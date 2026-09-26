package io.github.pplr.bazelcache.client.http

import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.Digests
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream

class HttpRemoteCacheClientTest {

    private val mapper = CacheKeyMapper("v1")
    private val gradleKey = "3748f79fa4230cfba17f559fee3220fb"
    private val payload = "gradle cache entry payload".toByteArray()
    private val payloadDigest = Digests.digestOf(payload)

    private lateinit var client: HttpRemoteCacheClient

    @BeforeEach
    fun setUp() {
        server.reset()
        client = HttpRemoteCacheClient(CacheEndpoint(server.baseUrl))
    }

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

        val result = client.getActionResult(actionKey)
        assertThat(result).isNotNull()
        val blob = mapper.entryBlobOf(result!!)
        assertThat(blob).isEqualTo(payloadDigest)

        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(blob!!, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(payload)
    }

    @Test
    fun `empty payload round-trips`() {
        val empty = Digests.digestOf(ByteArray(0))
        client.writeBlob(empty) { ByteArray(0).inputStream() }
        assertThat(client.readBlob(empty, ByteArrayOutputStream())).isTrue()
    }

    @Test
    fun `large payload round-trips`() {
        val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val digest = Digests.digestOf(big)
        client.writeBlob(digest) { big.inputStream() }
        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(digest, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(big)
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
        // The realistic LRU case: the AC entry outlives the CAS blob it names.
        val actionKey = mapper.actionKeyFor(gradleKey)
        client.updateActionResult(actionKey, mapper.actionResultFor(payloadDigest))

        val result = client.getActionResult(actionKey)
        assertThat(result).isNotNull()
        assertThat(client.readBlob(mapper.entryBlobOf(result!!)!!, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `truncated blob body reads as a miss, not corrupt data`() {
        // Without this check the partial body reaches Gradle's unpacker, which
        // throws mid-extraction; Gradle counts that as a cache failure and
        // disables the remote cache for the whole build.
        client.writeBlob(payloadDigest) { payload.inputStream() }
        server.truncateCasTo = payload.size / 2

        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `blob whose content does not match its digest reads as a miss`() {
        server.cas[payloadDigest.hash] = "totally different bytes".toByteArray()
        assertThat(client.readBlob(payloadDigest, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun `garbage in the action cache is a miss, not a parse failure`() {
        // A shared cache may hold a real Bazel entry, or one from a future
        // version of this plugin, under a colliding key.
        val actionKey = mapper.actionKeyFor(gradleKey)
        server.ac[actionKey.hash] = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x42)
        assertThat(client.getActionResult(actionKey)).isNull()
    }

    // --- failures -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `transient statuses are reported as retryable`(status: Int) {
        server.forceStatus = status
        assertThatThrownBy { client.getActionResult(mapper.actionKeyFor(gradleKey)) }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).retryable).isTrue() })
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `auth failures are flagged and not retryable`(status: Int) {
        // Retrying a bad credential across thousands of tasks is the worst
        // possible behaviour; the layer above trips the breaker immediately.
        server.forceStatus = status
        assertThatThrownBy { client.getActionResult(mapper.actionKeyFor(gradleKey)) }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({
                assertThat((it as CacheIoException).authFailure).isTrue()
                assertThat(it.retryable).isFalse()
            })
    }

    @ParameterizedTest
    @ValueSource(ints = [413, 507])
    fun `size rejections are flagged so they do not trip the breaker`(status: Int) {
        server.forceStatus = status
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).payloadTooLarge).isTrue() })
    }

    @Test
    fun `connection failure is retryable`() {
        val dead = HttpRemoteCacheClient(CacheEndpoint("http://127.0.0.1:1/"))
        assertThatThrownBy { dead.getActionResult(mapper.actionKeyFor(gradleKey)) }
            .isInstanceOf(CacheIoException::class.java)
            .satisfies({ assertThat((it as CacheIoException).retryable).isTrue() })
    }

    // --- probe --------------------------------------------------------------

    @Test
    fun `probe succeeds against a reachable server`() {
        assertThat(client.probe()).isTrue()
    }

    @Test
    fun `probe succeeds when the server demands credentials`() {
        // It answered, so it exists. A wrong token deserves a clearer message
        // than "unreachable".
        server.forceStatus = 401
        assertThat(client.probe()).isTrue()
    }

    @Test
    fun `probe fails against a closed port`() {
        assertThat(HttpRemoteCacheClient(CacheEndpoint("http://127.0.0.1:1/")).probe()).isFalse()
    }

    // --- misc ---------------------------------------------------------------

    @Test
    fun `custom headers are sent`() {
        val withAuth = HttpRemoteCacheClient(
            CacheEndpoint(server.baseUrl),
            headers = mapOf("Authorization" to "Bearer s3cret"),
        )
        withAuth.writeBlob(payloadDigest) { payload.inputStream() }
        assertThat(server.putCount.get()).isEqualTo(1)
    }

    @Test
    fun `source is re-readable so an upload can be retried`() {
        server.failFirst = 1
        assertThatThrownBy { client.writeBlob(payloadDigest) { payload.inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
        // Second attempt with a fresh stream from the same supplier succeeds.
        client.writeBlob(payloadDigest) { payload.inputStream() }
        val sink = ByteArrayOutputStream()
        assertThat(client.readBlob(payloadDigest, sink)).isTrue()
        assertThat(sink.toByteArray()).isEqualTo(payload)
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
        private val server = FakeBazelCacheServer()

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
