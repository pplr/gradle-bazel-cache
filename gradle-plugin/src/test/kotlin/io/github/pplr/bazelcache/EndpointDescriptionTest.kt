package io.github.pplr.bazelcache

import io.github.pplr.bazelcache.internal.describeEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Describer values are written to the build log and published into build scans,
 * so anything credential-shaped must never survive this function.
 */
class EndpointDescriptionTest {

    @Test
    fun `keeps scheme host port and path`() {
        assertThat(describeEndpoint("https://cache.internal:8080/team-a"))
            .isEqualTo("https://cache.internal:8080/team-a")
    }

    @Test
    fun `redacts url userinfo`() {
        assertThat(describeEndpoint("https://alice:hunter2@cache.internal:8080"))
            .isEqualTo("https://<redacted>@cache.internal:8080")
            .doesNotContain("hunter2")
    }

    @Test
    fun `redacts query string`() {
        assertThat(describeEndpoint("https://cache.internal/cas?token=s3cret"))
            .isEqualTo("https://cache.internal/cas?<redacted>")
            .doesNotContain("s3cret")
    }

    @Test
    fun `handles unset and malformed endpoints without throwing`() {
        assertThat(describeEndpoint(null)).isEqualTo("(not set)")
        assertThat(describeEndpoint("")).isEqualTo("(not set)")
        assertThat(describeEndpoint("http://a b c/:::")).isEqualTo("(unparseable)")
    }
}
