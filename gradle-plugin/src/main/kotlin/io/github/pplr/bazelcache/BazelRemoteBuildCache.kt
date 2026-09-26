package io.github.pplr.bazelcache

import org.gradle.caching.configuration.AbstractBuildCache

/**
 * User-facing configuration for the Bazel remote build cache backend.
 *
 * This object is serialized into Gradle's configuration cache entry (Gradle
 * round-trips `buildCache.remote` through `ConfigurationCacheState`), so it must
 * hold only simple, serializable values. In particular it must never hold a live
 * HTTP client, socket, thread pool, or a reference to `Settings`/`Gradle` — those
 * are built in [BazelRemoteBuildCacheServiceFactory], which runs on every build.
 *
 * Credentials are referenced *by environment variable name*, never by value, so
 * that no secret is ever written into the configuration cache entry on disk.
 */
abstract class BazelRemoteBuildCache : AbstractBuildCache() {

    /** Base URL of the cache server, e.g. `https://cache.internal:8080`. */
    var endpoint: String? = null

    /**
     * Optional path prefix / REAPI instance name. Applied as a URL path segment
     * before `ac/` and `cas/`. Note bazel-remote ignores it for `/cas/`.
     */
    var instanceName: String = ""

    /**
     * Name of the environment variable holding the bearer token, if any.
     * The value is read in the factory, never at configuration time.
     */
    var tokenEnvironmentVariable: String = "BAZEL_CACHE_TOKEN"

    /**
     * Cache keyspace version. Bumping this invalidates every entry this plugin
     * has ever written, without touching the server. See docs/PROTOCOL.md.
     */
    var keyVersion: String = "v1"

    /** Entries larger than this are not uploaded at all. */
    var maxEntrySizeBytes: Long = 256L * 1024 * 1024

    /** Verify the SHA-256 of downloaded blobs before handing them to Gradle. */
    var verifyDownloads: Boolean = true

    /**
     * When true, cache faults are rethrown as `BuildCacheException` instead of
     * being absorbed. Off by default: Gradle disables the remote cache for the
     * whole build on the first failure it sees.
     */
    var strict: Boolean = false
}
