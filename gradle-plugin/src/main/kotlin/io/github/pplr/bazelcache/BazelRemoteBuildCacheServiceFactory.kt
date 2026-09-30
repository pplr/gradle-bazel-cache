package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.http.HttpRemoteCacheClient
import io.github.pplr.bazelcache.client.resilience.ResilientRemoteCacheClient
import io.github.pplr.bazelcache.internal.BazelBuildCacheService
import io.github.pplr.bazelcache.internal.NoOpBuildCacheService
import io.github.pplr.bazelcache.internal.describeEndpoint
import io.github.pplr.bazelcache.internal.sweepStaleSpoolFiles
import org.gradle.api.InvalidUserDataException
import org.gradle.api.logging.Logging
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Builds the live [BuildCacheService] for a [BazelRemoteBuildCache].
 *
 * Runs once per build, including on a configuration-cache hit -- Gradle
 * re-invokes the factory after reading the CC entry. That makes it the right
 * place to resolve credentials, because a value read here never enters the
 * serialized entry on disk.
 *
 * Throwing from here fails the build, so it is reserved for configuration a
 * human must fix. Everything environmental -- an unreachable host, a cache that
 * is down -- degrades to [NoOpBuildCacheService] instead.
 */
class BazelRemoteBuildCacheServiceFactory : BuildCacheServiceFactory<BazelRemoteBuildCache> {

    private val logger = Logging.getLogger(BazelRemoteBuildCacheServiceFactory::class.java)

    override fun createBuildCacheService(
        configuration: BazelRemoteBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        describer
            .type("bazel")
            .config("endpoint", describeEndpoint(configuration.endpoint))
            .config("keyVersion", configuration.keyVersion)

        // Misconfiguration: fail loudly, with a message that says what to fix.
        val endpoint = try {
            CacheEndpoint(
                requireNotNull(configuration.endpoint) {
                    "bazel-cache: 'endpoint' is required, e.g. endpoint = \"https://cache.example.com\""
                },
            )
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("bazel-cache: ${e.message}", e)
        }

        val spool = resolveSpoolDirectory(configuration)
        sweepStaleSpoolFiles(spool, logger)

        val client = HttpRemoteCacheClient(endpoint, headers = resolveHeaders(configuration))

        // One cheap reachability check. Without it a typo in the endpoint turns
        // into one doomed request per task, which is slower than no cache at all.
        if (!client.probe()) {
            logger.warn(
                "bazel-cache: {} is not reachable; builds will run without the remote cache.",
                describeEndpoint(configuration.endpoint),
            )
            client.close()
            return NoOpBuildCacheService
        }

        return BazelBuildCacheService(
            // Gradle never retries and disables the cache on first failure, so
            // recovery has to happen below its notice.
            client = ResilientRemoteCacheClient(client),
            mapper = CacheKeyMapper(configuration.keyVersion),
            spoolDirectory = spool,
            maxEntrySizeBytes = configuration.maxEntrySizeBytes,
            inMemoryLimitBytes = configuration.inMemoryLimitBytes,
            strict = configuration.strict,
        )
    }

    /**
     * Credentials are read here, from environment variable NAMES held on the
     * configuration object.
     *
     * Reading them at configuration time instead -- via `System.getenv` in the
     * settings script, or `providers.environmentVariable` -- would write the
     * resolved secret into the configuration cache entry under
     * `.gradle/configuration-cache`, a directory CI systems routinely cache and
     * upload, and would invalidate that entry on every token rotation.
     */
    private fun resolveHeaders(configuration: BazelRemoteBuildCache): Map<String, String> {
        val headers = LinkedHashMap<String, String>()

        System.getenv(configuration.tokenEnvironmentVariable)?.takeIf { it.isNotBlank() }?.let {
            headers["Authorization"] = if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it"
        }
        configuration.headersFromEnvironment.forEach { (header, envVar) ->
            System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { headers[header] = it }
        }
        return headers
    }

    private fun resolveSpoolDirectory(configuration: BazelRemoteBuildCache): Path =
        configuration.spoolDirectory?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("java.io.tmpdir"), "gradle-bazel-cache-spool")
}
