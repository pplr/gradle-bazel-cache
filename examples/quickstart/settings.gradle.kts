import io.github.pplr.bazelcache.BazelRemoteBuildCache

pluginManagement {
    // Builds the plugin from this repository, so the quickstart needs no
    // published artifact. Real consumers use the Gradle Plugin Portal instead:
    //   plugins { id("io.github.pplr.bazel-cache") version "..." }
    includeBuild("../..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.github.pplr.bazel-cache")
}

rootProject.name = "quickstart"
include(":app")

buildCache {
    // Disabled purely so the demo proves the REMOTE cache is working. A local
    // hit would short-circuit the remote lookup and prove nothing.
    local { isEnabled = false }

    remote(BazelRemoteBuildCache::class) {
        endpoint = providers.gradleProperty("bazelCache.url").getOrElse("http://127.0.0.1:9090/")
        isPush = true
        // Credentials, when a server needs them, are referenced by environment
        // variable NAME so the value never lands in the configuration cache:
        // tokenEnvironmentVariable = "BAZEL_CACHE_TOKEN"
        //
        // The scheme picks the transport, as with Bazel's --remote_cache:
        // http(s):// for the HTTP protocol, grpc(s):// for gRPC (port 9092 here).
        // Over HTTP a path prefix goes in the endpoint; instanceName (like
        // --remote_instance_name) is sent over gRPC and has no effect over HTTP.
    }
}
