package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.client.http.FakeBazelCacheServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * End-to-end through a real Gradle build.
 *
 * Isolation matters more than it looks here:
 *  - the LOCAL cache is disabled, because a local hit short-circuits the remote
 *    load entirely and a "cache hit" test would pass with a broken client;
 *  - each test seeds its task with a unique input, so every test occupies its
 *    own keyspace and a shared server needs no cleanup;
 *  - `build/` and `.gradle/` are both deleted between runs, since leaving task
 *    history in place means the second build is UP-TO-DATE and never consults
 *    the cache at all. (`--rerun-tasks` does not work: it disables cache loading.)
 */
class BazelCacheFunctionalTest {

    @TempDir lateinit var projectDir: File

    private lateinit var salt: String

    @BeforeEach
    fun setUp() {
        server.reset()
        salt = UUID.randomUUID().toString()
    }

    @Test
    fun `second build is served from the remote cache`() {
        writeProject()

        val first = build()
        assertThat(first.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.putCount.get()).describedAs("first build should store").isGreaterThan(0)

        clearBuildState()

        val second = build()
        assertThat(second.task(":cacheMe")!!.outcome)
            .describedAs("second build should come from the remote cache")
            .isEqualTo(TaskOutcome.FROM_CACHE)
        assertThat(outputFile().readText()).isEqualTo("produced-$salt")
    }

    @Test
    fun `cache entry survives relocation to a different directory`() {
        // Proves we are not keying on anything path-dependent.
        writeProject()
        build()

        val relocated = File(projectDir.parentFile, "relocated-${UUID.randomUUID()}")
        projectDir.copyRecursively(relocated)
        File(relocated, "build").deleteRecursively()
        File(relocated, ".gradle").deleteRecursively()

        val result = GradleRunner.create()
            .withProjectDir(relocated)
            .withPluginClasspath()
            .withArguments("cacheMe", "--build-cache", "--configuration-cache")
            .build()
        assertThat(result.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    }

    @Test
    fun `configuration cache is reused and the cache still works`() {
        writeProject()
        build()
        clearBuildState()

        val second = build()
        assertThat(second.output).contains("Configuration cache entry reused")
        assertThat(second.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
    }

    @Test
    fun `the auth token never reaches the configuration cache entry`() {
        // The config object holds the environment variable NAME; the value is
        // resolved in the service factory, which runs after the CC entry is
        // read. CI systems routinely cache and upload .gradle/, so a leak here
        // would publish the token.
        writeProject()
        val secret = "super-secret-token-${UUID.randomUUID()}"

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withEnvironment(System.getenv() + mapOf("BAZEL_CACHE_TOKEN" to secret))
            .withArguments("cacheMe", "--build-cache", "--configuration-cache")
            .forwardOutput()
            .build()

        val ccDir = File(projectDir, ".gradle/configuration-cache")
        assertThat(ccDir).exists()
        val offenders = ccDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.readBytes().toString(Charsets.ISO_8859_1).contains(secret) }
            .toList()
        assertThat(offenders)
            .describedAs("token found in configuration cache entry")
            .isEmpty()
    }

    @Test
    fun `an unreachable cache degrades instead of failing the build`() {
        // The whole point of probing in the factory: a typo in the endpoint must
        // not cost one doomed request per task, nor fail the build.
        writeProject(endpoint = "http://127.0.0.1:1/")

        val result = build()
        assertThat(result.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains("is not reachable")
    }

    @Test
    fun `a failing cache server does not fail the build`() {
        writeProject()
        server.forceStatus = 503

        val result = build()
        assertThat(result.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `instanceName has no effect over HTTP, as in Bazel`() {
        // Bazel's HTTP cache client never uses --remote_instance_name. We match
        // that exactly, but warn instead of dropping a configured value silently.
        writeProject(instanceName = "team-a")

        val result = build()
        assertThat(result.task(":cacheMe")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).containsOnlyOnce("instanceName has no effect over HTTP")
        assertThat(server.requestPaths)
            .describedAs("no request may carry the instance name as a path prefix")
            .isNotEmpty()
            .noneMatch { it.contains("team-a") }
    }

    @Test
    fun `push disabled means nothing is stored`() {
        writeProject(push = false)
        build()
        assertThat(server.putCount.get()).isZero()
    }

    // --- fixture ------------------------------------------------------------

    private fun build() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments("cacheMe", "--build-cache", "--configuration-cache")
        .forwardOutput()
        .build()

    private fun clearBuildState() {
        File(projectDir, "build").deleteRecursively()
        // Task history lives here; without clearing it the task is UP-TO-DATE
        // and the build cache is never consulted.
        File(projectDir, ".gradle/executionHistory").deleteRecursively()
        File(projectDir, ".gradle/buildOutputCleanup").deleteRecursively()
    }

    private fun outputFile() = File(projectDir, "build/out.txt")

    private fun writeProject(endpoint: String = server.baseUrl, push: Boolean = true, instanceName: String = "") {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            import io.github.pplr.bazelcache.BazelRemoteBuildCache

            plugins { id("io.github.pplr.bazel-cache") }

            rootProject.name = "fixture"

            buildCache {
                // A local hit would short-circuit the remote load and let this
                // suite pass with a broken client.
                local { isEnabled = false }
                remote(BazelRemoteBuildCache::class) {
                    endpoint = "$endpoint"
                    isPush = $push
                    instanceName = "$instanceName"
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

                @TaskAction fun run() {
                    out.get().asFile.writeText("produced-" + salt.get())
                }
            }

            tasks.register<Produce>("cacheMe") {
                // Unique per test: gives each test its own keyspace so a shared
                // server needs no cleanup and tests can run in parallel.
                salt.set("$salt")
                out.set(layout.buildDirectory.file("out.txt"))
            }
            """.trimIndent(),
        )
    }

    companion object {
        private val server = FakeBazelCacheServer()

        @JvmStatic @AfterAll fun stop() = server.close()
    }
}
