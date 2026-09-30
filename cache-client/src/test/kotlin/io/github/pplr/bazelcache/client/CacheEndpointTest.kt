package io.github.pplr.bazelcache.client

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class CacheEndpointTest {

    private val hash = "a".repeat(64)

    @ParameterizedTest(name = "[{index}] endpoint={0}")
    @CsvSource(
        // endpoint,                  expected ac prefix
        "http://h:8080,               http://h:8080/ac/",
        "http://h:8080/,              http://h:8080/ac/",
        "http://h:8080/cache,         http://h:8080/cache/ac/",
        "http://h:8080/cache/,        http://h:8080/cache/ac/",
        "http://h:8080/org/team-a/,   http://h:8080/org/team-a/ac/",
    )
    fun `the endpoint path is the only prefix, as in Bazel`(endpoint: String, expectedPrefix: String) {
        // Bazel's HTTP client builds <--remote_cache path>/ac|cas/<hash> and never
        // uses --remote_instance_name. A doubled slash would be a 400 from
        // bazel-remote rather than a 404, so it would not look like a cache miss.
        val e = CacheEndpoint(endpoint)
        assertThat(e.actionCache(hash).toString()).isEqualTo(expectedPrefix + hash)
        assertThat(e.contentAddressableStorage(hash).toString())
            .isEqualTo(expectedPrefix.replace("/ac/", "/cas/") + hash)
    }

    @Test
    fun `probe targets the empty blob`() {
        // Every REAPI server reports the empty blob as present, so this is safe
        // to GET against a cache we know nothing about.
        assertThat(CacheEndpoint("http://h:8080").probe().toString())
            .isEqualTo("http://h:8080/cas/${Digests.EMPTY_SHA256_HEX}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "not-a-url", "ftp://h/x", "/relative/only", "http:///nohost"])
    fun `rejects unusable endpoints at construction`(bad: String) {
        // Fail loudly here: this runs in the service factory, where an exception
        // correctly fails the build with a clear message. Failing later would
        // instead be swallowed as a cache miss.
        assertThatThrownBy { CacheEndpoint(bad) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @MethodSource("badHashes")
    fun `rejects keys the server would reject`(badHash: String) {
        val endpoint = CacheEndpoint("http://h:8080")
        assertThatThrownBy { endpoint.actionCache(badHash) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("64 lowercase hex")
    }

    @Test
    fun `preserves https and default ports`() {
        assertThat(CacheEndpoint("https://cache.internal").actionCache(hash).toString())
            .isEqualTo("https://cache.internal/ac/$hash")
    }

    companion object {
        @JvmStatic
        fun badHashes(): List<String> = listOf(
            "",
            "abc",
            "A".repeat(64),   // uppercase: servers match [a-f0-9]
            "g".repeat(64),   // non-hex
            "a".repeat(63),   // too short
            "a".repeat(65),   // too long
        )
    }
}
