package io.github.pplr.bazelcache.internal

import java.util.concurrent.atomic.AtomicLong

/**
 * Per-build counters, reported once at `close()`.
 *
 * Gradle's own diagnostics for a remote cache are one warning per failure and
 * then silence, so without a summary users cannot tell a cold cache from a
 * broken one -- which is the difference between "keep waiting" and "fix your
 * config".
 */
internal class CacheStats {
    val hits = AtomicLong()
    val misses = AtomicLong()
    val stores = AtomicLong()
    val errors = AtomicLong()
    val skipped = AtomicLong()
    val bytesDown = AtomicLong()
    val bytesUp = AtomicLong()

    @Volatile var lastError: String? = null
    @Volatile var disabledReason: String? = null

    fun recordError(message: String) {
        errors.incrementAndGet()
        lastError = message
    }

    /** True if anything happened worth reporting. */
    fun active(): Boolean =
        hits.get() > 0 || misses.get() > 0 || stores.get() > 0 || errors.get() > 0 || skipped.get() > 0

    fun summary(): String = buildString {
        append("bazel-cache: ")
        append(hits.get()).append(" hits, ")
        append(misses.get()).append(" misses, ")
        append(stores.get()).append(" stores")
        if (skipped.get() > 0) append(", ").append(skipped.get()).append(" skipped")
        if (errors.get() > 0) append(", ").append(errors.get()).append(" errors")
        append(" (").append(mib(bytesDown.get())).append(" down, ").append(mib(bytesUp.get())).append(" up)")
        disabledReason?.let { append(" -- caching disabled: ").append(it) }
        lastError?.takeIf { errors.get() > 0 }?.let { append(" -- last error: ").append(it) }
    }

    private fun mib(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
}
