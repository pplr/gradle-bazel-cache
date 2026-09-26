plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

description = "Gradle settings plugin registering a Bazel remote cache as the Gradle build cache backend."

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

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    website = "https://github.com/pplr/gradle-bazel-adapter"
    vcsUrl = "https://github.com/pplr/gradle-bazel-adapter.git"
    plugins {
        create("bazelCache") {
            id = "io.github.pplr.bazel-cache"
            implementationClass = "io.github.pplr.bazelcache.BazelCachePlugin"
            displayName = "Bazel remote cache for Gradle"
            description = "Use a standard Bazel remote cache server (REAPI v2) as the Gradle build cache backend."
            tags = listOf("build-cache", "bazel", "remote-cache", "reapi", "caching")
        }
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation(project(":cache-client"))

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
