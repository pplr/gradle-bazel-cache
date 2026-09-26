package io.github.pplr.bazelcache.client.http

import io.github.pplr.bazelcache.client.CacheEndpoint
import io.github.pplr.bazelcache.client.CacheKeyMapper
import io.github.pplr.bazelcache.client.Digests
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Runs against a REAL bazel-remote, not our fake.
 *
 * This is the test that matters most in the whole suite. Everything else
 * verifies our reading of the protocol; only this verifies the protocol itself
 * -- specifically that a synthetic `ActionResult`, describing an action that
 * never actually ran, is accepted by a server whose AC validator is enabled and
 * which checks that referenced CAS blobs exist before serving an entry.
 *
 * Opt-in: set BAZEL_REMOTE_HTTP_URL. Skipped otherwise so `./gradlew build`
 * needs no container and works offline.
 *
 *     podman run -d -p 9090:8080 -v bazel-remote-data:/data \
 *       docker.io/buchgr/bazel-remote-cache:v2.6.2 --dir /data --max_size 1
 *     BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ ./gradlew integrationTest
 */
@Tag("integration")
class RealBazelRemoteIntegrationTest {

    private val mapper = CacheKeyMapper("v1")

    // Unique per run: a shared cache needs no cleanup and runs stay independent.
    private fun freshKey() = "it-${UUID.randomUUID()}"

    private fun client() = HttpRemoteCacheClient(CacheEndpoint(baseUrl!!))

    @Test
    fun `synthetic ActionResult is accepted by a validating server`() {
        // bazel-remote parses the /ac/ body as an ActionResult and answers 400
        // if it does not validate. A 200 here is the whole design working.
        val key = freshKey()
        val payload = "entry-for-$key".toByteArray()
        val blob = Digests.digestOf(payload)

        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))

            val result = c.getActionResult(mapper.actionKeyFor(key))
            assertThat(result).describedAs("server should serve our AC entry back").isNotNull()
            assertThat(mapper.entryBlobOf(result!!)).isEqualTo(blob)
        }
    }

    @Test
    fun `entry survives the server rewriting the ActionResult`() {
        // bazel-remote appends execution_metadata (its worker address) to AC
        // entries, so what comes back is never byte-identical to what we sent.
        val key = freshKey()
        val payload = "rewritten-$key".toByteArray()
        val blob = Digests.digestOf(payload)

        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            val sent = mapper.actionResultFor(blob)
            c.updateActionResult(mapper.actionKeyFor(key), sent)

            val got = c.getActionResult(mapper.actionKeyFor(key))!!
            assertThat(got.toByteArray())
                .describedAs("server is expected to enrich the entry")
                .isNotEqualTo(sent.toByteArray())
            assertThat(mapper.entryBlobOf(got))
                .describedAs("our parser must survive the enrichment")
                .isEqualTo(blob)
        }
    }

    @Test
    fun `full store and load cycle round-trips the exact bytes`() {
        val key = freshKey()
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val blob = Digests.digestOf(payload)

        client().use { c ->
            c.writeBlob(blob) { payload.inputStream() }
            c.writeBlob(mapper.commandDigest) { mapper.commandBytes.inputStream() }
            val actionBytes = mapper.actionBytesFor(key)
            c.writeBlob(mapper.actionKeyFor(key)) { actionBytes.inputStream() }
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(blob))

            val result = c.getActionResult(mapper.actionKeyFor(key))!!
            val sink = ByteArrayOutputStream()
            assertThat(c.readBlob(mapper.entryBlobOf(result)!!, sink)).isTrue()
            assertThat(sink.toByteArray()).isEqualTo(payload)
        }
    }

    @Test
    fun `the synthetic Action and Command are real CAS blobs`() {
        // REAPI expects the Action and its Command to be in the CAS before an
        // ActionResult refers to them. Storing the true digests means an
        // operator can fetch them and see what produced a cache entry.
        val key = freshKey()
        client().use { c ->
            c.writeBlob(mapper.commandDigest) { mapper.commandBytes.inputStream() }
            val actionDigest = mapper.actionKeyFor(key)
            c.writeBlob(actionDigest) { mapper.actionBytesFor(key).inputStream() }

            val out = ByteArrayOutputStream()
            assertThat(c.readBlob(actionDigest, out)).isTrue()
            // The server verifies CAS content against the key, so a round trip
            // proves our digest really is the digest of those bytes.
            assertThat(out.toByteArray()).isEqualTo(mapper.actionBytesFor(key))
        }
    }

    @Test
    fun `a dangling AC entry reads as a miss`() {
        // bazel-remote runs an AC->CAS dependency check on GET, so an entry whose
        // blob was evicted must 404 rather than hand us a broken pointer.
        val key = freshKey()
        val missing = Digests.digestOf("never-uploaded-$key".toByteArray())

        client().use { c ->
            c.updateActionResult(mapper.actionKeyFor(key), mapper.actionResultFor(missing))
            assertThat(c.getActionResult(mapper.actionKeyFor(key)))
                .describedAs("deps check should suppress an entry with no blob")
                .isNull()
        }
    }

    @Test
    fun `unknown key is a clean miss`() {
        client().use { c ->
            assertThat(c.getActionResult(mapper.actionKeyFor(freshKey()))).isNull()
        }
    }

    @Test
    fun `probe succeeds`() {
        client().use { c -> assertThat(c.probe()).isTrue() }
    }

    companion object {
        private var baseUrl: String? = null

        @JvmStatic
        @BeforeAll
        fun requireServer() {
            baseUrl = System.getenv("BAZEL_REMOTE_HTTP_URL")
            assumeTrue(baseUrl != null, "BAZEL_REMOTE_HTTP_URL not set; skipping real-server tests")
        }
    }
}
