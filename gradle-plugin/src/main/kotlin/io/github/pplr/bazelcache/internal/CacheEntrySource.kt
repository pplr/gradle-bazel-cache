package io.github.pplr.bazelcache.internal

import build.bazel.remote.execution.v2.Digest
import io.github.pplr.bazelcache.client.Digests
import org.gradle.caching.BuildCacheEntryWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream

/**
 * A cache entry whose SHA-256 is known and whose bytes can be read more than
 * once.
 *
 * Both properties are forced on us by the protocol: a CAS resource name embeds
 * the digest, so the content must be fully hashed before the first byte is
 * uploaded, and a retry has to re-read the payload from the start.
 */
internal interface CacheEntrySource : Closeable {
    val digest: Digest
    fun open(): InputStream
}

internal object CacheEntrySources {

    /**
     * Gradle 9.7 added `BuildCacheEntryWriter.getInputStream()`, documented to
     * return a fresh stream per call. Where it exists we can hash from one
     * stream and upload from another, skipping the spool copy entirely.
     *
     * Resolved once: per-call reflection on a hot path is not free, and the
     * method is `@Incubating`, so it may change shape in a later release.
     */
    private val getInputStream = try {
        MethodHandles.publicLookup().findVirtual(
            BuildCacheEntryWriter::class.java,
            "getInputStream",
            MethodType.methodType(InputStream::class.java),
        )
    } catch (_: Throwable) {
        null
    }

    val streamingSupported: Boolean get() = getInputStream != null

    fun of(writer: BuildCacheEntryWriter, spoolDirectory: Path, inMemoryLimit: Long): CacheEntrySource {
        if (getInputStream != null) {
            try {
                return streaming(writer)
            } catch (_: Throwable) {
                // Incubating API: fall back rather than fail a store.
            }
        }
        return spool(writer, spoolDirectory, inMemoryLimit)
    }

    /** Gradle >= 9.7: hash one stream, upload another. No copy. */
    private fun streaming(writer: BuildCacheEntryWriter): CacheEntrySource {
        val handle = requireNotNull(getInputStream)

        fun newStream(): InputStream = handle.invoke(writer) as InputStream

        val sha = Digests.newSha256()
        var size = 0L
        newStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
                size += read
            }
        }

        val digest = Digest.newBuilder()
            .setHash(Digests.toHex(sha.digest()))
            .setSizeBytes(size)
            .build()

        return object : CacheEntrySource {
            override val digest: Digest = digest
            override fun open(): InputStream = newStream()
            override fun close() = Unit
        }
    }

    /**
     * Gradle < 9.7: `writeTo` closes the stream it is given and the contract
     * does not promise it can be called twice, so the payload is captured once
     * while hashing it in the same pass.
     */
    private fun spool(writer: BuildCacheEntryWriter, spoolDirectory: Path, inMemoryLimit: Long): CacheEntrySource {
        // getSize() is exact (it is the length of Gradle's own temp file), but
        // treat it as a hint and trust the bytes we actually observe.
        val expected = runCatching { writer.size }.getOrDefault(-1L)

        return if (expected in 0..inMemoryLimit) {
            val buffer = ByteArrayOutputStream(expected.toInt().coerceAtLeast(32))
            val digest = writeAndDigest(writer, buffer)
            object : CacheEntrySource {
                override val digest: Digest = digest
                override fun open(): InputStream = ByteArrayInputStream(buffer.toByteArray())
                override fun close() = Unit
            }
        } else {
            Files.createDirectories(spoolDirectory)
            val file = Files.createTempFile(spoolDirectory, "entry-", ".bin")
            val digest = try {
                Files.newOutputStream(file).use { out -> writeAndDigest(writer, out) }
            } catch (e: Throwable) {
                Files.deleteIfExists(file)
                throw e
            }
            object : CacheEntrySource {
                override val digest: Digest = digest
                override fun open(): InputStream = Files.newInputStream(file)
                override fun close() { Files.deleteIfExists(file) }
            }
        }
    }

    private fun writeAndDigest(writer: BuildCacheEntryWriter, sink: OutputStream): Digest {
        val sha = Digests.newSha256()
        val counting = CountingOutputStream(DigestOutputStream(sink, sha))
        // writeTo closes the stream it is handed.
        writer.writeTo(counting)
        return Digest.newBuilder()
            .setHash(Digests.toHex(sha.digest()))
            .setSizeBytes(counting.count)
            .build()
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len); count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
