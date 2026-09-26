package io.github.pplr.bazelcache.client.resilience

import java.util.Random

/**
 * Attempt limits and backoff.
 *
 * Backoff uses FULL jitter -- a uniform draw from `[0, cap]` rather than a
 * proportional band around it. Every worker thread in the build is talking to
 * the same server, so proportional jitter would leave them clustered and
 * re-synchronised on each round.
 *
 * Large payloads get fewer attempts: re-uploading 50 MiB twice is a worse
 * outcome for the build than accepting a miss.
 */
class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val maxAttemptsLargePayload: Int = 2,
    private val largePayloadBytes: Long = 8L * 1024 * 1024,
    private val baseDelayMillis: Long = 100,
    private val multiplier: Double = 1.6,
    private val maxDelayMillis: Long = 5_000,
    private val random: Random = Random(),
) {
    fun maxAttemptsFor(sizeBytes: Long): Int =
        if (sizeBytes >= largePayloadBytes) maxAttemptsLargePayload else maxAttempts

    /** Delay before attempt [attempt] (1-based: the delay after attempt 1 fails). */
    fun delayMillis(attempt: Int): Long {
        val cap = minOf(maxDelayMillis, (baseDelayMillis * Math.pow(multiplier, (attempt - 1).toDouble())).toLong())
        if (cap <= 0) return 0
        return (random.nextDouble() * cap).toLong()
    }
}
