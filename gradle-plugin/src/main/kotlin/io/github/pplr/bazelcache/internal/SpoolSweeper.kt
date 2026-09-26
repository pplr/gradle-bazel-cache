package io.github.pplr.bazelcache.internal

import org.gradle.api.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Deletes spool files left behind by builds that were killed mid-store.
 *
 * The Gradle daemon is long-lived and `close()` never runs for a build that was
 * killed, so without a sweep these accumulate for the daemon's lifetime -- and
 * they are whole cache entries, not small.
 */
internal fun sweepStaleSpoolFiles(
    spoolDirectory: Path,
    logger: Logger,
    olderThan: Duration = Duration.ofHours(6),
) {
    if (!Files.isDirectory(spoolDirectory)) return
    val cutoff = Instant.now().minus(olderThan)
    var removed = 0
    try {
        Files.newDirectoryStream(spoolDirectory).use { entries ->
            for (entry in entries) {
                runCatching {
                    if (Files.isRegularFile(entry) && Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(entry)
                        removed++
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Never let housekeeping fail a build.
        logger.debug("bazel-cache: could not sweep spool directory {}: {}", spoolDirectory, e.message)
        return
    }
    if (removed > 0) logger.info("bazel-cache: removed {} stale spool file(s) from {}", removed, spoolDirectory)
}
