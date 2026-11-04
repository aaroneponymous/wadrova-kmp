package com.example.wadrova.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Runs on both platforms, so Android's MessageDigest and iOS CommonCrypto give the same hex the server checks. */
class Sha256Test {
    @Test
    fun matchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun coversEveryByteValueAndLargerInputs() {
        val bytes = ByteArray(1_000_000) { (it % 256).toByte() }
        val hash = sha256Hex(bytes)
        assertEquals(64, hash.length)
        assertEquals(hash, sha256Hex(bytes.copyOf()))
        bytes[999_999] = 0
        assertNotEquals(hash, sha256Hex(bytes))
    }
}
