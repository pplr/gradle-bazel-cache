package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.internal.NoOpBuildCacheService
import io.github.pplr.bazelcache.internal.describeEndpoint
import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory

/**
 * Builds the live [BuildCacheService] for a [BazelRemoteBuildCache] configuration.
 *
 * This runs once per build — including on a configuration-cache hit, because
 * Gradle re-invokes the factory after reading the CC entry. That makes it the
 * correct place to resolve credentials (so secrets never enter the CC entry) and
 * to probe the server.
 *
 * Throwing from here fails the build, so it is reserved for genuine
 * misconfiguration. An unreachable server yields [NoOpBuildCacheService] instead.
 */
class BazelRemoteBuildCacheServiceFactory : BuildCacheServiceFactory<BazelRemoteBuildCache> {

    override fun createBuildCacheService(
        configuration: BazelRemoteBuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        describer
            .type("bazel")
            .config("endpoint", describeEndpoint(configuration.endpoint))
            .config("keyVersion", configuration.keyVersion)

        // Real client wiring lands in M3/M4.
        return NoOpBuildCacheService
    }
}
