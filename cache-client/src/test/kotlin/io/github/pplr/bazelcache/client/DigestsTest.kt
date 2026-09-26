package io.github.pplr.bazelcache.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DigestsTest {

    @Test
    fun `empty sha256 constant matches a computed empty digest`() {
        assertThat(Digests.sha256Hex(ByteArray(0))).isEqualTo(Digests.EMPTY_SHA256_HEX)
        assertThat(Digests.EMPTY.hash).isEqualTo(Digests.EMPTY_SHA256_HEX)
        assertThat(Digests.EMPTY.sizeBytes).isEqualTo(0)
    }

    @Test
    fun `known vector`() {
        assertThat(Digests.sha256Hex("hello".toByteArray()))
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    }

    @Test
    fun `hex is lowercase and zero padded`() {
        // Servers match ^[a-f0-9]{64}$; an uppercase or unpadded byte is a 400.
        assertThat(Digests.toHex(byteArrayOf(0, 1, 15, 16, -1))).isEqualTo("00010f10ff")
        assertThat(Digests.sha256Hex(ByteArray(0))).matches("[a-f0-9]{64}")
    }

    @Test
    fun `digestOf reports exact size`() {
        val bytes = ByteArray(4096) { it.toByte() }
        assertThat(Digests.digestOf(bytes).sizeBytes).isEqualTo(4096)
    }

    @Test
    fun `isValidHash rejects what servers reject`() {
        assertThat(Digests.isValidHash("a".repeat(64))).isTrue()
        assertThat(Digests.isValidHash("A".repeat(64))).isFalse() // uppercase
        assertThat(Digests.isValidHash("a".repeat(63))).isFalse()
        assertThat(Digests.isValidHash("a".repeat(65))).isFalse()
        assertThat(Digests.isValidHash("g".repeat(64))).isFalse() // non-hex
        assertThat(Digests.isValidHash("")).isFalse()
    }
}
