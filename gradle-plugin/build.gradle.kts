plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.plugin.publish)
    `java-gradle-plugin`
    signing
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

// --- Maven Central metadata --------------------------------------------------
//
// The Plugin Portal is the primary distribution channel; Central is the mirror
// so the artifact resolves for consumers who do not use the portal. Central
// rejects a POM missing any of name/description/url/licenses/developers/scm.
publishing {
    publications.withType<MavenPublication>().configureEach {
        // The Gradle module is :gradle-plugin, but the published artifact must
        // be gradle-bazel-cache. archivesName only renames the jar file; the
        // Maven coordinates come from the project name, so they are set here.
        // The marker publication keeps its own generated id.
        if (name == "pluginMaven") {
            artifactId = "gradle-bazel-cache"
        }
        pom {
            name = "gradle-bazel-cache"
            description = "Use a standard Bazel remote cache server (REAPI v2) as the Gradle build cache backend."
            url = "https://github.com/pplr/gradle-bazel-cache"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://github.com/pplr/gradle-bazel-cache/blob/main/LICENSE"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "pplr"
                    name = "Pierre Paysant-Le Roux"
                    url = "https://github.com/pplr"
                }
            }
            scm {
                url = "https://github.com/pplr/gradle-bazel-cache"
                connection = "scm:git:https://github.com/pplr/gradle-bazel-cache.git"
                developerConnection = "scm:git:ssh://git@github.com/pplr/gradle-bazel-cache.git"
            }
        }
    }

    repositories {
        // A local staging repo. The release workflow zips this into a Central
        // Portal bundle, so no third-party publishing plugin is needed in the
        // build -- one fewer dependency on the path to a release.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// Central requires a detached .asc for every artifact. Keys come from the
// environment in CI and are never written to disk.
//
// Signing is gated on an explicit -Psigning.enabled rather than on the presence
// of SIGNING_KEY. Reading the key to decide would make it a configuration cache
// input, and its fingerprint would be recorded in the entry on disk -- the same
// hazard this plugin deliberately avoids for users' cache tokens.
val signingEnabled: Boolean =
    providers.gradleProperty("signing.enabled").map(String::toBoolean).orElse(false).get()

signing {
    if (signingEnabled) {
        useInMemoryPgpKeys(
            providers.environmentVariable("SIGNING_KEY").get(),
            providers.environmentVariable("SIGNING_PASSWORD").orNull,
        )
        sign(publishing.publications)
    }
}

// plugin-publish registers signing tasks for its own publications as soon as the
// signing plugin is applied, and without a key they fail rather than no-op.
// `enabled` is set at configuration time; an onlyIf spec would be serialized
// into the configuration cache and capture the enclosing script object.
tasks.withType<Sign>().configureEach {
    enabled = signingEnabled
}

// The staging repo is a plain directory, so publishing into it twice leaves
// artifacts from both runs behind -- and a release would upload the stale
// version alongside the new one.
val cleanStagingRepo by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("staging-repo"))
}

tasks.named("publishAllPublicationsToStagingRepository") {
    dependsOn(cleanStagingRepo)
}

/** Bundles the staging repo for upload to the Sonatype Central Portal. */
val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Packages the staging repository as a Central Portal upload bundle."
    dependsOn(tasks.named("publishAllPublicationsToStagingRepository"))
    from(layout.buildDirectory.dir("staging-repo"))
    archiveFileName = "central-bundle.zip"
    destinationDirectory = layout.buildDirectory.dir("central")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation(project(":cache-client"))

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testImplementation(gradleTestKit())
    testImplementation(testFixtures(project(":cache-client")))
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
    useJUnitPlatform { excludeTags("crossversion") }
    dependsOn(tasks.shadowJar)
    systemProperty("shadedJar", tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("shadedPrefix", shadedPrefix)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Runs the functional suite against every Gradle version we claim to support.
 *
 * This is the only enforcement of the compatibility claim: we compile against
 * the wrapper's Gradle API, so nothing at compile time prevents using an API an
 * older Gradle lacks.
 */
val crossVersionTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the plugin against each supported Gradle version."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("crossversion") }
    systemProperty("gradleVersions", providers.gradleProperty("gradleVersions").getOrElse("8.0,8.14.5,9.8.0"))
    // Each version starts its own daemon; running many in one JVM exhausts memory.
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
