package io.github.pplr.bazelcache

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * M0 gate: the plugin marker resource exists and points at a loadable class.
 *
 * This is what `plugins { id("io.github.pplr.bazel-cache") }` actually resolves,
 * so a typo in `implementationClass` is a publish-breaking bug that no other
 * test would catch.
 */
class PluginDescriptorTest {

    private val pluginId = "io.github.pplr.bazel-cache"

    @Test
    fun `plugin descriptor resource is generated`() {
        val descriptor = javaClass.classLoader
            .getResourceAsStream("META-INF/gradle-plugins/$pluginId.properties")

        assertThat(descriptor)
            .describedAs("META-INF/gradle-plugins/%s.properties", pluginId)
            .isNotNull()

        val props = java.util.Properties().apply { descriptor!!.use { load(it) } }
        assertThat(props.getProperty("implementation-class"))
            .isEqualTo("io.github.pplr.bazelcache.BazelCachePlugin")
    }

    @Test
    fun `implementation class is loadable and is a Plugin`() {
        val type = Class.forName("io.github.pplr.bazelcache.BazelCachePlugin")
        assertThat(org.gradle.api.Plugin::class.java).isAssignableFrom(type)
    }
}
