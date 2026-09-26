package io.github.pplr.bazelcache.client

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import build.bazel.remote.execution.v2.OutputFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * GOLDEN VECTORS -- these freeze the cache keyspace.
 *
 * `actionKeyFor` determines the Action Cache address of every entry this plugin
 * has ever written. If one of these assertions fails, the change under test
 * silently invalidates every entry on every server running this plugin, and
 * splits users across two incompatible keyspaces.
 *
 * Do not "fix" a failure by updating the expected value. Either revert the
 * change, or bump `keyVersion` and add a new vector alongside the old one.
 *
 * The byte strings below were verified by hand against the protobuf wire format
 * and against the upstream field numbers in remote_execution.proto.
 */
class CacheKeyMapperTest {

    private val mapper = CacheKeyMapper("v1")

    @Test
    fun `constant Command serializes to the frozen bytes`() {
        // 0a 12 "gradle-build-cache" | 0a 02 "v1" | 3a 05 "entry"
        assertThat(Digests.toHex(mapper.commandBytes)).isEqualTo(
            "0a12677261646c652d6275696c642d63616368650a0276313a05656e747279",
        )
        assertThat(mapper.commandDigest.hash)
            .isEqualTo("d079cc9c8b8f9943a49fdb2b20573d629c062e9d5ef0c852ab7080c6e5d78738")
        assertThat(mapper.commandDigest.sizeBytes).isEqualTo(31)
    }

    @ParameterizedTest(name = "actionKey({0})")
    @CsvSource(
        "00000000000000000000000000000000, 96d4c4335a6f70af89666e80ee93c4b133b2cc22834c5cbb19353b295c4bf33d",
        "ffffffffffffffffffffffffffffffff, 698f3a17ecbbc36c42b9fe74712dde906a7b998abf7736dfd1060b09fcc276b9",
        "3748f79fa4230cfba17f559fee3220fb, 0070a3f346b3b5a13649aa4b175f794d5ee823d767906b5404d2d3fa2252e9cf",
    )
    fun `action key is frozen`(gradleKey: String, expectedHash: String) {
        val key = mapper.actionKeyFor(gradleKey)
        assertThat(key.hash).isEqualTo(expectedHash)
        assertThat(key.sizeBytes).isEqualTo(172)
    }

    @Test
    fun `ActionResult serializes to the frozen bytes`() {
        val blob = Digests.digestOf("hello".toByteArray())
        assertThat(Digests.toHex(mapper.actionResultFor(blob).toByteArray())).isEqualTo(
            "124d0a05656e74727912440a4032636632346462613566623061333065323665383362326163356239653239" +
                "65316231363165356331666137343235653733303433333632393338623938323410 05".replace(" ", ""),
        )
    }

    // --- Properties the golden vectors alone would not catch ----------------

    @Test
    fun `action key is always a server-acceptable 64-hex digest with non-zero size`() {
        // bazel-remote rejects size_bytes == 0 for any hash but the empty-blob
        // sentinel, and rejects anything that is not 64 lowercase hex.
        for (key in listOf("", "a", "0".repeat(32), "z".repeat(40), "3748f79fa4230cfba17f559fee3220fb")) {
            val d = mapper.actionKeyFor(key)
            assertThat(Digests.isValidHash(d.hash)).describedAs("hash for %s", key).isTrue()
            assertThat(d.sizeBytes).describedAs("size for %s", key).isGreaterThan(0)
        }
    }

    @Test
    fun `distinct gradle keys map to distinct action keys`() {
        val keys = (0 until 2_000).map { "%032x".format(it) }
        val mapped = keys.map { mapper.actionKeyFor(it).hash }.toSet()
        assertThat(mapped).hasSize(keys.size)
    }

    @Test
    fun `keyVersion partitions the keyspace`() {
        val key = "3748f79fa4230cfba17f559fee3220fb"
        assertThat(CacheKeyMapper("v1").actionKeyFor(key).hash)
            .isNotEqualTo(CacheKeyMapper("v2").actionKeyFor(key).hash)
    }

    @Test
    fun `mapping is deterministic across instances`() {
        val key = "3748f79fa4230cfba17f559fee3220fb"
        assertThat(CacheKeyMapper("v1").actionKeyFor(key)).isEqualTo(CacheKeyMapper("v1").actionKeyFor(key))
    }

    @Test
    fun `gradle key is treated as opaque text, not hex`() {
        // getHashCode() is a String and Gradle has changed its hash before;
        // a non-hex or differently sized key must still map cleanly.
        for (key in listOf("not-hex-at-all", "0".repeat(64), "ABCDEF", "é")) {
            assertThat(Digests.isValidHash(mapper.actionKeyFor(key).hash)).isTrue()
        }
    }

    @Test
    fun `input root is the empty blob and is never sized`() {
        val action = mapper.actionFor("k")
        assertThat(action.inputRootDigest.hash).isEqualTo(Digests.EMPTY_SHA256_HEX)
        assertThat(action.inputRootDigest.sizeBytes).isEqualTo(0)
    }

    // --- Round trip ---------------------------------------------------------

    @Test
    fun `entry blob round-trips through the ActionResult`() {
        val blob = Digests.digestOf("payload".toByteArray())
        val parsed = ActionResult.parseFrom(mapper.actionResultFor(blob).toByteArray())
        assertThat(mapper.entryBlobOf(parsed)).isEqualTo(blob)
    }

    @Test
    fun `unknown fields in a server-rewritten ActionResult are tolerated`() {
        // Servers MAY modify an ActionResult (BuildBuddy injects worker metadata),
        // and the cache may be shared with real Bazel builds.
        val blob = Digests.digestOf("payload".toByteArray())
        val enriched = mapper.actionResultFor(blob).toBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("something-else").setDigest(Digests.EMPTY))
            .build()
        assertThat(mapper.entryBlobOf(ActionResult.parseFrom(enriched.toByteArray()))).isEqualTo(blob)
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-output-files", "no-digest", "bad-hash", "negative-size"])
    fun `a malformed ActionResult is a miss, not an error`(case: String) {
        val result = when (case) {
            "no-output-files" -> ActionResult.getDefaultInstance()
            "no-digest" -> ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder().setPath("entry")).build()
            "bad-hash" -> ActionResult.newBuilder()
                .addOutputFiles(
                    OutputFile.newBuilder().setPath("entry")
                        .setDigest(Digest.newBuilder().setHash("NOTHEX").setSizeBytes(1)),
                ).build()
            "negative-size" -> ActionResult.newBuilder()
                .addOutputFiles(
                    OutputFile.newBuilder().setPath("entry")
                        .setDigest(Digest.newBuilder().setHash("a".repeat(64)).setSizeBytes(-1)),
                ).build()
            else -> error(case)
        }
        assertThat(mapper.entryBlobOf(result)).isNull()
    }
}
