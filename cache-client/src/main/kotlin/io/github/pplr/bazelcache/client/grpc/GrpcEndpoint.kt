package io.github.pplr.bazelcache.client.grpc

import java.net.URI

/**
 * A gRPC cache target, spelled as Bazel's `--remote_cache` spells it.
 *
 * Bazel's rules (`GoogleAuthUtils`): `grpc://` is plaintext, `grpcs://` is TLS,
 * and a target with **no scheme at all is gRPC over TLS**. A missing port is
 * the gRPC default for the transport: 443 with TLS, 80 without.
 *
 * `unix://` targets are a Bazel feature this client does not support: the
 * okhttp transport cannot open a Unix domain socket.
 */
class GrpcEndpoint(target: String) {

    val host: String
    val port: Int
    val tls: Boolean

    init {
        require(target.isNotBlank()) { "endpoint must not be blank" }
        val trimmed = target.trim()
        require(!trimmed.startsWith("unix:")) {
            "unix:// endpoints are not supported; use grpc://host:port or grpcs://host:port: $target"
        }
        // As in Bazel: no scheme means gRPC with TLS.
        val withScheme = if ("://" in trimmed) trimmed else "grpcs://$trimmed"
        val parsed = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw IllegalArgumentException("endpoint is not a valid gRPC target: $target", e)
        }
        val scheme = parsed.scheme?.lowercase()
        require(scheme == "grpc" || scheme == "grpcs") {
            "endpoint scheme must be http, https, grpc or grpcs, was '${parsed.scheme}': $target"
        }
        require(!parsed.host.isNullOrBlank()) { "endpoint must include a host: $target" }
        // Bazel passes the target straight to gRPC as host:port, so there is
        // nowhere for a path, a query or credentials to go. Say so rather than
        // silently dropping them.
        require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") {
            "a gRPC endpoint cannot have a path; use instanceName instead: $target"
        }
        require(parsed.rawQuery == null && parsed.rawFragment == null) {
            "a gRPC endpoint cannot have a query or fragment: $target"
        }
        require(parsed.rawUserInfo == null) {
            "a gRPC endpoint cannot carry credentials; use tokenEnvironmentVariable: $target"
        }

        tls = scheme == "grpcs"
        host = parsed.host
        port = if (parsed.port != -1) parsed.port else if (tls) 443 else 80
    }

    override fun toString(): String = "${if (tls) "grpcs" else "grpc"}://$host:$port"

    companion object {
        /**
         * True if [endpoint] names a gRPC cache rather than an HTTP one.
         *
         * Mirrors how Bazel picks a transport for `--remote_cache`: `http://` and
         * `https://` select the HTTP protocol, everything else -- including a
         * bare `host:port` -- is gRPC.
         */
        fun isGrpc(endpoint: String): Boolean {
            val lower = endpoint.trim().lowercase()
            return !(lower.startsWith("http://") || lower.startsWith("https://"))
        }
    }
}
