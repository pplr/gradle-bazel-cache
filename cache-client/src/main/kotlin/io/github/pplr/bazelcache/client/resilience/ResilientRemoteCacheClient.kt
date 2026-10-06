package io.github.pplr.bazelcache.client.resilience

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import io.github.pplr.bazelcache.client.CacheDisabledException
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.RemoteCacheClient
import java.io.InputStream
import java.io.OutputStream

/**
 * Adds retries, a retry budget and a circuit breaker to a [RemoteCacheClient].
 *
 * This layer exists because Gradle has none of it: Gradle never retries, and
 * the first failure it sees disables the remote cache for the remainder of the
 * build. A single transient 503 would otherwise cost every remaining cache hit,
 * so recovery has to happen below Gradle's notice.
 *
 * When the breaker is open, reads report a miss and writes are dropped. That is
 * deliberately indistinguishable from a cold cache: the build keeps going.
 */
class ResilientRemoteCacheClient(
    private val delegate: RemoteCacheClient,
    private val policy: RetryPolicy = RetryPolicy(),
    private val budget: RetryBudget = RetryBudget(),
    private val breaker: CircuitBreaker = CircuitBreaker(),
    private val clock: Clock = Clock.SYSTEM,
) : RemoteCacheClient {

    /** Non-null once the breaker has tripped; surfaced in the build summary. */
    val disabledReason: String? get() = breaker.reason

    override fun getActionResult(actionKey: Digest): ActionResult? =
        call("getActionResult", 0) { delegate.getActionResult(actionKey) }

    override fun updateActionResult(actionKey: Digest, result: ActionResult) {
        call("updateActionResult", 0) { delegate.updateActionResult(actionKey, result) }
    }

    override fun readBlob(digest: Digest, sink: OutputStream): Boolean =
        call("readBlob", digest.sizeBytes) { delegate.readBlob(digest, sink) } ?: false

    override val queriesMissingBlobs: Boolean get() = delegate.queriesMissingBlobs

    /**
     * A local answer (HTTP) bypasses the breaker: counting it as a success
     * would reset the consecutive-failure count before every store.
     */
    override fun findMissingBlobs(digests: Collection<Digest>): Set<Digest> =
        if (!delegate.queriesMissingBlobs) {
            delegate.findMissingBlobs(digests)
        } else {
            call("findMissingBlobs", 0) { delegate.findMissingBlobs(digests) } ?: digests.toSet()
        }

    override fun writeBlob(digest: Digest, source: () -> InputStream) {
        call("writeBlob", digest.sizeBytes) { delegate.writeBlob(digest, source) }
    }

    override fun probe(): Boolean = delegate.probe()

    override fun close() = delegate.close()

    /**
     * Runs [block] with retries.
     *
     * Throws [CacheDisabledException] when the breaker is open and rethrows the
     * final [CacheIoException] once attempts are exhausted, rather than
     * absorbing either. The caller needs to tell a deliberate skip from a real
     * failure -- otherwise a dropped store would be counted as a successful one,
     * and `strict` mode would silently stop working.
     */
    private fun <T> call(operation: String, sizeBytes: Long, block: () -> T): T? {
        if (!breaker.allowRequest()) throw CacheDisabledException(breaker.reason ?: "cache unavailable")

        val maxAttempts = policy.maxAttemptsFor(sizeBytes)
        var attempt = 1
        while (true) {
            try {
                val result = block()
                breaker.recordSuccess()
                budget.recordSuccess()
                return result
            } catch (e: InterruptedException) {
                // Build cancellation is not a cache fault.
                Thread.currentThread().interrupt()
                return null
            } catch (e: CacheIoException) {
                if (Thread.currentThread().isInterrupted) return null

                if (e.payloadTooLarge) {
                    // Real, but not a reason to stop using the cache for
                    // everything else in the build.
                    breaker.recordIgnored()
                    throw e
                }

                val describe = "$operation: ${e.message}"
                val lastAttempt = attempt >= maxAttempts
                if (!e.retryable || lastAttempt || !budget.tryConsume()) {
                    breaker.recordFailure(describe, authFailure = e.authFailure)
                    throw e
                }

                clock.sleep(policy.delayMillis(attempt))
                attempt++
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) return null
                breaker.recordFailure("$operation: ${e.message}")
                throw e
            }
        }
    }
}
