package io.github.pplr.bazelcache.client.resilience

import java.util.concurrent.atomic.AtomicLong

/**
 * Token bucket that caps retries across the whole build, modelled on gRPC's
 * retry throttling.
 *
 * Per-operation retry limits are not enough on their own: when a cache server is
 * struggling, every one of a build's worker threads retries at once and the
 * retries themselves become the load. This bounds total retry effort, so a
 * degraded server sees roughly normal traffic rather than a multiple of it.
 *
 * Tokens are held in thousandths so a fractional refill per success needs no
 * floating-point CAS loop.
 */
class RetryBudget(
    maxTokens: Int = 100,
    tokensPerSuccess: Double = 0.2,
    retryThresholdRatio: Double = 0.5,
) {
    private val maxMilliTokens: Long = maxTokens * SCALE
    private val refillMilliTokens: Long = (tokensPerSuccess * SCALE).toLong()
    private val thresholdMilliTokens: Long = (maxTokens * retryThresholdRatio * SCALE).toLong()

    private val milliTokens = AtomicLong(maxMilliTokens)

    /** True if a retry may be attempted; consumes a token when it returns true. */
    fun tryConsume(): Boolean {
        while (true) {
            val current = milliTokens.get()
            if (current <= thresholdMilliTokens) return false
            if (milliTokens.compareAndSet(current, current - SCALE)) return true
        }
    }

    fun recordSuccess() {
        while (true) {
            val current = milliTokens.get()
            val next = minOf(maxMilliTokens, current + refillMilliTokens)
            if (current == next || milliTokens.compareAndSet(current, next)) return
        }
    }

    val availableTokens: Double get() = milliTokens.get() / SCALE.toDouble()

    private companion object {
        const val SCALE = 1000L
    }
}
