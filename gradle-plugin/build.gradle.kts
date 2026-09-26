plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    `java-gradle-plugin`
}

description = "Gradle settings plugin registering a Bazel remote cache as the Gradle build cache backend."

// The Gradle module is :gradle-plugin, but the published coordinates must be
// io.github.pplr:gradle-bazel-cache to match the repository and plugin id.
base {
    archivesName = "gradle-bazel-cache"
}

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
    website = "https://github.com/pplr/gradle-bazel-cache"
    vcsUrl = "https://github.com/pplr/gradle-bazel-cache.git"
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
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

// --- Shading -----------------------------------------------------------------
//
// protobuf-java must not reach a consumer's classloader under its own name.
// Gradle does not export com.google.protobuf to plugins, but every plugin in a
// ClassLoaderScope shares ONE classloader, so a settings plugin competes with
// Develocity, foojay-resolver and the AGP settings plugins for it. protobuf is
// notoriously intolerant of generated-code/runtime version skew.
//
// We relocate com.google.protobuf ONLY.
//
// We deliberately do NOT relocate build.bazel.remote.execution.v2 (our own
// generated stubs). Generated protobuf classes embed their FileDescriptorProto
// as a string literal in which package names are length-prefixed varints.
// Rewriting the package to a longer name changes the byte length without
// updating the prefix, corrupting the descriptor pool at class-init time.
// Relocating com.google.protobuf is a pure Java-package rename and touches no
// descriptor string, because our protos import no well-known types.
val shadedPrefix = "io.github.pplr.bazelcache.shaded"

tasks.shadowJar {
    archiveClassifier = ""
    relocate("com.google.protobuf", "$shadedPrefix.protobuf")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class")
    exclude("**/module-info.class")
    // .proto sources are build-time inputs. protobuf-java ships its own copies
    // of google/protobuf/*.proto, which would collide by resource name with any
    // other plugin bundling protobuf. Descriptors are embedded in the generated
    // classes, so nothing reads these at runtime.
    exclude("**/*.proto")
}

// The plain jar would otherwise collide with the shaded one on the same name.
tasks.jar {
    archiveClassifier = "plain"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    dependsOn(tasks.shadowJar)
    systemProperty("shadedJar", tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("shadedPrefix", shadedPrefix)
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
