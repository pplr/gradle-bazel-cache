package io.github.pplr.bazelcache.client.resilience

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stops talking to a cache that is clearly not working.
 *
 * Without this, a cache that is down costs a full timeout on every operation
 * for the rest of the build -- which is strictly slower than having no cache at
 * all. Once open, operations short-circuit locally in microseconds.
 *
 * Recovery is allowed because a long build should not lose caching permanently
 * because the server restarted early on. After [maxTrips] the breaker stays
 * open: at that point the cache is not having a bad moment, it is broken.
 */
class CircuitBreaker(
    private val consecutiveFailureThreshold: Int = 5,
    private val windowSize: Int = 100,
    private val minimumWindowFailures: Int = 20,
    private val failureRateThreshold: Double = 0.5,
    private val openDurationMillis: Long = 30_000,
    private val maxTrips: Int = 3,
    private val clock: Clock = Clock.SYSTEM,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val consecutiveFailures = AtomicInteger()
    private val windowFailures = AtomicInteger()
    private val windowTotal = AtomicInteger()
    private val trips = AtomicInteger()
    private val openedAtMillis = AtomicLong(0)

    @Volatile private var state = State.CLOSED
    @Volatile var reason: String? = null
        private set

    /** True if a probe or a real operation may proceed. */
    fun allowRequest(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> true
        State.OPEN -> {
            if (clock.nowMillis() - openedAtMillis.get() >= openDurationMillis) {
                state = State.HALF_OPEN
                true
            } else {
                false
            }
        }
    }

    fun currentState(): State = state

    fun recordSuccess() {
        consecutiveFailures.set(0)
        record(failure = false)
        if (state == State.HALF_OPEN) {
            state = State.CLOSED
            reason = null
        }
    }

    /**
     * [authFailure] trips immediately: retrying a rejected credential across
     * thousands of tasks is the worst possible behaviour, and no amount of
     * waiting will fix it.
     */
    fun recordFailure(message: String, authFailure: Boolean = false) {
        record(failure = true)
        val consecutive = consecutiveFailures.incrementAndGet()

        if (authFailure) return trip("authentication rejected ($message)")
        if (state == State.HALF_OPEN) return trip("still failing after recovery probe ($message)")
        if (consecutive >= consecutiveFailureThreshold) {
            return trip("$consecutive consecutive failures (last: $message)")
        }
        val total = windowTotal.get()
        if (total >= minimumWindowFailures && windowFailures.get().toDouble() / total >= failureRateThreshold) {
            return trip("failure rate above ${(failureRateThreshold * 100).toInt()}% (last: $message)")
        }
    }

    /** Size rejections and the like: real, but no reason to stop using the cache. */
    fun recordIgnored() = record(failure = false)

    private fun trip(why: String) {
        state = State.OPEN
        reason = why
        openedAtMillis.set(clock.nowMillis())
        consecutiveFailures.set(0)
        if (trips.incrementAndGet() > maxTrips) {
            reason = "$why; giving up after $maxTrips recovery attempts"
            openedAtMillis.set(Long.MAX_VALUE / 2) // never reopens
        }
    }

    private fun record(failure: Boolean) {
        if (windowTotal.incrementAndGet() > windowSize) {
            windowTotal.set(1)
            windowFailures.set(0)
        }
        if (failure) windowFailures.incrementAndGet()
    }
}
