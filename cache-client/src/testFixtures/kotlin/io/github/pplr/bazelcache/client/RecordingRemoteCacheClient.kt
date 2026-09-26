package io.github.pplr.bazelcache.client

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [RemoteCacheClient] that fails on demand and counts attempts, so retry and
 * circuit-breaker behaviour can be asserted exactly rather than approximately.
 */
class RecordingRemoteCacheClient(
    /** Returns the exception to throw for a given 1-based attempt, or null to succeed. */
    private val failure: (attempt: Int) -> Throwable? = { null },
) : RemoteCacheClient {

    val attempts = AtomicInteger()
    var closed = false
        private set

    private fun <T> attempt(value: T): T {
        val n = attempts.incrementAndGet()
        failure(n)?.let { throw it }
        return value
    }

    override fun getActionResult(actionKey: Digest): ActionResult? = attempt(null)

    override fun updateActionResult(actionKey: Digest, result: ActionResult) {
        attempt(Unit)
    }

    override fun readBlob(digest: Digest, sink: OutputStream): Boolean = attempt(false)

    override fun writeBlob(digest: Digest, source: () -> InputStream) {
        attempt(Unit)
    }

    override fun probe(): Boolean = true

    override fun close() { closed = true }
}
