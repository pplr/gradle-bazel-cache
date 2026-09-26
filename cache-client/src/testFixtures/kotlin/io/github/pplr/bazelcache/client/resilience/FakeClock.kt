package io.github.pplr.bazelcache.client.resilience

import java.util.concurrent.atomic.AtomicLong

/** Virtual time: backoff and breaker timeouts are tested without real sleeps. */
class FakeClock(startMillis: Long = 0) : Clock {
    private val now = AtomicLong(startMillis)
    val sleeps = mutableListOf<Long>()

    override fun nowMillis(): Long = now.get()

    override fun sleep(millis: Long) {
        synchronized(sleeps) { sleeps += millis }
        now.addAndGet(millis)
    }

    fun advance(millis: Long) = now.addAndGet(millis)
}
