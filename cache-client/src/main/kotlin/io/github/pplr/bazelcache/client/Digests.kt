package io.github.pplr.bazelcache.client

import build.bazel.remote.execution.v2.Digest
import java.security.MessageDigest

/**
 * SHA-256 helpers. REAPI servers accept lowercase hex only: bazel-remote matches
 * both `/ac/` and `/cas/` paths against `^[a-f0-9]{64}$` and rejects anything
 * else with 400.
 */
object Digests {

    /**
     * Digest of the empty byte sequence.
     *
     * Doubles as the digest of an empty `Directory` proto (which serializes to
     * zero bytes), which is what we use for `Action.input_root_digest`. Servers
     * special-case the empty blob as always present, so it is never uploaded.
     *
     * It is also the one hash bazel-remote's `validateHash` accepts with
     * `size_bytes == 0`; every other hash with size 0 is rejected outright.
     */
    const val EMPTY_SHA256_HEX = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    val EMPTY: Digest = Digest.newBuilder()
        .setHash(EMPTY_SHA256_HEX)
        .setSizeBytes(0)
        .build()

    fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun sha256Hex(bytes: ByteArray): String = toHex(newSha256().digest(bytes))

    fun digestOf(bytes: ByteArray): Digest = Digest.newBuilder()
        .setHash(sha256Hex(bytes))
        .setSizeBytes(bytes.size.toLong())
        .build()

    /** True if [hash] is the 64-char lowercase hex every REAPI server requires. */
    fun isValidHash(hash: String): Boolean =
        hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }

    private val HEX = "0123456789abcdef".toCharArray()
}
