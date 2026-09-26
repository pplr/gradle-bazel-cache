package io.github.pplr.bazelcache

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * Settings plugin that makes [BazelRemoteBuildCache] available to `buildCache { }`.
 *
 * Apply in `settings.gradle.kts`:
 * ```
 * plugins { id("io.github.pplr.bazel-cache") version "..." }
 *
 * buildCache {
 *     remote(BazelRemoteBuildCache::class) {
 *         endpoint = "https://cache.internal:8080"
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
    }
}
