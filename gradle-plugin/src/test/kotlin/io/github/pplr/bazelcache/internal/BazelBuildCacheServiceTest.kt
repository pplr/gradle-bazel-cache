package io.github.pplr.bazelcache.internal

import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.Digests
import io.github.pplr.bazelcache.client.grpc.FakeGrpcCacheServer
import io.github.pplr.bazelcache.client.grpc.GrpcEndpoint
import io.github.pplr.bazelcache.client.grpc.GrpcRemoteCacheClient
import io.github.pplr.bazelcache.client.http.FakeBazelCacheServer
import io.github.pplr.bazelcache.client.http.HttpRemoteCacheClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheException
import org.gradle.caching.BuildCacheKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

class BazelBuildCacheServiceTest {

    @TempDir lateinit var spool: Path

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    private fun service(
        strict: Boolean = false,
        maxEntrySize: Long = Long.MAX_VALUE,
        inMemoryLimit: Long = 8L * 1024 * 1024,
    ) = BazelBuildCacheService(
        client = HttpRemoteCacheClient(CacheEndpoint(server.baseUrl)),
        mapper = CacheKeyMapper("v1"),
        spoolDirectory = spool,
        maxEntrySizeBytes = maxEntrySize,
        inMemoryLimitBytes = inMemoryLimit,
        strict = strict,
    )

    private fun grpcService() = BazelBuildCacheService(
        client = GrpcRemoteCacheClient(GrpcEndpoint(grpcServer.target)),
        mapper = CacheKeyMapper("v1"),
        spoolDirectory = spool,
        maxEntrySizeBytes = Long.MAX_VALUE,
        inMemoryLimitBytes = 8L * 1024 * 1024,
        strict = false,
    )

    @BeforeEach fun setUp() {
        server.reset()
        grpcServer.reset()
    }

    @ParameterizedTest(name = "streaming={0} inMemoryLimit={1}")
    @CsvSource("true, 8388608", "false, 8388608", "false, 0")
    fun `store then load round-trips on every entry-source path`(streaming: Boolean, inMemoryLimit: Long) {
        // Three paths must produce identical bytes: Gradle >= 9.7 streaming, and
        // the pre-9.7 spool fallback both in memory and on disk.
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service(inMemoryLimit = inMemoryLimit).use { svc ->
            svc.store(key, if (streaming) writer(payload) else legacyWriter(payload))
            val sink = ByteArrayOutputStream()
            assertThat(svc.load(key, reader(sink))).isTrue()
            assertThat(sink.toByteArray()).isEqualTo(payload)
        }
    }

    @Test
    fun `both entry-source paths agree on the digest`() {
        // They must: the digest is the CAS address, so a disagreement would
        // split users onto two keyspaces depending on their Gradle version.
        val streamed = CacheEntrySources.of(writer(payload), spool, 8L * 1024 * 1024)
        val spooled = CacheEntrySources.of(legacyWriter(payload), spool, 0)
        try {
            assertThat(streamed.digest).isEqualTo(spooled.digest)
            assertThat(streamed.digest.sizeBytes).isEqualTo(payload.size.toLong())
        } finally {
            streamed.close(); spooled.close()
        }
    }

    @Test
    fun `store publishes the CAS blob and the AC entry`() {
        // No server verifies dependencies on write, so the reverse order would
        // publish a pointer to a blob that is not there yet.
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service().use { it.store(key, writer(payload)) }

        val mapper = CacheKeyMapper("v1")
        assertThat(server.ac[mapper.actionKeyFor(key.hashCode).hash]).isNotNull()
        assertThat(server.cas).containsKey(Digests.digestOf(payload).hash)
    }

    @Test
    fun `store publishes the synthetic Command and Action into the CAS`() {
        val mapper = CacheKeyMapper("v1")
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service().use { it.store(key, writer(payload)) }

        assertThat(server.cas).containsKey(mapper.commandDigest.hash)
        assertThat(server.cas).containsKey(mapper.actionKeyFor(key.hashCode).hash)
        assertThat(server.cas[mapper.commandDigest.hash]).isEqualTo(mapper.commandBytes)
    }

    @Test
    fun `load of an unknown key is a plain miss`() {
        service().use { svc ->
            assertThat(svc.load(key("a".repeat(32)), reader(ByteArrayOutputStream()))).isFalse()
            assertThat(svc.statsForTesting().misses.get()).isEqualTo(1)
            assertThat(svc.statsForTesting().errors.get()).isZero()
        }
    }

    @Test
    fun `dangling AC entry is a miss, not an error`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service().use { it.store(key, writer(payload)) }
        server.cas.clear() // CAS evicted, AC survived

        service().use { svc ->
            assertThat(svc.load(key, reader(ByteArrayOutputStream()))).isFalse()
            assertThat(svc.statsForTesting().errors.get()).isZero()
        }
    }

    @Test
    fun `truncated body is a miss and no partial data reaches Gradle`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service().use { it.store(key, writer(payload)) }
        server.truncateCasTo = payload.size / 3

        val sink = ByteArrayOutputStream()
        service().use { svc ->
            assertThat(svc.load(key, reader(sink))).isFalse()
            assertThat(svc.statsForTesting().errors.get()).isZero()
        }
        assertThat(sink.size()).describedAs("reader must not see partial bytes").isZero()
    }

    @Test
    fun `oversize entries are skipped rather than attempted`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service(maxEntrySize = 1024).use { svc ->
            svc.store(key, writer(payload))
            assertThat(svc.statsForTesting().skipped.get()).isEqualTo(1)
            assertThat(svc.statsForTesting().errors.get()).isZero()
        }
        assertThat(server.putCount.get()).describedAs("nothing should be uploaded").isZero()
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 500, 503])
    fun `server failures never escape load or store`(status: Int) {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        server.forceStatus = status
        service().use { svc ->
            svc.store(key, writer(payload))
            assertThat(svc.load(key, reader(ByteArrayOutputStream()))).isFalse()
            assertThat(svc.statsForTesting().errors.get()).isGreaterThan(0)
        }
    }

    @Test
    fun `strict mode surfaces failures as BuildCacheException`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        server.forceStatus = 503
        service(strict = true).use { svc ->
            assertThatThrownBy { svc.store(key, writer(payload)) }
                .isInstanceOf(BuildCacheException::class.java)
        }
    }

    @Test
    fun `operations after close are inert`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        val svc = service()
        svc.close()
        svc.store(key, writer(payload))
        assertThat(svc.load(key, reader(ByteArrayOutputStream()))).isFalse()
        svc.close() // idempotent
    }

    @Test
    fun `spool directory is left empty after stores and loads`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        service(inMemoryLimit = 0).use { svc ->
            svc.store(key, writer(payload))
            svc.load(key, reader(ByteArrayOutputStream()))
        }
        assertThat(spool.toFile().listFiles()?.toList() ?: emptyList<Any>())
            .describedAs("temp entries must not accumulate in a long-lived daemon")
            .isEmpty()
    }

    @Test
    fun `concurrent stores and loads are safe`() {
        service().use { svc ->
            val threads = (0 until 16).map { i ->
                Thread {
                    val k = key("%032x".format(i))
                    svc.store(k, writer(payload))
                    svc.load(k, reader(ByteArrayOutputStream()))
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertThat(svc.statsForTesting().errors.get()).isZero()
            assertThat(svc.statsForTesting().hits.get()).isEqualTo(16)
        }
    }

    // --- gRPC ---------------------------------------------------------------

    @Test
    fun `store then load round-trips over gRPC`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        grpcService().use { svc ->
            svc.store(key, writer(payload))
            val sink = ByteArrayOutputStream()
            assertThat(svc.load(key, reader(sink))).isTrue()
            assertThat(sink.toByteArray()).isEqualTo(payload)
            assertThat(svc.statsForTesting().errors.get()).isZero()
        }
        val mapper = CacheKeyMapper("v1")
        assertThat(grpcServer.cas).containsKeys(
            Digests.digestOf(payload).hash,
            mapper.commandDigest.hash,
            mapper.actionKeyFor(key.hashCode).hash,
        )
    }

    @Test
    fun `a store asks FindMissingBlobs once and uploads only what is missing`() {
        val first = key("3748f79fa4230cfba17f559fee3220fb")
        val second = key("0".repeat(32))
        grpcService().use { it.store(first, writer(payload)) }
        assertThat(grpcServer.count("FindMissingBlobs")).isEqualTo(1)
        assertThat(grpcServer.count("Write")).isEqualTo(3) // entry, Command, Action

        // A new build storing the same output under another key: the entry and
        // the Command are already there, so only the new Action is uploaded.
        grpcServer.calls.clear()
        grpcService().use { it.store(second, writer(payload)) }
        assertThat(grpcServer.count("FindMissingBlobs")).isEqualTo(1)
        assertThat(grpcServer.count("Write")).isEqualTo(1)
        assertThat(grpcServer.count("UpdateActionResult")).isEqualTo(1)
    }

    @Test
    fun `gRPC failures never escape load or store`() {
        val key = key("3748f79fa4230cfba17f559fee3220fb")
        grpcServer.forceStatus = io.grpc.Status.UNAVAILABLE
        grpcService().use { svc ->
            svc.store(key, writer(payload))
            assertThat(svc.load(key, reader(ByteArrayOutputStream()))).isFalse()
            assertThat(svc.statsForTesting().errors.get()).isGreaterThan(0)
        }
    }

    private fun key(hash: String) = object : BuildCacheKey {
        override fun getHashCode() = hash
        override fun toByteArray() = hash.toByteArray()
    }

    /** Gradle >= 9.7: getInputStream() returns a fresh stream per call. */
    private fun writer(bytes: ByteArray) = object : BuildCacheEntryWriter {
        override fun writeTo(output: OutputStream) = output.use { it.write(bytes) }
        override fun getInputStream(): InputStream = bytes.inputStream()
        override fun getSize() = bytes.size.toLong()
    }

    /**
     * Gradle < 9.7, where getInputStream() does not exist. We compile against
     * 9.8, so the method is always on the interface and the MethodHandle lookup
     * always succeeds; throwing here reproduces what an older Gradle does at
     * invoke time and exercises the spool fallback.
     */
    private fun legacyWriter(bytes: ByteArray) = object : BuildCacheEntryWriter {
        override fun writeTo(output: OutputStream) = output.use { it.write(bytes) }
        override fun getInputStream(): InputStream = throw UnsupportedOperationException("pre-9.7 Gradle")
        override fun getSize() = bytes.size.toLong()
    }

    private fun reader(sink: OutputStream) = BuildCacheEntryReader { input: InputStream ->
        input.use { it.copyTo(sink) }
    }

    companion object {
        private val server = FakeBazelCacheServer()
        private val grpcServer = FakeGrpcCacheServer()

        @JvmStatic @AfterAll fun stop() {
            server.close()
            grpcServer.close()
        }
    }
}
