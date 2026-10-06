package io.github.pplr.bazelcache.client

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Transport-agnostic view of a Bazel remote cache, narrowed to what a build
 * cache needs. Implemented over HTTP (Bazel's `/ac/` `/cas/` protocol) and over
 * gRPC (REAPI).
 *
 * ## Contract
 *
 * A *miss* is an ordinary result, signalled by `null`/`false` -- never an
 * exception. Implementations throw [CacheIoException] for genuine transport
 * faults, which the layer above converts into a miss after exhausting retries.
 * Nothing here may throw on a cache miss, because Gradle disables the remote
 * cache for the whole build on the first failure it sees.
 *
 * Implementations must be safe for concurrent use: Gradle calls `load` and
 * `store` from every task worker thread at once, against a single instance.
 */
interface RemoteCacheClient : Closeable {

    /** The `ActionResult` at [actionKey], or null if absent. */
    fun getActionResult(actionKey: Digest): ActionResult?

    /** Publishes [result] at [actionKey]. Last writer wins. */
    fun updateActionResult(actionKey: Digest, result: ActionResult)

    /**
     * Streams the blob named by [digest] into [sink].
     *
     * Returns false if the blob is absent. A dangling reference -- an
     * `ActionResult` whose blob has been evicted -- is normal on an LRU cache
     * and must read as a miss.
     */
    fun readBlob(digest: Digest, sink: OutputStream): Boolean

    /**
     * True if [findMissingBlobs] asks the server. False for HTTP, which has no
     * such query, so wrappers can tell a local answer from a network round trip.
     */
    val queriesMissingBlobs: Boolean get() = false

    /**
     * The subset of [digests] the server does not hold.
     *
     * Lets a store skip uploads the server already has. A transport without
     * such a query reports every digest as missing, exactly as Bazel's HTTP
     * cache client does.
     */
    fun findMissingBlobs(digests: Collection<Digest>): Set<Digest> = digests.toSet()

    /**
     * Uploads a blob whose content and digest are already known.
     *
     * [source] may be invoked more than once, because a retry needs to re-read
     * the payload from the start.
     */
    fun writeBlob(digest: Digest, source: () -> InputStream)

    /**
     * Cheap reachability check, used once at service creation.
     *
     * Returning false here is what stops a misconfigured endpoint from turning
     * into one doomed request per task.
     */
    fun probe(): Boolean
}

/**
 * Thrown when the circuit breaker has given up on the cache.
 *
 * Distinct from [CacheIoException] so callers can tell "we deliberately skipped
 * this" from "this failed": the former is expected once a cache is known to be
 * down, and counting thousands of them as errors would bury the real cause.
 */
class CacheDisabledException(val reason: String) : RuntimeException("cache disabled: $reason")

/** A transport fault. Never used to signal a cache miss. */
class CacheIoException(
    message: String,
    cause: Throwable? = null,
    /** True if a retry could plausibly succeed. */
    val retryable: Boolean = false,
    /** True if this is an authentication or authorization failure. */
    val authFailure: Boolean = false,
    /** True if the server refused because the payload is too large. */
    val payloadTooLarge: Boolean = false,
) : RuntimeException(message, cause)
