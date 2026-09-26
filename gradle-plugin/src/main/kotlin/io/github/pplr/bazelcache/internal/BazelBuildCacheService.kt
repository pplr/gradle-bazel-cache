package io.github.pplr.bazelcache.internal

import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.RemoteCacheClient
import org.gradle.api.logging.Logging
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheException
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Gradle's build cache onto a Bazel remote cache.
 *
 * ## Why nothing here throws
 *
 * Gradle performs no retries, and the first failure it observes disables the
 * remote cache for the rest of the build (`BaseRemoteBuildCacheServiceHandle`
 * catches `Exception`, warns, and sets `disabled`). So a single transient blip
 * on a 2000-task build costs every remaining cache hit. Faults are therefore
 * absorbed here, counted, and reported once in [close]. Users who prefer the
 * loud behaviour can set `strict = true`.
 *
 * Thread-safe: Gradle calls [load] and [store] concurrently from task worker
 * threads against this single instance.
 */
internal class BazelBuildCacheService(
    private val client: RemoteCacheClient,
    private val mapper: CacheKeyMapper,
    private val spoolDirectory: Path,
    private val maxEntrySizeBytes: Long,
    private val inMemoryLimitBytes: Long,
    private val strict: Boolean,
    private val stats: CacheStats = CacheStats(),
) : BuildCacheService {

    private val logger = Logging.getLogger(BazelBuildCacheService::class.java)

    /** Digests we have already uploaded in this build; avoids redundant PUTs. */
    private val knownPresent = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var closed = false

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        if (closed) return false
        val actionKey = mapper.actionKeyFor(key.hashCode)

        return try {
            val result = client.getActionResult(actionKey)
            if (result == null) {
                stats.misses.incrementAndGet()
                return false
            }

            val blob = mapper.entryBlobOf(result)
            if (blob == null) {
                // Someone else's entry under a colliding key, or a future
                // version of this plugin. Not an error.
                stats.misses.incrementAndGet()
                return false
            }

            // Spool before handing anything to Gradle. readBlob verifies the
            // SHA-256 as it streams; a truncated body reaching Gradle's unpacker
            // would throw mid-extraction and cost the whole build's caching.
            Files.createDirectories(spoolDirectory)
            val temp = Files.createTempFile(spoolDirectory, "load-", ".bin")
            try {
                val complete = Files.newOutputStream(temp).use { out -> client.readBlob(blob, out) }
                if (!complete) {
                    // Dangling reference after CAS eviction, or a corrupt body.
                    stats.misses.incrementAndGet()
                    return false
                }
                stats.bytesDown.addAndGet(blob.sizeBytes)
                // readFrom closes the stream it is given.
                Files.newInputStream(temp).use { input -> reader.readFrom(input) }
                stats.hits.incrementAndGet()
                true
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Throwable) {
            if (Thread.currentThread().isInterrupted) return false
            fail("load", key, e)
            false
        }
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        if (closed) return

        try {
            CacheEntrySources.of(writer, spoolDirectory, inMemoryLimitBytes).use { entry ->
                if (entry.digest.sizeBytes > maxEntrySizeBytes) {
                    // Attempting it would earn a server rejection, which would
                    // otherwise look like a cache fault and disable everything.
                    stats.skipped.incrementAndGet()
                    logger.info(
                        "bazel-cache: skipping {} ({} bytes exceeds maxEntrySizeBytes={})",
                        key.hashCode, entry.digest.sizeBytes, maxEntrySizeBytes,
                    )
                    return
                }

                // CAS before AC, always: no server checks dependencies on write,
                // so an AC entry published first names a blob that is not there.
                if (knownPresent.add(entry.digest.hash)) {
                    client.writeBlob(entry.digest) { entry.open() }
                    stats.bytesUp.addAndGet(entry.digest.sizeBytes)
                }

                uploadActionBestEffort(key.hashCode)

                client.updateActionResult(mapper.actionKeyFor(key.hashCode), mapper.actionResultFor(entry.digest))
                stats.stores.incrementAndGet()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            if (Thread.currentThread().isInterrupted) return
            fail("store", key, e)
        }
    }

    /**
     * Publishes the synthetic `Command` and `Action` that the `ActionResult`
     * claims to describe.
     *
     * Best-effort by design: REAPI asks clients to upload them, and they make an
     * entry self-describing for anyone inspecting the cache, but no server we
     * target verifies their presence. A failure here must not cost the store --
     * the entry blob and AC write alone produce a working cache entry.
     */
    private fun uploadActionBestEffort(gradleKey: String) {
        try {
            if (knownPresent.add(mapper.commandDigest.hash)) {
                client.writeBlob(mapper.commandDigest) { mapper.commandBytes.inputStream() }
            }
            val actionBytes = mapper.actionBytesFor(gradleKey)
            val actionDigest = mapper.actionKeyFor(gradleKey)
            if (knownPresent.add(actionDigest.hash)) {
                client.writeBlob(actionDigest) { actionBytes.inputStream() }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            logger.debug("bazel-cache: could not publish synthetic Action (harmless): {}", e.message)
        }
    }

    private fun fail(operation: String, key: BuildCacheKey, e: Throwable) {
        val message = "${e::class.simpleName}: ${e.message}"
        stats.recordError(message)
        logger.debug("bazel-cache: {} failed for {}: {}", operation, key.hashCode, message, e)
        if (strict) throw BuildCacheException("bazel-cache: $operation failed for ${key.hashCode}", e)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            client.close()
        } catch (e: Throwable) {
            logger.debug("bazel-cache: error closing client: {}", e.message)
        }
        if (stats.active()) {
            if (stats.errors.get() > 0) logger.warn(stats.summary()) else logger.info(stats.summary())
        }
    }

    internal fun statsForTesting(): CacheStats = stats
}
