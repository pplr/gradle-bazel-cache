plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
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

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
