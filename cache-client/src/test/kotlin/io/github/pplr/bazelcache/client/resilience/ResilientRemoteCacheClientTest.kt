package io.github.pplr.bazelcache.client.resilience

import io.github.pplr.bazelcache.client.CacheDisabledException
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.Digests
import io.github.pplr.bazelcache.client.RecordingRemoteCacheClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Random

class ResilientRemoteCacheClientTest {

    private val key = Digests.digestOf("k".toByteArray())
    private val small = Digests.digestOf("small".toByteArray())
    private val large = Digests.digestOf(ByteArray(0)).toBuilder().setSizeBytes(64L * 1024 * 1024).build()

    private fun retryable(msg: String = "boom") = CacheIoException(msg, retryable = true)

    private fun resilient(
        delegate: RecordingRemoteCacheClient,
        clock: FakeClock = FakeClock(),
        policy: RetryPolicy = RetryPolicy(random = Random(1)),
        budget: RetryBudget = RetryBudget(),
        breaker: CircuitBreaker = CircuitBreaker(clock = clock),
    ) = ResilientRemoteCacheClient(delegate, policy, budget, breaker, clock)

    // --- retries ------------------------------------------------------------

    @Test
    fun `retries a transient failure and succeeds`() {
        val delegate = RecordingRemoteCacheClient { if (it < 3) retryable() else null }
        val client = resilient(delegate)

        assertThat(client.getActionResult(key)).isNull() // delegate returns null on success
        assertThat(delegate.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `gives up after the attempt limit and rethrows`() {
        val delegate = RecordingRemoteCacheClient { retryable() }
        val client = resilient(delegate)

        assertThatThrownBy { client.getActionResult(key) }.isInstanceOf(CacheIoException::class.java)
        assertThat(delegate.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `does not retry a permanent failure`() {
        val delegate = RecordingRemoteCacheClient { CacheIoException("nope", retryable = false) }
        val client = resilient(delegate)

        assertThatThrownBy { client.getActionResult(key) }.isInstanceOf(CacheIoException::class.java)
        assertThat(delegate.attempts.get()).isEqualTo(1)
    }

    @Test
    fun `large payloads get fewer attempts than small ones`() {
        // Re-uploading 64 MiB repeatedly is worse for the build than a miss.
        val big = RecordingRemoteCacheClient { retryable() }
        assertThatThrownBy { resilient(big).writeBlob(large) { ByteArray(0).inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
        assertThat(big.attempts.get()).isEqualTo(2)

        val little = RecordingRemoteCacheClient { retryable() }
        assertThatThrownBy { resilient(little).writeBlob(small) { ByteArray(0).inputStream() } }
            .isInstanceOf(CacheIoException::class.java)
        assertThat(little.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `backoff is bounded and uses full jitter`() {
        val clock = FakeClock()
        val delegate = RecordingRemoteCacheClient { retryable() }
        assertThatThrownBy { resilient(delegate, clock).getActionResult(key) }
            .isInstanceOf(CacheIoException::class.java)

        assertThat(clock.sleeps).hasSize(2)
        // Full jitter draws from [0, cap]; cap is well under the 5s ceiling here.
        assertThat(clock.sleeps).allSatisfy { assertThat(it).isBetween(0L, 5_000L) }
    }

    // --- budget -------------------------------------------------------------

    @Test
    fun `retry budget stops retries once exhausted`() {
        // Without a build-wide cap, every worker thread retries at once and the
        // retries themselves become the load on an already-struggling server.
        val budget = RetryBudget(maxTokens = 10, tokensPerSuccess = 0.0, retryThresholdRatio = 0.5)
        val delegate = RecordingRemoteCacheClient { retryable() }
        val client = resilient(delegate, budget = budget, breaker = CircuitBreaker(maxTrips = Int.MAX_VALUE))

        repeat(20) { runCatching { client.getActionResult(key) } }

        // 10 tokens, floor at 5 -> only 5 retries are ever funded.
        assertThat(budget.availableTokens).isLessThanOrEqualTo(5.0)
        assertThat(delegate.attempts.get()).isLessThan(20 * 3)
    }

    // --- circuit breaker ----------------------------------------------------

    @Test
    fun `breaker opens after consecutive failures and then short-circuits`() {
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 2, clock = clock)
        val delegate = RecordingRemoteCacheClient { CacheIoException("down", retryable = false) }
        val client = resilient(delegate, clock, breaker = breaker)

        repeat(2) { runCatching { client.getActionResult(key) } }
        val attemptsWhenOpen = delegate.attempts.get()

        assertThatThrownBy { client.getActionResult(key) }
            .isInstanceOf(CacheDisabledException::class.java)
        assertThat(delegate.attempts.get())
            .describedAs("open breaker must not reach the network")
            .isEqualTo(attemptsWhenOpen)
        assertThat(client.disabledReason).contains("consecutive failures")
    }

    @Test
    fun `findMissingBlobs is retried like any other call`() {
        val delegate = RecordingRemoteCacheClient { if (it < 2) retryable() else null }
        assertThat(resilient(delegate).findMissingBlobs(listOf(small))).containsExactly(small)
        assertThat(delegate.attempts.get()).isEqualTo(2)
    }

    @Test
    fun `a local findMissingBlobs answer does not hide consecutive failures`() {
        // Over HTTP the answer never touches the network. Were it counted as a
        // success, every store would reset the count and the breaker would
        // never open on a dead server.
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 2, clock = clock)
        val delegate = RecordingRemoteCacheClient(queriesMissingBlobs = false) {
            CacheIoException("down", retryable = false)
        }
        val client = resilient(delegate, clock, breaker = breaker)

        repeat(2) {
            assertThat(client.findMissingBlobs(listOf(small))).containsExactly(small)
            runCatching { client.writeBlob(small) { ByteArray(0).inputStream() } }
        }
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)
    }

    @Test
    fun `auth failure trips the breaker immediately`() {
        // Retrying a rejected credential across thousands of tasks is the worst
        // possible behaviour, and waiting will not fix it.
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 100, clock = clock)
        val delegate = RecordingRemoteCacheClient {
            CacheIoException("401", retryable = false, authFailure = true)
        }
        val client = resilient(delegate, clock, breaker = breaker)

        runCatching { client.getActionResult(key) }

        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(client.disabledReason).contains("authentication rejected")
        assertThatThrownBy { client.getActionResult(key) }
            .isInstanceOf(CacheDisabledException::class.java)
        assertThat(delegate.attempts.get()).isEqualTo(1)
    }

    @Test
    fun `breaker recovers after the open period`() {
        // A 30-minute build should not lose caching because the server
        // restarted three minutes in.
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 1, openDurationMillis = 1_000, clock = clock)
        var healthy = false
        val delegate = RecordingRemoteCacheClient { if (healthy) null else CacheIoException("x", retryable = false) }
        val client = resilient(delegate, clock, breaker = breaker)

        runCatching { client.getActionResult(key) }
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.OPEN)

        clock.advance(1_500)
        healthy = true
        client.getActionResult(key)
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `breaker stays open for good after repeated trips`() {
        val clock = FakeClock()
        val breaker = CircuitBreaker(
            consecutiveFailureThreshold = 1, openDurationMillis = 100, maxTrips = 2, clock = clock,
        )
        val delegate = RecordingRemoteCacheClient { CacheIoException("x", retryable = false) }
        val client = resilient(delegate, clock, breaker = breaker)

        repeat(4) {
            clock.advance(1_000)
            runCatching { client.getActionResult(key) }
        }

        clock.advance(1_000_000)
        assertThatThrownBy { client.getActionResult(key) }
            .isInstanceOf(CacheDisabledException::class.java)
        assertThat(client.disabledReason).contains("giving up")
    }

    @Test
    fun `payload-too-large does not trip the breaker`() {
        // One oversized entry must not cost caching for the other 2000 tasks.
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 2, clock = clock)
        val delegate = RecordingRemoteCacheClient {
            CacheIoException("413", retryable = false, payloadTooLarge = true)
        }
        val client = resilient(delegate, clock, breaker = breaker)

        repeat(10) {
            assertThatThrownBy { client.writeBlob(small) { ByteArray(0).inputStream() } }
                .isInstanceOf(CacheIoException::class.java)
        }
        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    // --- cancellation -------------------------------------------------------

    @Test
    fun `interruption is not counted as a cache failure`() {
        val clock = FakeClock()
        val breaker = CircuitBreaker(consecutiveFailureThreshold = 1, clock = clock)
        val delegate = RecordingRemoteCacheClient { throw InterruptedException("cancelled") }
        val client = resilient(delegate, clock, breaker = breaker)

        val thread = Thread {
            assertThat(client.getActionResult(key)).isNull()
            // The flag must be restored, or Gradle's cancellation stops working.
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        }
        thread.start()
        thread.join()

        assertThat(breaker.currentState()).isEqualTo(CircuitBreaker.State.CLOSED)
    }
}
