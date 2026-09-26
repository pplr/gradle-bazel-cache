package io.github.pplr.bazelcache.client.http

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.Digests
import io.github.pplr.bazelcache.client.RemoteCacheClient
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Bazel HTTP cache protocol over the JDK's own client -- no third-party
 * dependency, which matters for a plugin that shares a classloader with every
 * other settings plugin in a build.
 *
 * Status handling follows Bazel's `HttpCacheClient`: 200/201/202/204 are a
 * successful store (the odd ones exist for nginx's WebDAV module), and 404/204
 * on a read is a miss.
 *
 * Thread-safe: `HttpClient` is, and this class holds no per-request state.
 */
class HttpRemoteCacheClient(
    private val endpoint: CacheEndpoint,
    private val headers: Map<String, String> = emptyMap(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    connectTimeout: Duration = Duration.ofSeconds(10),
    httpClient: HttpClient? = null,
) : RemoteCacheClient {

    private val client: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val ownsClient = httpClient == null

    override fun getActionResult(actionKey: Digest): ActionResult? {
        val uri = endpoint.actionCache(actionKey.hash)
        val response = send(get(uri, requestTimeout), HttpResponse.BodyHandlers.ofByteArray())
        if (isMiss(response.statusCode())) return null
        if (!isSuccess(response.statusCode())) throw failure("GET", uri, response.statusCode())

        return try {
            ActionResult.parseFrom(response.body())
        } catch (_: Exception) {
            // A shared cache may hold something else under a colliding key, or
            // an entry from a future version of this plugin. Either way: a miss,
            // not a build-breaking error.
            null
        }
    }

    override fun updateActionResult(actionKey: Digest, result: ActionResult) {
        val uri = endpoint.actionCache(actionKey.hash)
        val body = result.toByteArray()
        val response = send(
            put(uri, HttpRequest.BodyPublishers.ofByteArray(body), PROTOBUF_CONTENT_TYPE),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (!isSuccess(response.statusCode())) throw failure("PUT", uri, response.statusCode())
    }

    override fun readBlob(digest: Digest, sink: OutputStream): Boolean {
        val uri = endpoint.contentAddressableStorage(digest.hash)
        val response = send(get(uri, requestTimeout), HttpResponse.BodyHandlers.ofInputStream())
        if (isMiss(response.statusCode())) return false
        if (!isSuccess(response.statusCode())) throw failure("GET", uri, response.statusCode())

        // Verify while streaming. A truncated or corrupt body would otherwise
        // reach Gradle's unpacker and throw mid-extraction, which Gradle counts
        // as a cache failure and responds to by disabling the cache for the rest
        // of the build. Treating it as a miss costs one task's worth of work.
        val sha = Digests.newSha256()
        var total = 0L
        response.body().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
                sink.write(buffer, 0, read)
                total += read
            }
        }

        if (total != digest.sizeBytes) return false
        return Digests.toHex(sha.digest()) == digest.hash
    }

    override fun writeBlob(digest: Digest, source: () -> InputStream) {
        val uri = endpoint.contentAddressableStorage(digest.hash)
        val response = send(
            put(uri, HttpRequest.BodyPublishers.ofInputStream(source), OCTET_STREAM_CONTENT_TYPE),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (!isSuccess(response.statusCode())) throw failure("PUT", uri, response.statusCode())
    }

    override fun probe(): Boolean {
        val uri = endpoint.probe()
        return try {
            val code = send(get(uri, PROBE_TIMEOUT), HttpResponse.BodyHandlers.discarding()).statusCode()
            // Reachability, not correctness: 401/403 prove a server answered, and
            // a wrong credential is reported later with a far clearer message
            // than "unreachable" would give.
            isSuccess(code) || isMiss(code) || code == 401 || code == 403
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        // HttpClient only became Closeable in Java 21; on 17 the selector thread
        // is reclaimed when the client is collected. Call it reflectively so a
        // single build works on both.
        if (!ownsClient) return
        runCatching { HttpClient::class.java.getMethod("close").invoke(client) }
    }

    // --- plumbing -----------------------------------------------------------

    private fun get(uri: URI, timeout: Duration): HttpRequest =
        base(uri).timeout(timeout).GET().build()

    private fun put(uri: URI, body: HttpRequest.BodyPublisher, contentType: String): HttpRequest =
        base(uri).timeout(requestTimeout).header("Content-Type", contentType).PUT(body).build()

    private fun base(uri: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        try {
            client.send(request, handler)
        } catch (e: InterruptedException) {
            // Build cancellation. Restore the flag so Gradle's own cancellation
            // still works, and do not let this count as a cache failure.
            Thread.currentThread().interrupt()
            throw CacheIoException("interrupted", e, retryable = false)
        } catch (e: Exception) {
            throw CacheIoException("${request.method()} ${request.uri()} failed: ${e.message}", e, retryable = true)
        }

    private fun failure(method: String, uri: URI, code: Int): CacheIoException = CacheIoException(
        "$method $uri returned HTTP $code",
        retryable = code in RETRYABLE_STATUS,
        authFailure = code == 401 || code == 403,
        payloadTooLarge = code == 413 || code == 507,
    )

    private companion object {
        const val PROTOBUF_CONTENT_TYPE = "application/octet-stream"
        const val OCTET_STREAM_CONTENT_TYPE = "application/octet-stream"
        val PROBE_TIMEOUT: Duration = Duration.ofMillis(1500)

        /** 200/201/202/204; the latter three exist for nginx's WebDAV module. */
        fun isSuccess(code: Int) = code == 200 || code == 201 || code == 202 || code == 204

        /** Bazel treats both as "no entry"; 204 again for nginx compatibility. */
        fun isMiss(code: Int) = code == 404 || code == 204

        val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
    }
}
