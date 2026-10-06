package io.github.pplr.bazelcache

import build.bazel.remote.execution.v2.DigestFunction
import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.RemoteCacheClient
import io.github.pplr.bazelcache.client.grpc.GrpcEndpoint
import io.github.pplr.bazelcache.client.grpc.GrpcRemoteCacheClient
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
        val grpc = configuration.endpoint?.let(GrpcEndpoint::isGrpc) ?: false
        describer
            .type("bazel")
            .config("endpoint", describeEndpoint(configuration.endpoint))
            .config("transport", if (grpc) "grpc" else "http")
            .config("keyVersion", configuration.keyVersion)
        if (grpc && configuration.instanceName.isNotEmpty()) {
            describer.config("instanceName", configuration.instanceName)
        }

        // Misconfiguration: fail loudly, with a message that says what to fix.
        val client: RemoteCacheClient = try {
            val endpoint = requireNotNull(configuration.endpoint) {
                "'endpoint' is required, e.g. endpoint = \"https://cache.example.com\""
            }
            if (grpc) {
                GrpcRemoteCacheClient(
                    GrpcEndpoint(endpoint),
                    instanceName = configuration.instanceName,
                    headers = resolveHeaders(configuration),
                )
            } else {
                HttpRemoteCacheClient(CacheEndpoint(endpoint), headers = resolveHeaders(configuration))
            }
        } catch (e: IllegalArgumentException) {
            throw InvalidUserDataException("bazel-cache: ${e.message}", e)
        }

        val spool = resolveSpoolDirectory(configuration)
        sweepStaleSpoolFiles(spool, logger)

        // One cheap reachability check. Without it a typo in the endpoint turns
        // into one doomed request per task, which is slower than no cache at all.
        val usable = if (client is GrpcRemoteCacheClient) {
            checkCapabilities(client, configuration)
        } else {
            client.probe().also { reachable -> if (!reachable) warnUnreachable(configuration) }
        }
        if (!usable) {
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
     * The gRPC startup check, as Bazel runs it: `GetCapabilities` doubles as the
     * reachability probe, then the server must accept SHA-256 -- the only digest
     * function our keyspace and the HTTP protocol use.
     *
     * Bazel fails the build on an unsupported digest function. Here it is a
     * server-side fact, not something the build script can fix, so it degrades
     * like an unreachable server instead. A server that refuses action results
     * only earns Bazel's warning: Bazel keeps trying, and so do we.
     */
    private fun checkCapabilities(client: GrpcRemoteCacheClient, configuration: BazelRemoteBuildCache): Boolean {
        val capabilities = try {
            client.getCapabilities(GrpcRemoteCacheClient.PROBE_TIMEOUT)
        } catch (_: CacheIoException) {
            warnUnreachable(configuration)
            return false
        } ?: return true // answered, but without capabilities: e.g. a credential problem, reported later

        val cache = capabilities.cacheCapabilities
        if (DigestFunction.Value.SHA256 !in cache.digestFunctionsList) {
            logger.warn(
                "bazel-cache: {} does not support SHA256 (supported: {}); " +
                    "builds will run without the remote cache.",
                describeEndpoint(configuration.endpoint),
                cache.digestFunctionsList,
            )
            return false
        }
        if (configuration.isPush && !cache.actionCacheUpdateCapabilities.updateEnabled) {
            // Bazel's wording for --remote_upload_local_results.
            logger.warn(
                "bazel-cache: push is enabled, but the remote cache does not support uploading action " +
                    "results or the current account is not authorized to write local results to the remote cache.",
            )
        }
        return true
    }

    private fun warnUnreachable(configuration: BazelRemoteBuildCache) {
        logger.warn(
            "bazel-cache: {} is not reachable; builds will run without the remote cache.",
            describeEndpoint(configuration.endpoint),
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
