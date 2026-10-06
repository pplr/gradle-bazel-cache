package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.client.grpc.FakeGrpcCacheServer
import io.github.pplr.bazelcache.client.http.FakeBazelCacheServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.UUID

/**
 * Proves the plugin actually loads and caches on every Gradle version we claim
 * to support.
 *
 * This is the only real enforcement of that claim. We compile against the
 * wrapper's Gradle API, and the Kotlin stdlib on the compile classpath is the
 * one Gradle 9.8 ships -- so nothing at compile time stops us calling an API
 * that an older Gradle cannot satisfy. A `NoSuchMethodError` on Gradle 8.0
 * would surface here and nowhere else.
 *
 * Both transports run on every version: the gRPC stack carries okio, which is
 * Kotlin, and must run against the oldest stdlib Gradle hands us.
 *
 * Tagged: each version downloads a full distribution and starts its own daemon,
 * so this is kept out of `./gradlew build`.
 *
 *     ./gradlew :gradle-plugin:crossVersionTest
 */
@Tag("crossversion")
class CrossVersionFunctionalTest {

    @TempDir lateinit var projectDir: File

    @ParameterizedTest(name = "Gradle {0} over {1}")
    @MethodSource("gradleVersions")
    fun `plugin loads and serves a cache hit`(gradleVersion: String, transport: String) {
        val salt = UUID.randomUUID().toString()
        writeProject(salt, if (transport == "grpc") grpcServer.target else server.baseUrl)

        val first = run(gradleVersion)
        assertThat(first.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)

        File(projectDir, "build").deleteRecursively()
        File(projectDir, ".gradle/executionHistory").deleteRecursively()
        File(projectDir, ".gradle/buildOutputCleanup").deleteRecursively()

        val second = run(gradleVersion)
        assertThat(second.task(":cacheMe")!!.outcome)
            .describedAs("Gradle %s should serve from the remote cache", gradleVersion)
            .isEqualTo(TaskOutcome.FROM_CACHE)
    }

    private fun run(gradleVersion: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withGradleVersion(gradleVersion)
        .withArguments("cacheMe", "--build-cache")
        .forwardOutput()
        .build()

    private fun writeProject(salt: String, endpoint: String) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            import io.github.pplr.bazelcache.BazelRemoteBuildCache

            plugins { id("io.github.pplr.bazel-cache") }

            rootProject.name = "xver"

            buildCache {
                local { isEnabled = false }
                remote(BazelRemoteBuildCache::class) {
                    endpoint = "$endpoint"
                    isPush = true
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            @CacheableTask
            abstract class Produce : DefaultTask() {
                @get:Input abstract val salt: Property<String>
                @get:OutputFile abstract val out: RegularFileProperty

                @TaskAction fun run() { out.get().asFile.writeText(salt.get()) }
            }

            tasks.register<Produce>("cacheMe") {
                salt.set("$salt")
                out.set(layout.buildDirectory.file("out.txt"))
            }
            """.trimIndent(),
        )
    }

    companion object {
        private val server = FakeBazelCacheServer()
        private val grpcServer = FakeGrpcCacheServer()

        /** Overridable so CI can shard the matrix across jobs. */
        @JvmStatic
        fun gradleVersions(): List<Arguments> =
            (System.getProperty("gradleVersions") ?: "8.0,8.14.5,9.8.0").split(",")
                .flatMap { version -> listOf("http", "grpc").map { Arguments.of(version, it) } }

        @JvmStatic @AfterAll fun stop() {
            server.close()
            grpcServer.close()
        }
    }
}
