package io.github.pplr.bazelcache.client.resilience

/** Injected so retry and circuit-breaker tests need no real sleeps. */
interface Clock {
    fun nowMillis(): Long
    fun sleep(millis: Long)

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun nowMillis(): Long = System.nanoTime() / 1_000_000
            override fun sleep(millis: Long) = Thread.sleep(millis)
        }
    }
}
