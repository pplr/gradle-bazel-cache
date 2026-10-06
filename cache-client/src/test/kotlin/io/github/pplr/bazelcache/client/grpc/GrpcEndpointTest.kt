package io.github.pplr.bazelcache.client.grpc

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class GrpcEndpointTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        // target,                     host,           port, tls
        "grpc://cache:9092,            cache,          9092, false",
        "grpcs://cache:1985,           cache,          1985, true",
        "grpcs://cache:1985/,          cache,          1985, true",
        // Bazel: no scheme means gRPC with TLS.
        "cache.example.com:443,        cache.example.com, 443, true",
        // gRPC's default ports when none is given.
        "grpcs://cache,                cache,          443,  true",
        "grpc://cache,                 cache,          80,   false",
        "GRPC://cache:9092,            cache,          9092, false",
    )
    fun `targets are read as Bazel reads --remote_cache`(target: String, host: String, port: Int, tls: Boolean) {
        val e = GrpcEndpoint(target)
        assertThat(e.host).isEqualTo(host)
        assertThat(e.port).isEqualTo(port)
        assertThat(e.tls).isEqualTo(tls)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "grpc://cache:9092/team-a",   // instance names go in instanceName
            "grpc://cache:9092?token=x",
            "grpc://user:pw@cache:9092",
            "unix:///var/run/cache.sock",
            "ftp://cache:21",
            "grpc://",
            " ",
        ],
    )
    fun `targets gRPC cannot honour are rejected with a reason`(target: String) {
        assertThatThrownBy { GrpcEndpoint(target) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @CsvSource(
        "http://cache:8080,  false",
        "HTTPS://cache,      false",
        "grpc://cache:9092,  true",
        "grpcs://cache:1985, true",
        "cache:9092,         true",
    )
    fun `the scheme picks the transport, as in Bazel`(endpoint: String, grpc: Boolean) {
        assertThat(GrpcEndpoint.isGrpc(endpoint)).isEqualTo(grpc)
    }
}
