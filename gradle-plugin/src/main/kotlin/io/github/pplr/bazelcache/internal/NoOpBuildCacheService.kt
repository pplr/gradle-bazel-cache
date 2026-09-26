package io.github.pplr.bazelcache.internal

import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService

/**
 * Used when the cache is unreachable or misconfigured at service-creation time.
 *
 * Returning this rather than throwing is deliberate: an exception from the
 * factory fails the whole build, and an unreachable cache should degrade to
 * "no caching", not to "broken build". Every call is a cheap local no-op, so a
 * misconfigured endpoint costs nothing instead of producing one doomed network
 * round-trip per task.
 */
internal object NoOpBuildCacheService : BuildCacheService {
    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean = false
    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) = Unit
    override fun close() = Unit
}
