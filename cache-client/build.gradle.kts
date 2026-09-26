plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
    `java-test-fixtures`
}

description = "Bazel remote cache (REAPI v2) client. No Gradle API dependency."

kotlin {
    compilerOptions {
        // Bytecode level. Enforced for Java by `options.release` below.
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        // See gradle/libs.versions.toml: Gradle supplies the Kotlin stdlib to
        // plugin classloaders, so we must not emit calls it cannot satisfy.
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(libs.versions.kotlinApi.get())
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(libs.versions.kotlinApi.get())
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
}

dependencies {
    // Deliberately compileOnly: Gradle provides the Kotlin stdlib to plugin
    // classloaders, and bundling a second copy is a classpath hazard.
    compileOnly(kotlin("stdlib"))

    // Shaded into the plugin jar; see gradle-plugin/build.gradle.kts.
    api(libs.protobuf.java)

    testFixturesCompileOnly(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Container-backed tests are opt-in so `./gradlew build` works offline.
        excludeTags("integration")
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Verifies the protocol against a real bazel-remote rather than our fake.
 *
 *   podman run -d -p 9090:8080 -v bazel-remote-data:/data \
 *     docker.io/buchgr/bazel-remote-cache:v2.6.2 --dir /data --max_size 1
 *   BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ ./gradlew integrationTest
 */
val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs cache protocol tests against a real bazel-remote server."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    // The server URL is an input, so results must not be cached across servers.
    inputs.property("bazelRemoteUrl", providers.environmentVariable("BAZEL_REMOTE_HTTP_URL").orElse("unset"))
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
