package io.github.pplr.bazelcache.client.grpc

import build.bazel.remote.execution.v2.DigestFunction
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.Digests
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayOutputStream

class GrpcRemoteCacheClientTest {

    private val mapper = CacheKeyMapper("v1")
    private val gradleKey = "3748f79fa4230cfba17f559fee3220fb"
    private val payload = "gradle cache entry payload".toByteArray()
    private val payloadDigest = Digests.digestOf(payload)

    private lateinit var client: GrpcRemoteCacheClient

    @BeforeEach
    fun setUp() {
        server.reset()
        client = GrpcRemoteCacheClient(GrpcEndpoint(server.target))
    }

    @AfterEach
    fun tearDown() = client.close()

    // --- happy path ---------------------------------------------------------

    @Test
    fun `blob round-trips`() {
        client.writeBlob(payloadDigest) { payload.inputStream() }
        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(payloadDigest, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `action result round-trips through the full store then load sequence`() {
        val actionKey = mapper.actionKeyFor(gradleKey)
        client.writeBlob(payloadDigest) { payload.inputStream() }
        client.updateActionResult(actionKey, mapper.actionResultFor(payloadDigest))

        val blob = mapper.entryBlobOf(client.getActionResult(actionKey)!!)
        assertThat(blob).isEqualTo(payloadDigest)

        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(blob!!, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `empty blob is never uploaded and always reads back`() {
        // As in Bazel: servers treat the empty blob as always present.
        client.writeBlob(Digests.EMPTY) { ByteArray(0).inputStream() }
        assertThat(server.count("Write")).isZero()
        assertThat(client.readBlob(Digests.EMPTY, ByteArrayOutputStream())).isTrue()
    }

    @Test
    fun `large payload round-trips in many chunks`() {
        // Larger than gRPC's default 4 MiB message limit and than any one chunk,
        // so both flow control and multi-message reads are exercised.
        val big = ByteArray(5 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val digest = Digests.digestOf(big)
        client.writeBlob(digest) { big.inputStream() }
        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(digest, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(big)
    }

    @Test
    fun `resource names follow Bazel, with no instance prefix when none is set`() {
        client.writeBlob(payloadDigest) { payload.inputStream() }
        client.readBlob(payloadDigest, ByteArrayOutputStream())

        val (write, read) = server.resourceNames
        assertThat(write).matches("uploads/[0-9a-f-]{36}/blobs/${payloadDigest.hash}/${payload.size}")
        assertThat(read).isEqualTo("blobs/${payloadDigest.hash}/${payload.size}")
    }

    @Test
    fun `instance name is sent on every call and prefixes resource names`() {
        GrpcRemoteCacheClient(GrpcEndpoint(server.target), instanceName = "team-a/main").use { named ->
            val actionKey = mapper.actionKeyFor(gradleKey)
            named.findMissingBlobs(listOf(payloadDigest))
            named.writeBlob(payloadDigest) { payload.inputStream() }
            named.updateActionResult(actionKey, mapper.actionResultFor(payloadDigest))
            named.getActionResult(actionKey)
            named.readBlob(payloadDigest, ByteArrayOutputStream())
            named.getCapabilities()
        }
        assertThat(server.instanceNames).hasSize(6).containsOnly("team-a/main")
        assertThat(server.resourceNames).allMatch { it.startsWith("team-a/main/") }
    }

    @Test
    fun `findMissingBlobs reports only what the server lacks`() {
        client.writeBlob(payloadDigest) { payload.inputStream() }
        val other = Digests.digestOf("not uploaded".toByteArray())
        assertThat(client.findMissingBlobs(listOf(payloadDigest, other))).containsExactly(other)
        assertThat(client.findMissingBlobs(emptyList())).isEmpty()
    }

    // --- misses, which must never be errors ---------------------------------

    @Test
    fun `absent action result is a miss`() {
        assertThat(client.getActionResult(mapper.actionKeyFor("nothing-here"))).isNull()
    }

    @Test
    fun `absent blob is a miss`() {
        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `dangling action result reads as a miss`() {
        val actionKey = mapper.actionKeyFor(gradleKey)
        client.updateActionResult(actionKey, mapper.actionResultFor(payloadDigest))
        val blob = mapper.entryBlobOf(client.getActionResult(actionKey)!!)!!
        assertThat(client.readBlob(blob, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `truncated blob reads as a miss, not corrupt data`() {
        client.writeBlob(payloadDigest) { payload.inputStream() }
        server.truncateReadsTo = payload.size / 2
        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `blob whose content does not match its digest reads as a miss`() {
        server.cas[payloadDigest.hash] = "totally different bytes!!!".toByteArray().copyOf(payload.size)
        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `a server sending more bytes than the digest declares is a miss`() {
        server.cas[payloadDigest.hash] = payload + payload
        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    // --- failures -----------------------------------------------------------

    @ParameterizedTest
    @EnumSource(names = ["UNAVAILABLE", "UNKNOWN", "DEADLINE_EXCEEDED", "ABORTED", "INTERNAL", "RESOURCE_EXHAUSTED"])
    fun `Bazel's transient codes are reported as retryable`(code: Status.Code) {
        server.forceStatus = code.toStatus()
        assertThatThrownBy { client.getActionResult(mapper.actionKeyFor(gradleKey)) }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).retryable).isTrue() })
    }

    @ParameterizedTest
    @EnumSource(names = ["UNAUTHENTICATED", "PERMISSION_DENIED"])
    fun `auth failures are flagged and not retryable`(code: Status.Code) {
        server.forceStatus = code.toStatus()
        assertThatThrownBy { client.getActionResult(mapper.actionKeyFor(gradleKey)) }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({
                assertThat((it as CacheIoException).authFailure).isTrue()
                assertThat(it.retryable).isFalse()
            })
    }

    @Test
    fun `invalid argument is permanent`() {
        server.forceStatus = Status.INVALID_ARGUMENT
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).retryable).isFalse() })
    }

    @Test
    fun `a write refused for space is flagged so it does not trip the breaker`() {
        server.forceStatus = Status.RESOURCE_EXHAUSTED
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).payloadTooLarge).isTrue() })
    }

    @Test
    fun `a source shorter than its digest fails the write instead of committing a partial blob`() {
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.copyOf(3).inputStream() } }
            .isInstanceOf(java.io.EOFException::class.java)
        assertThat(server.cas).doesNotContainKey(payloadDigest.hash)
    }

    @Test
    fun `connection failure is retryable`() {
        GrpcRemoteCacheClient(GrpcEndpoint("grpc://127.0.0.1:1")).use { dead ->
            assertThatThrownBy { dead.getActionResult(mapper.actionKeyFor(gradleKey)) }
                .isInstanceOf(CacheIoException::class.java)
                .satisfies({ assertThat((it as CacheIoException).retryable).isTrue() })
        }
    }

    @Test
    fun `source is re-readable so an upload can be retried`() {
        server.failFirst = 1
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
        client.writeBlob(payloadDigest) { payload.inputStream() }
        assertThat(server.cas[payloadDigest.hash]).isEqualTo(payload)
    }

    // --- capabilities and probe ---------------------------------------------

    @Test
    fun `capabilities are read from the server`() {
        server.digestFunctions = listOf(DigestFunction.Value.SHA256)
        server.updateEnabled = false
        val caps = client.getCapabilities()!!.cacheCapabilities
        assertThat(caps.digestFunctionsList).containsExactly(DigestFunction.Value.SHA256)
        assertThat(caps.actionCacheUpdateCapabilities.updateEnabled).isFalse()
    }

    @Test
    fun `probe succeeds against a reachable server`() {
        assertThat(client.probe()).isTrue()
    }

    @Test
    fun `probe succeeds when the server demands credentials`() {
        server.forceStatus = Status.UNAUTHENTICATED
        assertThat(client.probe()).isTrue()
        assertThat(client.getCapabilities()).isNull()
    }

    @Test
    fun `probe fails against a closed port`() {
        GrpcRemoteCacheClient(GrpcEndpoint("grpc://127.0.0.1:1")).use { assertThat(it.probe()).isFalse() }
    }

    // --- misc ---------------------------------------------------------------

    @Test
    fun `headers are sent as lower-cased metadata`() {
        GrpcRemoteCacheClient(
            GrpcEndpoint(server.target),
            headers = mapOf("Authorization" to "Bearer s3cret", "X-Team" to "a"),
        ).use { it.getActionResult(mapper.actionKeyFor(gradleKey)) }
        assertThat(server.headers.last()).containsEntry("authorization", "Bearer s3cret").containsEntry("x-team", "a")
    }

    @Test
    fun `concurrent stores of the same key are safe`() {
        val threads = (1..16).map {
            Thread {
                client.writeBlob(payloadDigest) { payload.inputStream() }
                client.updateActionResult(mapper.actionKeyFor(gradleKey), mapper.actionResultFor(payloadDigest))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val result = client.getActionResult(mapper.actionKeyFor(gradleKey))
        assertThat(mapper.entryBlobOf(result!!)).isEqualTo(payloadDigest)
    }

    companion object {
        private val server = FakeGrpcCacheServer()

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
