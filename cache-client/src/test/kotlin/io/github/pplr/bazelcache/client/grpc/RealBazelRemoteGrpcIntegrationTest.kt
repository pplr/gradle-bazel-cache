package io.github.pplr.bazelcache.client.grpc

import build.bazel.remote.execution.v2.DigestFunction
import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.Digests
import io.github.pplr.bazelcache.client.http.HttpRemoteCacheClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The gRPC transport against a REAL bazel-remote: the counterpart of
 * `RealBazelRemoteIntegrationTest`, over the server's REAPI port.
 *
 * Opt-in: set BAZEL_REMOTE_GRPC_URL. With BAZEL_REMOTE_HTTP_URL also set, the
 * cross-transport test proves an entry stored over one protocol loads over the
 * other -- the keyspace does not depend on the transport.
 *
 *     cd examples/quickstart && podman compose up -d
 *     BAZEL_REMOTE_GRPC_URL=grpc://127.0.0.1:9092 \
 *       BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ ./gradlew :cache-client:integrationTest
 */
@Tag("integration")
class RealBazelRemoteGrpcIntegrationTest {

    private val mapper = CacheKeyMapper("v1")

    private fun freshKey() = "it-${UUID.randomUUID()}"

    private fun client(instanceName: String = "") = GrpcRemoteCacheClient(GrpcEndpoint(target!!), instanceName)

    @Test
    fun `the server advertises SHA256 and accepts action results`() {
        client().use { c ->
            val cache = c.getCapabilities()!!.cacheCapabilities
            assertThat(cache.digestFunctionsList).contains(DigestFunction.Value.SHA256)
            assertThat(cache.actionCacheUpdateCapabilities.updateEnabled).isTrue()
        }
    }

    @Test
    fun `synthetic ActionResult is accepted by a validating server`() {
        val key = freshKey()
        val payload = "entry-for-$key".toByteArray()
        val blob = Digests.digestOf(payload)

        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))

            val result = c.getActionResult(mapper.actionKeyFor(key))
            assertThat(result).describedAs("server should serve our AC entry back").isNotNull()
            assertThat(mapper.entryBlobOf(result!!)).isEqualTo(blob)
        }
    }

    @Test
    fun `full store and load cycle round-trips the exact bytes across many chunks`() {
        val key = freshKey()
        // Several MiB: many ByteStream chunks each way, and more than gRPC's
        // default 4 MiB inbound message limit in total.
        val payload = ByteArray(6 * 1024 * 1024 + 3) { ((it * 31) % 251).toByte() }
        val blob = Digests.digestOf(payload)

        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.writeBlob(mapper.commandDigest) { mapper.commandBytes.inputStream() }
            val actionBytes = mapper.actionBytesFor(key)
            c.writeBlob(mapper.actionKeyFor(key)) { actionBytes.inputStream() }
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))

            val result = c.getActionResult(mapper.actionKeyFor(key))!!
            val sink = ByteArrayOutputStream()
            assertThat(c.readBlob(mapper.entryBlobOf(result)!!, sink)).isTrue()
            assertThat(sink.toByteArray()).isEqualTo(payload)
        }
    }

    @Test
    fun `FindMissingBlobs reports exactly what is absent`() {
        val key = freshKey()
        val present = "present-$key".toByteArray()
        val presentDigest = Digests.digestOf(present)
        val absentDigest = Digests.digestOf("absent-$key".toByteArray())

        client().use { c ->
            c.writeBlob(presentDigest) { present.inputStream() }
            assertThat(c.findMissingBlobs(listOf(presentDigest, absentDigest))).containsExactly(absentDigest)
        }
    }

    @Test
    fun `uploading a blob the server already has succeeds`() {
        // bazel-remote may end the Write early with committed_size == size.
        val payload = ByteArray(256 * 1024) { (it % 7).toByte() }
        val blob = Digests.digestOf(payload)
        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.writeBlob(blob) { payload.inputStream() }
        }
    }

    @Test
    fun `a dangling AC entry reads as a miss`() {
        val key = freshKey()
        val missing = Digests.digestOf("never-uploaded-$key".toByteArray())

        client().use { c ->
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(missing))
            assertThat(c.getActionResult(mapper.actionKeyFor(key)))
                .describedAs("deps check should suppress an entry with no blob")
                .isNull()
        }
    }

    @Test
    fun `unknown key and unknown blob are clean misses`() {
        client().use { c ->
            assertThat(c.getActionResult(mapper.actionKeyFor(freshKey()))).isNull()
            val absent = Digests.digestOf("absent-${freshKey()}".toByteArray())
            assertThat(c.readBlob(absent, ByteArrayOutputStream())).isFalse()
        }
    }

    @Test
    fun `an instance name is accepted`() {
        // bazel-remote ignores it by default; what matters is that a prefixed
        // ByteStream resource name parses on a real server.
        val key = freshKey()
        val payload = "instance-$key".toByteArray()
        val blob = Digests.digestOf(payload)
        client(instanceName = "team-a/main").use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))
            val sink = ByteArrayOutputStream()
            assertThat(c.readBlob(mapper.entryBlobOf(c.getActionResult(mapper.actionKeyFor(key))!!)!!, sink)).isTrue()
            assertThat(sink.toByteArray()).isEqualTo(payload)
        }
    }

    @Test
    fun `an entry stored over HTTP loads over gRPC, and the reverse`() {
        val httpUrl = System.getenv("BAZEL_REMOTE_HTTP_URL")
        assumeTrue(httpUrl != null, "BAZEL_REMOTE_HTTP_URL not set; skipping cross-transport test")

        HttpRemoteCacheClient(CacheEndpoint(httpUrl!!)).use { http ->
            client().use { grpc ->
                for ((writer, reader) in listOf(http to grpc, grpc to http)) {
                    val key = freshKey()
                    val payload = "cross-$key".toByteArray()
                    val blob = Digests.digestOf(payload)
                    writer.writeBlob(blob) { payload.inputStream() }
                    writer.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))

                    val result = reader.getActionResult(mapper.actionKeyFor(key))
                    assertThat(result).describedAs("${writer.javaClass.simpleName} -> ${reader.javaClass.simpleName}")
                        .isNotNull()
                    val sink = ByteArrayOutputStream()
                    assertThat(reader.readBlob(mapper.entryBlobOf(result!!)!!, sink)).isTrue()
                    assertThat(sink.toByteArray()).isEqualTo(payload)
                }
            }
        }
    }

    @Test
    fun `probe succeeds`() {
        client().use { c -> assertThat(c.probe()).isTrue() }
    }

    companion object {
        private var target: String? = null

        @JvmStatic
        @BeforeAll
        fun requireServer() {
            target = System.getenv("BAZEL_REMOTE_GRPC_URL")
            assumeTrue(target != null, "BAZEL_REMOTE_GRPC_URL not set; skipping real-server gRPC tests")
        }
    }
}
