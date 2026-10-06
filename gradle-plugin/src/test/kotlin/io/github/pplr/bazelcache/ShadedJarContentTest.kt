package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.client.grpc.FakeGrpcCacheServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * M2 gate: asserts against the PUBLISHED artifact, not the compile classpath.
 *
 * Relocation failures are invisible at compile time and usually surface as a
 * `NoClassDefFoundError` or `Invalid embedded descriptor` in somebody else's
 * build, so every assertion here opens the real shaded jar.
 */
class ShadedJarContentTest {

    companion object {
        private lateinit var jar: File
        private lateinit var entries: List<String>
        private lateinit var shadedPrefix: String

        @JvmStatic
        @BeforeAll
        fun open() {
            jar = File(System.getProperty("shadedJar") ?: error("shadedJar system property not set"))
            shadedPrefix = System.getProperty("shadedPrefix") ?: error("shadedPrefix not set")
            assertThat(jar).exists()
            entries = JarFile(jar).use { j -> j.entries().toList().map { it.name } }
        }
    }

    @Test
    fun `protobuf is not present under its own package`() {
        // If this leaks, we fight every other settings plugin for the protobuf
        // version in a shared ClassLoaderScope.
        assertThat(entries.filter { it.startsWith("com/google/protobuf/") })
            .describedAs("unrelocated protobuf classes in the published jar")
            .isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["io/grpc/", "com/google/common/", "com/google/gson/", "okio/", "io/perfmark/"])
    fun `gRPC and its dependencies are not present under their own packages`(prefix: String) {
        assertThat(entries.filter { it.startsWith(prefix) && it.endsWith(".class") })
            .describedAs("unrelocated $prefix classes in the published jar")
            .isEmpty()
    }

    @Test
    fun `gRPC service files are renamed and point at relocated classes`() {
        val services = entries.filter { it.startsWith("META-INF/services/") && !it.endsWith("/") }
        val shaded = shadedPrefix.replace('.', '/')
        assertThat(services).isNotEmpty().allMatch { it.startsWith("META-INF/services/$shadedPrefix.") }
        JarFile(jar).use { j ->
            services.forEach { name ->
                val impls = j.getInputStream(j.getEntry(name)).bufferedReader().readLines().filter(String::isNotBlank)
                impls.forEach { impl ->
                    assertThat(entries).describedAs("provider named in $name").contains(
                        "$shaded/${impl.removePrefix("$shadedPrefix.").replace('.', '/')}.class",
                    )
                }
            }
        }
    }

    @Test
    fun `no Maven metadata for bundled libraries`() {
        assertThat(entries.filter { it.startsWith("META-INF/maven/") }).isEmpty()
    }

    @Test
    fun `protobuf is present under the shaded package`() {
        val shadedPath = shadedPrefix.replace('.', '/') + "/protobuf/"
        assertThat(entries.filter { it.startsWith(shadedPath) && it.endsWith(".class") })
            .describedAs("relocated protobuf runtime")
            .isNotEmpty()
    }

    @Test
    fun `our REAPI stubs are deliberately NOT relocated`() {
        // Renaming build.bazel.remote.execution.v2 would rewrite the package name
        // inside each generated class's embedded FileDescriptorProto, where it is
        // length-prefixed -- corrupting the descriptor pool at class init.
        assertThat(entries).contains("build/bazel/remote/execution/v2/ActionResult.class")
        assertThat(entries.none { it.startsWith(shadedPrefix.replace('.', '/') + "/bazel/") }).isTrue()
        // Same for the ByteStream stubs, which share com.google with relocated libraries.
        assertThat(entries).contains("com/google/bytestream/ByteStreamProto\$ReadRequest.class")
    }

    @Test
    fun `plugin descriptor and plugin classes are present`() {
        assertThat(entries).contains(
            "META-INF/gradle-plugins/io.github.pplr.bazel-cache.properties",
            "io/github/pplr/bazelcache/BazelCachePlugin.class",
        )
    }

    @Test
    fun `no module-info or stale signatures`() {
        assertThat(entries.filter { it.endsWith("module-info.class") }).isEmpty()
        assertThat(entries.filter { it.matches(Regex("META-INF/.*\\.(SF|DSA|RSA)")) }).isEmpty()
    }

    @Test
    fun `no proto sources are shipped`() {
        // protobuf-java bundles google/protobuf/*.proto; those would collide by
        // resource name with any other plugin shipping protobuf, and nothing
        // reads them at runtime.
        assertThat(entries.filter { it.endsWith(".proto") })
            .describedAs("proto sources leaked into the published jar")
            .isEmpty()
    }

    @Test
    fun `no kotlin stdlib is bundled`() {
        // Gradle supplies the stdlib to plugin classloaders; a second copy is a
        // classpath hazard and would defeat compileOnly(kotlin("stdlib")).
        assertThat(entries.filter { it.startsWith("kotlin/") && it.endsWith(".class") })
            .describedAs("bundled Kotlin stdlib")
            .isEmpty()
    }

    /**
     * The assertion that actually catches descriptor corruption: load the
     * generated class out of the shaded jar, in a classloader that can see
     * nothing else, and force descriptor initialisation.
     */
    @Test
    fun `descriptors initialise from the shaded jar and keep upstream proto names`() {
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { cl ->
            val actionResult = cl.loadClass("build.bazel.remote.execution.v2.ActionResult")

            // getDescriptor() triggers the embedded FileDescriptorProto parse.
            val descriptor = actionResult.getMethod("getDescriptor").invoke(null)
            val fullName = descriptor.javaClass.getMethod("getFullName").invoke(descriptor) as String

            assertThat(fullName)
                .describedAs("proto full name must survive Java-package relocation")
                .isEqualTo("build.bazel.remote.execution.v2.ActionResult")

            // And the protobuf runtime backing it really is the relocated one.
            assertThat(descriptor.javaClass.name).startsWith("$shadedPrefix.protobuf")
        }
    }

    @Test
    fun `ByteStream descriptors initialise from the shaded jar`() {
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { cl ->
            val readRequest = cl.loadClass("com.google.bytestream.ByteStreamProto\$ReadRequest")
            val descriptor = readRequest.getMethod("getDescriptor").invoke(null)
            val fullName = descriptor.javaClass.getMethod("getFullName").invoke(descriptor) as String
            assertThat(fullName).isEqualTo("google.bytestream.ReadRequest")
        }
    }

    /**
     * The gRPC stack out of the shaded jar, against a real server: channel
     * construction, the relocated ServiceLoader providers, the hand-written
     * method descriptors and ByteStream in both directions. Relocation faults
     * here are runtime-only -- a missing provider is "no name resolver found"
     * in somebody else's build -- so nothing short of a round trip proves it.
     */
    @Test
    fun `a gRPC round trip works entirely inside the shaded jar`() {
        val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        FakeGrpcCacheServer().use { server ->
            URLClassLoader(
                arrayOf(jar.toURI().toURL(), stdlib.toURI().toURL()),
                ClassLoader.getPlatformClassLoader(),
            ).use { cl ->
                val endpoint = cl.loadClass("io.github.pplr.bazelcache.client.grpc.GrpcEndpoint")
                    .getConstructor(String::class.java).newInstance(server.target)
                val clientClass = cl.loadClass("io.github.pplr.bazelcache.client.grpc.GrpcRemoteCacheClient")
                val client = clientClass.constructors
                    .single { it.parameterCount == 4 && it.parameterTypes[0] == endpoint.javaClass }
                    .newInstance(endpoint, "", emptyMap<String, String>(), java.time.Duration.ofSeconds(10))
                try {
                    assertThat(clientClass.getMethod("probe").invoke(client)).isEqualTo(true)

                    val payload = "shaded round trip".toByteArray()
                    val digests = cl.loadClass("io.github.pplr.bazelcache.client.Digests")
                    val digest = digests.getMethod("digestOf", ByteArray::class.java)
                        .invoke(digests.getField("INSTANCE").get(null), payload)
                    // writeBlob takes a Kotlin () -> InputStream from the isolated
                    // loader's stdlib, so the lambda has to be built in that loader.
                    val function0 = cl.loadClass("kotlin.jvm.functions.Function0")
                    val source = Proxy.newProxyInstance(cl, arrayOf(function0)) { _, method, _ ->
                        if (method.name == "invoke") payload.inputStream() else null
                    }
                    clientClass.getMethod("writeBlob", digest.javaClass, function0).invoke(client, digest, source)

                    val sink = ByteArrayOutputStream()
                    val read = clientClass.getMethod("readBlob", digest.javaClass, OutputStream::class.java)
                        .invoke(client, digest, sink)
                    assertThat(read).isEqualTo(true)
                    assertThat(sink.toByteArray()).isEqualTo(payload)
                } finally {
                    clientClass.getMethod("close").invoke(client)
                }
            }
        }
    }

    /**
     * Exercises real behaviour out of the shaded jar, with only the Kotlin
     * stdlib alongside it -- which is exactly what Gradle provides to a plugin
     * classloader, and why we must not bundle our own copy.
     */
    @Test
    fun `a round trip works entirely inside the shaded jar`() {
        val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        URLClassLoader(
            arrayOf(jar.toURI().toURL(), stdlib.toURI().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { cl ->
            val mapperClass = cl.loadClass("io.github.pplr.bazelcache.client.CacheKeyMapper")
            val mapper = mapperClass.getConstructor(String::class.java).newInstance("v1")
            val digest = mapperClass.getMethod("actionKeyFor", String::class.java)
                .invoke(mapper, "3748f79fa4230cfba17f559fee3220fb")
            val hash = digest.javaClass.getMethod("getHash").invoke(digest) as String

            // Same golden vector as CacheKeyMapperTest: relocation must not
            // change a single byte of the keyspace.
            assertThat(hash)
                .isEqualTo("0070a3f346b3b5a13649aa4b175f794d5ee823d767906b5404d2d3fa2252e9cf")
        }
    }
}
