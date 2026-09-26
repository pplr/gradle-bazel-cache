package io.github.pplr.bazelcache.client.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process stand-in for a Bazel HTTP cache.
 *
 * Deliberately a real socket server rather than a mocked `HttpClient`: the
 * behaviours worth testing here are wire-level (truncated bodies, status codes,
 * dangling references), and a mock would let us assert our own assumptions
 * rather than the protocol.
 */
class FakeBazelCacheServer : Closeable {

    val ac = ConcurrentHashMap<String, ByteArray>()
    val cas = ConcurrentHashMap<String, ByteArray>()

    val requestCount = AtomicInteger()
    val getCount = AtomicInteger()
    val putCount = AtomicInteger()

    /** When set, every request returns this status instead of being served. */
    @Volatile var forceStatus: Int? = null

    /** Fail the first N requests with [forceStatus] (or 503), then behave. */
    @Volatile var failFirst: Int = 0

    /** Serve CAS bodies truncated to this many bytes, simulating a torn read. */
    @Volatile var truncateCasTo: Int? = null

    /** Artificial per-request delay. */
    @Volatile var delayMillis: Long = 0

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        executor = null
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/"

    private fun handle(exchange: HttpExchange) {
        requestCount.incrementAndGet()
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)

            val forced = forceStatus
            if (failFirst > 0) {
                failFirst--
                respond(exchange, forced ?: 503, ByteArray(0)); return
            }
            if (forced != null) { respond(exchange, forced, ByteArray(0)); return }

            val path = exchange.requestURI.path.trimStart('/')
            val store = when {
                path.startsWith("ac/") -> ac
                path.startsWith("cas/") -> cas
                else -> { respond(exchange, 400, ByteArray(0)); return }
            }
            val key = path.substringAfter('/')
            if (!key.matches(Regex("[a-f0-9]{64}"))) { respond(exchange, 400, ByteArray(0)); return }

            when (exchange.requestMethod) {
                "GET" -> {
                    getCount.incrementAndGet()
                    val body = store[key] ?: run { respond(exchange, 404, ByteArray(0)); return }
                    val limit = truncateCasTo
                    val served = if (store === cas && limit != null) body.copyOf(minOf(limit, body.size)) else body
                    respond(exchange, 200, served)
                }
                "PUT" -> {
                    putCount.incrementAndGet()
                    store[key] = exchange.requestBody.use { it.readBytes() }
                    respond(exchange, 200, ByteArray(0))
                }
                else -> respond(exchange, 405, ByteArray(0))
            }
        } catch (e: Exception) {
            runCatching { respond(exchange, 500, ByteArray(0)) }
        } finally {
            exchange.close()
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        // Content-Length must reflect what we actually write; when truncating we
        // still advertise the truncated length, so the client sees a complete
        // response carrying the wrong bytes -- the realistic corruption case.
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    fun reset() {
        ac.clear(); cas.clear()
        forceStatus = null; failFirst = 0; truncateCasTo = null; delayMillis = 0
        requestCount.set(0); getCount.set(0); putCount.set(0)
    }

    override fun close() = server.stop(0)
}
