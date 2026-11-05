package com.example.wadrova.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The cutout codec runs on each platform's zlib (java.util.zip on Android, libz on iOS), so these tests run on both.
 * The fixture was written by Pillow with the backend's own settings (RGBA, `compress_level=3`), not by this codec.
 */
@OptIn(ExperimentalEncodingApi::class)
class CutoutPngTest {
    private fun fixture(b64: String) = Base64.decode(b64)

    /** The pixels Pillow encoded: the same formula, so the expected values don't come from any decoder. */
    private fun expectedPixel(x: Int, y: Int): IntArray {
        val r = (x * 37 + y * 11) % 256
        val g = (x * 5 + y * 53 + 17) % 256
        val b = (x * x + y * 7) % 256
        val a = when {
            (x + y) % 5 == 0 -> 0
            (x * y) % 7 == 3 -> 128
            else -> 255
        }
        return intArrayOf(r, g, b, a)
    }

    private fun RgbaImage.pixel(x: Int, y: Int): IntArray {
        val i = (y * width + x) * 4
        return IntArray(4) { pixels[i + it].toInt() and 0xFF }
    }

    @Test
    fun aPillowCutoutDecodesExactlyIncludingTheColorItHidesUnderTransparency() {
        val image = decodeRgbaPng(fixture(PILLOW_RGBA_24X16))
        assertEquals(24, image.width)
        assertEquals(16, image.height)
        for (y in 0 until 16) for (x in 0 until 24) assertContentEquals(expectedPixel(x, y), image.pixel(x, y), "pixel $x,$y")
        // The backend's cutouts really do carry photo color under alpha 0; that's what sanitizing removes.
        assertTrue(expectedPixel(0, 0).let { it[3] == 0 && it[1] != 0 })
    }

    @Test
    fun sanitizingKeepsEveryVisiblePixelAndTheAlphaAndClearsHiddenColor() {
        val stored = decodeRgbaPng(sanitizedCutoutPng(fixture(PILLOW_RGBA_24X16), 24, 16))
        var hidden = 0
        var partial = 0
        for (y in 0 until 16) for (x in 0 until 24) {
            val expected = expectedPixel(x, y)
            val actual = stored.pixel(x, y)
            assertEquals(expected[3], actual[3], "alpha at $x,$y")
            if (expected[3] == 0) {
                hidden++
                assertContentEquals(intArrayOf(0, 0, 0, 0), actual, "hidden pixel $x,$y")
            } else {
                if (expected[3] < 255) partial++
                assertContentEquals(expected, actual, "visible pixel $x,$y")
            }
        }
        assertTrue(hidden > 0 && partial > 0)
    }

    @Test
    fun theStoredPngIsMinimalAndTheSameOnEveryRun() {
        val first = sanitizedCutoutPng(fixture(PILLOW_RGBA_24X16))
        assertContentEquals(first, sanitizedCutoutPng(fixture(PILLOW_RGBA_24X16)))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(first))
    }

    @Test
    fun everyFilterRoundTripsExactlyOnALargerImage() {
        val random = Random(7)
        val width = 301
        val height = 157
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            // Flat, gradient and noisy regions, so the encoder picks every filter type somewhere.
            val region = (x / 60 + y / 40) % 3
            for (c in 0 until 3) {
                pixels[i + c] = when (region) {
                    0 -> 90
                    1 -> (x + y * c).toByte()
                    else -> random.nextInt(256).toByte()
                }
            }
            pixels[i + 3] = when {
                x < 20 || y < 10 -> 0
                (x + y) % 13 == 0 -> random.nextInt(1, 255).toByte()
                else -> 255.toByte()
            }
        }
        val source = clearHiddenColor(RgbaImage(width, height, pixels))
        for (filter in 0..4) {
            val encoded = encodeRgbaPng(source, onlyFilter = filter)
            assertEquals(setOf(filter), usedFilters(encoded, width, height))
            assertContentEquals(source.pixels, decodeRgbaPng(encoded).pixels, "filter $filter")
        }
        val adaptive = encodeRgbaPng(source)
        assertTrue(usedFilters(adaptive, width, height).size > 1)
        assertContentEquals(source.pixels, decodeRgbaPng(adaptive).pixels)
    }

    @Test
    fun onlyEightBitRgbaWithoutAColorProfileIsAccepted() {
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(fixture(PILLOW_RGB)) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(withHeaderByte(fixture(PILLOW_RGBA_24X16), 12, 1)) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(fixture(PILLOW_ICC)) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(fixture(PILLOW_16_BIT_GREY)) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng("png-cnd_j1".encodeToByteArray()) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(ByteArray(0)) }
    }

    @Test
    fun aCorruptTruncatedOrMisdescribedCutoutIsRefused() {
        val good = fixture(PILLOW_RGBA_24X16)
        val flipped = good.copyOf().also { it[good.size / 2] = (it[good.size / 2].toInt() xor 0x40).toByte() }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(flipped) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(good.copyOf(good.size - 20)) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(good, expectedWidth = 25, expectedHeight = 16) }
        assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(good, expectedWidth = 24, expectedHeight = 15) }
    }

    @Test
    fun anOversizedHeaderIsRefusedBeforeAnythingIsDecoded() {
        // IHDR rewritten to claim 9000x9000 with a valid CRC: the size check, not the decoder, must refuse it.
        var forged = encodeRgbaPng(RgbaImage(1, 1, ByteArray(4)))
        for (at in listOf(0, 1, 4, 5)) forged = withHeaderByte(forged, at, 0)
        for (at in listOf(2, 6)) forged = withHeaderByte(forged, at, 0x23)
        for (at in listOf(3, 7)) forged = withHeaderByte(forged, at, 0x28)
        val error = assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(forged) }
        assertEquals("The cutout's size isn't supported.", error.message)
    }

    /** [png] with IHDR data byte [index] set to [value] and the chunk's CRC recomputed, so only the field is wrong. */
    private fun withHeaderByte(png: ByteArray, index: Int, value: Int): ByteArray {
        val out = png.copyOf()
        out[16 + index] = value.toByte()
        val crc = crc32(out, 12, 17)
        out[29] = (crc ushr 24).toByte(); out[30] = (crc ushr 16).toByte(); out[31] = (crc ushr 8).toByte(); out[32] = crc.toByte()
        return out
    }

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var at = 8
        while (at < png.size) {
            val length = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
                ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
            types += png.decodeToString(at + 4, at + 8)
            at += 12 + length
        }
        return types
    }

    private fun usedFilters(png: ByteArray, width: Int, height: Int): Set<Int> {
        var at = 8
        var idat = ByteArray(0)
        while (at < png.size) {
            val length = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
                ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
            if (png.decodeToString(at + 4, at + 8) == "IDAT") idat += png.copyOfRange(at + 8, at + 8 + length)
            at += 12 + length
        }
        val raw = zlibInflate(idat, (width * 4 + 1) * height)
        return (0 until height).map { raw[it * (width * 4 + 1)].toInt() }.toSet()
    }

    private companion object {
        const val PILLOW_RGBA_24X16 =
            "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAYAAAAMJL+VAAABb0lEQVR4XoVU0WrDQAzzkYkwzBhljDFKKWWIMcoe831pv/zGXc6p4ybdgx/qWLJkJU2yEyFSJjohIEQvxHMiNBMvQrwKsRPiLRHvmfgQ4lOIvRCHRBwzcRLiSwgK8Z2In0ychfiVJx36rEii6ESBq6K/KEqvF1ep9WPP5gpuDL1aZYG4BUZugEhkZFGEkd+JsAVXRXdRwECePIIqeTnllvL2rPZswUXRZQU8UWKw64iMeNThkcMpg6RIWdFdFbgpr9msgepv5/C46tBwPuSxOYiKPOjfbILrMYY8g9odI1El5z15Ka/cag65LEjtLVoMONBqoCtzi54tSCHkePNSRn4TMRxmIodbiLCQy1tUTiRlIUNQHuROF8kLzpNXnA95WhAGoiK3NDo0bHN4LrjFl1xeUw+qA0vQXJsOw9wiZPuSDVAHPIgL5XsjqeTtrNabsMNKyO2O08C9oom8YuZam5t7FnLWYf433bT7oLfp+g/B0pkgodg6ywAAAABJRU5ErkJggg=="
        const val PILLOW_RGB =
            "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAIAAACDRijCAAAAlUlEQVR4nM2SbQcCQRRGW3WMXEmSZGUlR7L6OP//r2UlSdtqe+f5MMadY54zUwxmAylkKEiSsYRMZCozmctClrKSUtZSyUa2ouxkL7UcRpFTUATDgCA9ndEPQHZutoNaz8QpzfxtWh21j6Zzqvc7shvU7SL+6NWi0bd+DJR79PID1ervOvKyLu+Wzdeg/h/69kb51WpHP74rrrMBd+8AAAAASUVORK5CYII="
        const val PILLOW_ICC =
            "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAYAAAAMJL+VAAAAGWlDQ1BJQ0MgUHJvZmlsZQAAeJxjYBhYAAAAgAAB+ZUIqQAAAUhJREFUeJyVVNFKxDAQ3FCHIovIISJyHIfIIHL4mO9L++WRxOSaxjS9e+jLtrMzuzNbIwcRwnhiEAJCjEI8GkI98STEsxAHIV4M8eqJNyHehTgKcTLE2RMfQnwKQSG+DPHtiYsQP/KgdvQKI4pBFJgV46QItVGKx6R6XcvfBZyravEJBFIQ5OYZIA1QS4TbEpEJZsUwKZBBZfOpAsXm7ChP72ItE0yKwSuwYmd7VeWETm1vwj8PjMJ4xTArsCi33bW4hfDcnDDjSpNdmuCfUbqAdr2ppna1yVdQ2qNUjWJz9kU0TQ4EJqXo7ihqp5YJTGXybVG0p10R2eQprUgCISujtACxo5zr5hFXX3I7irjp2Kq7uUS/Sg9CTDeiOHaj2POmJMiXvBlFrpQfV96kta6nsQ2T0x779xAx+wkqTfZqr3/Tu6PYm/oXwdKZIHmqo0kAAAAASUVORK5CYII="
        const val PILLOW_16_BIT_GREY = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAEEAAAAADcCh3hAAAAC0lEQVR4nGNgIAwAACQAAbZm3wQAAAAASUVORK5CYII="
    }
}
