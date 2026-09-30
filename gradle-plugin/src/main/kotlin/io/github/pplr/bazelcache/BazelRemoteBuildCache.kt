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
     * Same meaning as Bazel's `--remote_instance_name`: a value passed verbatim as
     * `instance_name` in the Remote Execution API, whose meaning the server decides.
     *
     * Like Bazel, it has **no effect over HTTP**: Bazel's HTTP cache client never
     * uses it. For an HTTP path prefix, put it in [endpoint], as Bazel users put it
     * in `--remote_cache` (e.g. `https://cache.example.com/team-a/`). It is sent
     * once the gRPC transport lands.
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
     * Where entries are spooled while their digest is computed.
     *
     * Defaults to the JVM temp directory. Worth overriding in CI, where
     * `java.io.tmpdir` is often a small tmpfs that a few hundred megabytes of
     * build outputs will fill.
     */
    var spoolDirectory: String? = null

    /** Entries at or below this size are spooled in memory rather than to disk. */
    var inMemoryLimitBytes: Long = 8L * 1024 * 1024

    /** Extra request headers, as header name -> environment variable NAME. */
    var headersFromEnvironment: Map<String, String> = emptyMap()

    /**
     * When true, cache faults are rethrown as `BuildCacheException` instead of
     * being absorbed. Off by default: Gradle disables the remote cache for the
     * whole build on the first failure it sees.
     */
    var strict: Boolean = false
}
