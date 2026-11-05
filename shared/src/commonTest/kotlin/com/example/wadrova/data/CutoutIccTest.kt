package com.example.wadrova.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cutouts with an embedded color profile, as the backend actually serves them: Pillow carries the uploaded photo's
 * profile into each cutout. Runs on both platforms' zlib.
 */
@OptIn(ExperimentalEncodingApi::class)
class CutoutIccTest {
    /** Written by Pillow like a backend cutout (RGBA, `compress_level=3`, IHDR iCCP IDAT IEND), carrying the same
     *  456-byte sRGB profile the live SAM 3 cutouts carried. Same pixels as [CutoutPngTest]'s fixture. */
    private val srgbIccFixture = Base64.decode(IccFixtures.SRGB_ICC)

    /** The same pixels with a small synthetic Display P3 (matrix/TRC) profile, to prove nothing assumes sRGB. */
    private val p3IccFixture = Base64.decode(IccFixtures.P3_ICC)

    private fun expectedPixel(x: Int, y: Int): IntArray {
        val a = when {
            (x + y) % 5 == 0 -> 0
            (x * y) % 7 == 3 -> 128
            else -> 255
        }
        return intArrayOf((x * 37 + y * 11) % 256, (x * 5 + y * 53 + 17) % 256, (x * x + y * 7) % 256, a)
    }

    private fun profileOf(png: ByteArray): ByteArray = (decodeRgbaPng(png).colorSpace as PngColorSpace.Icc).profile

    @Test
    fun theRealProfileStructureIsAcceptedKeptAndHiddenColorCleared() {
        val source = decodeRgbaPng(srgbIccFixture)
        val profile = assertIs<PngColorSpace.Icc>(source.colorSpace).profile
        assertEquals(456, profile.size)
        val stored = sanitizedCutoutPng(srgbIccFixture, 24, 16)
        assertEquals(listOf("IHDR", "iCCP", "IDAT", "IEND"), chunks(stored).map { it.first })
        val image = decodeRgbaPng(stored)
        assertContentEquals(profile, assertIs<PngColorSpace.Icc>(image.colorSpace).profile)
        assertExactlySanitized(image)
    }

    @Test
    fun aNonSrgbProfileIsKeptByteForByteNotReinterpreted() {
        val profile = profileOf(p3IccFixture)
        val stored = sanitizedCutoutPng(p3IccFixture, 24, 16)
        assertContentEquals(profile, profileOf(stored))
        assertExactlySanitized(decodeRgbaPng(stored))
        assertContentEquals(stored, sanitizedCutoutPng(p3IccFixture, 24, 16))
    }

    @Test
    fun aCutoutWithoutAProfileStaysUntaggedAndAnSrgbChunkIsKept() {
        val plain = encodeRgbaPng(RgbaImage(2, 1, byteArrayOf(1, 2, 3, 0, 4, 5, 6, -1)))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks(sanitizedCutoutPng(plain)).map { it.first })
        val srgb = insertBeforeIdat(plain, "sRGB", byteArrayOf(0))
        val kept = sanitizedCutoutPng(srgb)
        assertEquals(listOf("IHDR", "sRGB", "IDAT", "IEND"), chunks(kept).map { it.first })
        assertEquals(PngColorSpace.Srgb(0), decodeRgbaPng(kept).colorSpace)
    }

    @Test
    fun textExifAndOtherAncillaryChunksAreNotCopiedButGammaBesideAProfileIsHarmless() {
        var png = insertBeforeIdat(srgbIccFixture, "tEXt", "Comment\u0000from a camera".encodeToByteArray())
        png = insertBeforeIdat(png, "eXIf", ByteArray(16) { it.toByte() })
        png = insertBeforeIdat(png, "gAMA", byteArrayOf(0, 0, 0xB1.toByte(), 0x8F.toByte()))
        assertEquals(listOf("IHDR", "iCCP", "IDAT", "IEND"), chunks(sanitizedCutoutPng(png)).map { it.first })
    }

    @Test
    fun aMalformedProfileIsRefusedAsInvalid() {
        val name = "ICC Profile".encodeToByteArray()
        val profile = profileOf(srgbIccFixture)
        fun withBody(body: ByteArray) = replaceChunk(srgbIccFixture, "iCCP", body)
        fun refused(png: ByteArray) = assertEquals(CutoutProblem.INVALID_PNG, assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(png) }.problem)

        refused(withBody(name + byteArrayOf(0, 1) + zlibDeflate(profile))) // unknown compression method
        refused(withBody(name + byteArrayOf(0, 0) + zlibDeflate(profile).copyOf(40))) // truncated compressed data
        refused(withBody(name + byteArrayOf(0, 0) + ByteArray(64) { 0x5A })) // not zlib at all
        refused(withBody(name + byteArrayOf(0, 0) + zlibDeflate(profile.copyOf(300)))) // profile shorter than it declares
        refused(withBody(byteArrayOf(0, 0) + zlibDeflate(profile))) // empty name
        refused(withBody(ByteArray(80) { 0x41 } + byteArrayOf(0, 0) + zlibDeflate(profile))) // name over 79 bytes
        refused(withBody(name + byteArrayOf(0, 0) + zlibDeflate(profile.copyOf().also { it[36] = 'x'.code.toByte() }))) // no acsp
        // Wrong CRC: change one profile byte without fixing the chunk's CRC.
        val badCrc = srgbIccFixture.copyOf()
        val iccp = chunks(badCrc).first { it.first == "iCCP" }
        badCrc[iccp.second + 8 + 20] = (badCrc[iccp.second + 8 + 20].toInt() xor 1).toByte()
        refused(badCrc)
    }

    @Test
    fun aGreyProfileOnAnRgbaCutoutIsRefusedAsUnsupported() {
        val grey = profileOf(srgbIccFixture).copyOf().also { "GRAY".encodeToByteArray().copyInto(it, 16) }
        val png = replaceChunk(srgbIccFixture, "iCCP", "ICC Profile".encodeToByteArray() + byteArrayOf(0, 0) + zlibDeflate(grey))
        assertEquals(CutoutProblem.UNSUPPORTED_FORMAT, assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(png) }.problem)
    }

    @Test
    fun duplicateMisplacedOrConflictingColorDeclarationsAreRefused() {
        val iccp = chunks(srgbIccFixture).first { it.first == "iCCP" }
        val iccpBody = srgbIccFixture.copyOfRange(iccp.second + 8, iccp.second + 8 + iccp.third)
        val plain = encodeRgbaPng(RgbaImage(1, 1, byteArrayOf(9, 9, 9, -1)))
        fun problem(png: ByteArray) = assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(png) }.problem

        assertEquals(CutoutProblem.INVALID_PNG, problem(insertBeforeIdat(srgbIccFixture, "iCCP", iccpBody)))
        assertEquals(CutoutProblem.INVALID_PNG, problem(insertBeforeIdat(srgbIccFixture, "sRGB", byteArrayOf(0))))
        assertEquals(CutoutProblem.INVALID_PNG, problem(insertBeforeIdat(insertBeforeIdat(plain, "sRGB", byteArrayOf(0)), "sRGB", byteArrayOf(0))))
        assertEquals(CutoutProblem.INVALID_PNG, problem(insertBeforeIdat(plain, "sRGB", byteArrayOf(7))))
        assertEquals(CutoutProblem.INVALID_PNG, problem(insertBeforeIend(plain, "iCCP", iccpBody)))
        assertEquals(CutoutProblem.UNSUPPORTED_FORMAT, problem(insertBeforeIdat(srgbIccFixture, "cICP", byteArrayOf(1, 13, 0, 1))))
        assertEquals(CutoutProblem.UNSUPPORTED_FORMAT, problem(insertBeforeIdat(plain, "gAMA", byteArrayOf(0, 0, 0xB1.toByte(), 0x8F.toByte()))))
    }

    @Test
    fun oversizedProfilesAreRefusedWithoutExpandingThem() {
        val name = "ICC Profile".encodeToByteArray()
        // Incompressible data larger than the compressed limit.
        val big = zlibDeflate(Random(1).nextBytes(MAX_ICC_COMPRESSED_BYTES + 1024))
        assertTrue(big.size > MAX_ICC_COMPRESSED_BYTES)
        val tooBig = replaceChunk(srgbIccFixture, "iCCP", name + byteArrayOf(0, 0) + big)
        assertEquals(CutoutProblem.INVALID_PNG, assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(tooBig) }.problem)
        // A few KB that would expand past the profile limit (a compression bomb).
        val bomb = zlibDeflate(ByteArray(MAX_ICC_PROFILE_BYTES + 4096))
        assertTrue(bomb.size < 16 * 1024)
        val bombed = replaceChunk(srgbIccFixture, "iCCP", name + byteArrayOf(0, 0) + bomb)
        assertEquals(CutoutProblem.INVALID_PNG, assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(bombed) }.problem)
    }

    @Test
    fun aSizeMismatchIsAnIntegrityProblem() {
        val error = assertFailsWith<CutoutFormatException> { sanitizedCutoutPng(srgbIccFixture, 25, 16) }
        assertEquals(CutoutProblem.INTEGRITY, error.problem)
    }

    private fun assertExactlySanitized(image: RgbaImage) {
        assertEquals(24, image.width)
        assertEquals(16, image.height)
        for (y in 0 until 16) for (x in 0 until 24) {
            val expected = expectedPixel(x, y)
            val i = (y * 24 + x) * 4
            val actual = IntArray(4) { image.pixels[i + it].toInt() and 0xFF }
            if (expected[3] == 0) assertContentEquals(intArrayOf(0, 0, 0, 0), actual, "hidden $x,$y")
            else assertContentEquals(expected, actual, "visible $x,$y")
        }
    }

}

/** Base64 PNG fixtures (24x16, same pixels as CutoutPngTest's), shared with the platform display tests. */
internal object IccFixtures {
    /** Pillow-written like a backend cutout, with the 456-byte sRGB profile the live SAM 3 cutouts carried. */
    const val SRGB_ICC = "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAYAAAAMJL+VAAABCGlDQ1BJQ0MgUHJvZmlsZQAAeJxjYGA8wQAELAYMDLl5JUVB7k4KEZFRCuwPGBiBEAwSk4sLGHADoKpv1yBqL+viUYcLcKakFicD6Q9ArFIEtBxopAiQLZIOYWuA2EkQtg2IXV5SUAJkB4DYRSFBzkB2CpCtkY7ETkJiJxcUgdT3ANk2uTmlyQh3M/Ck5oUGA2kOIJZhKGYIYnBncAL5H6IkfxEDg8VXBgbmCQixpJkMDNtbGRgkbiHEVBYwMPC3MDBsO48QQ4RJQWJRIliIBYiZ0tIYGD4tZ2DgjWRgEL7AwMAVDQsIHG5TALvNnSEfCNMZchhSgSKeDHkMyQx6QJYRgwGDIYMZAKbWPz9HbOBQAAABb0lEQVR4XoVU0WrDQAzzkYkwzBhljDFKKWWIMcoe831pv/zGXc6p4ybdgx/qWLJkJU2yEyFSJjohIEQvxHMiNBMvQrwKsRPiLRHvmfgQ4lOIvRCHRBwzcRLiSwgK8Z2In0ychfiVJx36rEii6ESBq6K/KEqvF1ep9WPP5gpuDL1aZYG4BUZugEhkZFGEkd+JsAVXRXdRwECePIIqeTnllvL2rPZswUXRZQU8UWKw64iMeNThkcMpg6RIWdFdFbgpr9msgepv5/C46tBwPuSxOYiKPOjfbILrMYY8g9odI1El5z15Ka/cag65LEjtLVoMONBqoCtzi54tSCHkePNSRn4TMRxmIodbiLCQy1tUTiRlIUNQHuROF8kLzpNXnA95WhAGoiK3NDo0bHN4LrjFl1xeUw+qA0vQXJsOw9wiZPuSDVAHPIgL5XsjqeTtrNabsMNKyO2O08C9oom8YuZam5t7FnLWYf433bT7oLfp+g/B0pkgodg6ywAAAABJRU5ErkJggg=="

    /** The same pixels with a synthetic Display P3 matrix/TRC profile. */
    const val P3_ICC = "iVBORw0KGgoAAAANSUhEUgAAABgAAAAQCAYAAAAMJL+VAAAA/2lDQ1BJQ0MgUHJvZmlsZQAAeJxjYGBiy0nOLWYSYGDIzSspCnJ3UoiIjFJgQAKJycUFjgEBPgw4wbdrDIwg+rIubjU4AWdKanEykP4AxEXJBUUlDAyMKUC2cnlJAYjdAWSLFAEdBWTPAbHTIewNIHYShH0ErCYkyBnIvgFk86VD2C9A7CQI+weIDbULBCTCE1OK8ssSFUpSi0sUXDKLC3ISKxUCjMnwAn5QklpRAqL98hWS8wsqizLTM0p0ILamZVaUlBalMjAgwhwRlgix5nsMDLb7////vwMh5nWQgWGjCQMD1y6EmIYFA4MgLwPDid3JpUVlUOsZmYBews8HANwtTmMix5emAAABb0lEQVR4XoVU0WrDQAzzkYkwzBhljDFKKWWIMcoe831pv/zGXc6p4ybdgx/qWLJkJU2yEyFSJjohIEQvxHMiNBMvQrwKsRPiLRHvmfgQ4lOIvRCHRBwzcRLiSwgK8Z2In0ychfiVJx36rEii6ESBq6K/KEqvF1ep9WPP5gpuDL1aZYG4BUZugEhkZFGEkd+JsAVXRXdRwECePIIqeTnllvL2rPZswUXRZQU8UWKw64iMeNThkcMpg6RIWdFdFbgpr9msgepv5/C46tBwPuSxOYiKPOjfbILrMYY8g9odI1El5z15Ka/cag65LEjtLVoMONBqoCtzi54tSCHkePNSRn4TMRxmIodbiLCQy1tUTiRlIUNQHuROF8kLzpNXnA95WhAGoiK3NDo0bHN4LrjFl1xeUw+qA0vQXJsOw9wiZPuSDVAHPIgL5XsjqeTtrNabsMNKyO2O08C9oom8YuZam5t7FnLWYf433bT7oLfp+g/B0pkgodg6ywAAAABJRU5ErkJggg=="
}

/** (type, offset of the chunk's length field, body length) for every chunk. */
internal fun chunks(png: ByteArray): List<Triple<String, Int, Int>> {
    val out = mutableListOf<Triple<String, Int, Int>>()
    var at = 8
    while (at + 8 <= png.size) {
        val length = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        out += Triple(png.decodeToString(at + 4, at + 8), at, length)
        at += 12 + length
    }
    return out
}

/** A complete chunk with a correct CRC. */
internal fun chunkBytes(type: String, body: ByteArray): ByteArray {
    val typeAndBody = type.encodeToByteArray() + body
    val crc = crc32(typeAndBody, 0, typeAndBody.size)
    fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    return int(body.size) + typeAndBody + int(crc)
}

internal fun insertBeforeIdat(png: ByteArray, type: String, body: ByteArray): ByteArray {
    val at = chunks(png).first { it.first == "IDAT" }.second
    return png.copyOfRange(0, at) + chunkBytes(type, body) + png.copyOfRange(at, png.size)
}

internal fun insertBeforeIend(png: ByteArray, type: String, body: ByteArray): ByteArray {
    val at = chunks(png).first { it.first == "IEND" }.second
    return png.copyOfRange(0, at) + chunkBytes(type, body) + png.copyOfRange(at, png.size)
}

internal fun replaceChunk(png: ByteArray, type: String, body: ByteArray): ByteArray {
    val (_, at, length) = chunks(png).first { it.first == type }
    return png.copyOfRange(0, at) + chunkBytes(type, body) + png.copyOfRange(at + 12 + length, png.size)
}
