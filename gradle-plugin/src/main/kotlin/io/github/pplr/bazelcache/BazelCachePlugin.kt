package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.client.grpc.GrpcEndpoint
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging

/**
 * Settings plugin that makes [BazelRemoteBuildCache] available to `buildCache { }`.
 *
 * Apply in `settings.gradle.kts`:
 * ```
 * plugins { id("io.github.pplr.bazel-cache") version "..." }
 *
 * buildCache {
 *     remote(BazelRemoteBuildCache::class) {
 *         endpoint = "https://cache.internal:8080"   // or "grpcs://cache.internal:1985"
 *         isPush = System.getenv("CI") != null
 *     }
 * }
 * ```
 */
class BazelCachePlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.buildCache.registerBuildCacheService(
            BazelRemoteBuildCache::class.java,
            BazelRemoteBuildCacheServiceFactory::class.java,
        )

        // Checked here rather than in the service factory: Gradle invokes the
        // factory twice when it stores a configuration-cache entry, which would
        // print the warning twice. This runs once per configuration.
        settings.gradle.settingsEvaluated { evaluated ->
            val remote = evaluated.buildCache.remote as? BazelRemoteBuildCache ?: return@settingsEvaluated
            val http = remote.endpoint?.let { !GrpcEndpoint.isGrpc(it) } ?: false
            if (http && remote.instanceName.isNotBlank()) {
                // Bazel ignores --remote_instance_name for HTTP caches too. We match
                // that, but say so rather than silently dropping a value. Over gRPC
                // it is sent, so there is nothing to warn about.
                Logging.getLogger(BazelCachePlugin::class.java).warn(
                    "bazel-cache: instanceName has no effect over HTTP (as in Bazel). " +
                        "To use a path prefix, put it in endpoint, e.g. https://cache.example.com/team-a/",
                )
            }
        }
    }
}
