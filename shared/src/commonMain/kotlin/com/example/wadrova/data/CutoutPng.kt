package com.example.wadrova.data

/** Broad reasons a cutout can't be kept; safe to report, since none carries image content. */
enum class CutoutProblem {
    /** A valid PNG using something the codec deliberately doesn't keep (bit depth, color type, color declarations). */
    UNSUPPORTED_FORMAT,

    /** Not a well-formed PNG: bad signature, CRC, structure, profile or compressed data. */
    INVALID_PNG,

    /** A well-formed PNG that doesn't match what the server described (its size). */
    INTEGRITY,

    /** Decoding or encoding failed unexpectedly. */
    CODEC_ERROR,

    /** The sanitized file couldn't be written. */
    STORAGE_ERROR,
}

/** A cutout that can't be kept as a transparent image. The garment is still saved with its photo. */
class CutoutFormatException(val problem: CutoutProblem, message: String) : Exception(message)

/**
 * How the pixel values are to be read. [Untagged] is plain sRGB by the PNG convention. An [Icc] profile is kept
 * exactly as declared, so a non-sRGB profile (Display P3, for instance) is never reinterpreted as sRGB.
 */
internal sealed interface PngColorSpace {
    data object Untagged : PngColorSpace

    data class Srgb(val renderingIntent: Int) : PngColorSpace

    class Icc(val profile: ByteArray) : PngColorSpace
}

/** 8-bit RGBA pixels, straight (not premultiplied) alpha, row by row, with the color space they're declared in. */
internal class RgbaImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val colorSpace: PngColorSpace = PngColorSpace.Untagged,
) {
    init {
        require(pixels.size == width * height * 4)
    }
}

/** Large enough for any cutout of a 1600 px upload, small enough to decode safely on a phone. */
internal const val MAX_CUTOUT_PIXELS = 4096 * 4096
private const val MAX_CUTOUT_SIDE = 8192

/** Real profiles are a few hundred bytes to a few KB; these bounds stop a hostile one from costing memory. */
internal const val MAX_ICC_COMPRESSED_BYTES = 256 * 1024
internal const val MAX_ICC_PROFILE_BYTES = 1024 * 1024

/**
 * The verified server cutout as it's kept on the device: the same visible pixels and the same alpha, with RGB set to 0
 * wherever alpha is 0, re-encoded as a lossless PNG. The server's cutout keeps the photo's colors under its
 * transparent pixels; those would otherwise sit, hidden but present, in the stored file.
 *
 * Decoding and encoding are done here rather than by the platform so pixels are never premultiplied, converted or
 * color-managed, and both platforms produce the same bytes. Only 8-bit RGBA, not interlaced, is accepted. A color
 * declaration is kept, never applied: a valid embedded ICC profile (the backend's cutouts carry the upload's
 * profile) or an sRGB chunk is written back unchanged in meaning, so the pixels keep their interpretation. Text,
 * EXIF and other ancillary chunks are dropped. Anything malformed, conflicting or unsupported throws
 * [CutoutFormatException].
 */
fun sanitizedCutoutPng(source: ByteArray, expectedWidth: Int? = null, expectedHeight: Int? = null): ByteArray {
    val image = decodeRgbaPng(source)
    if ((expectedWidth != null && expectedWidth != image.width) || (expectedHeight != null && expectedHeight != image.height)) {
        throw CutoutFormatException(CutoutProblem.INTEGRITY, "The cutout's size doesn't match the server's description.")
    }
    return encodeRgbaPng(clearHiddenColor(image))
}

internal fun clearHiddenColor(image: RgbaImage): RgbaImage {
    val out = image.pixels.copyOf()
    for (i in out.indices step 4) {
        if (out[i + 3] == 0.toByte()) {
            out[i] = 0
            out[i + 1] = 0
            out[i + 2] = 0
        }
    }
    return RgbaImage(image.width, image.height, out, image.colorSpace)
}

private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun invalid(message: String) = CutoutFormatException(CutoutProblem.INVALID_PNG, message)

private fun unsupported(message: String) = CutoutFormatException(CutoutProblem.UNSUPPORTED_FORMAT, message)

internal fun decodeRgbaPng(bytes: ByteArray): RgbaImage {
    if (bytes.size < SIGNATURE.size || !bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
        throw invalid("The cutout isn't a PNG.")
    }
    var offset = SIGNATURE.size
    var width = 0
    var height = 0
    var sawHeader = false
    var sawData = false
    var ended = false
    var icc: ByteArray? = null
    var srgbIntent: Int? = null
    var fallbackColorChunks = 0
    val data = ByteChunks()
    while (offset < bytes.size && !ended) {
        if (offset + 12 > bytes.size) throw invalid("The cutout is truncated.")
        val length = readInt(bytes, offset)
        if (length < 0 || offset + 12L + length > bytes.size) throw invalid("The cutout is truncated.")
        val type = bytes.decodeToString(offset + 4, offset + 8)
        val bodyStart = offset + 8
        val storedCrc = readInt(bytes, bodyStart + length)
        if (crc32(bytes, offset + 4, length + 4) != storedCrc) throw invalid("The cutout is corrupt.")
        if (!sawHeader && type != "IHDR") throw invalid("The cutout has no header.")
        when (type) {
            "IHDR" -> {
                if (sawHeader || length != 13) throw invalid("The cutout's header is invalid.")
                sawHeader = true
                width = readInt(bytes, bodyStart)
                height = readInt(bytes, bodyStart + 4)
                val bitDepth = bytes[bodyStart + 8].toInt()
                val colorType = bytes[bodyStart + 9].toInt()
                val compression = bytes[bodyStart + 10].toInt()
                val filter = bytes[bodyStart + 11].toInt()
                val interlace = bytes[bodyStart + 12].toInt()
                if (width <= 0 || height <= 0 || width > MAX_CUTOUT_SIDE || height > MAX_CUTOUT_SIDE ||
                    width.toLong() * height > MAX_CUTOUT_PIXELS
                ) {
                    throw unsupported("The cutout's size isn't supported.")
                }
                if (compression != 0 || filter != 0) throw invalid("The cutout's header is invalid.")
                if (bitDepth != 8 || colorType != 6 || interlace != 0) throw unsupported("Only 8-bit RGBA cutouts are supported.")
            }
            "IDAT" -> {
                sawData = true
                data.add(bytes, bodyStart, length)
            }
            "IEND" -> ended = true
            "iCCP" -> {
                if (icc != null) throw invalid("The cutout declares more than one color profile.")
                if (sawData) throw invalid("The cutout's color profile comes after its image data.")
                icc = readIccChunk(bytes, bodyStart, length)
            }
            "sRGB" -> {
                if (srgbIntent != null) throw invalid("The cutout declares sRGB more than once.")
                if (sawData || length != 1) throw invalid("The cutout's sRGB declaration is invalid.")
                srgbIntent = (bytes[bodyStart].toInt() and 0xFF).also { if (it > 3) throw invalid("The cutout's sRGB declaration is invalid.") }
            }
            // Fallbacks for decoders without color management; ignored when a profile or sRGB is declared (PNG spec).
            "gAMA", "cHRM" -> fallbackColorChunks++
            // Newer color and HDR declarations take precedence over a profile; dropping them would change the colors.
            "cICP", "mDCv", "cLLi" -> throw unsupported("The cutout uses a color declaration that isn't supported.")
            else -> if (type[0].isUpperCase() && type != "PLTE") throw unsupported("The cutout uses an unsupported PNG feature.")
        }
        offset = bodyStart + length + 4
    }
    if (!ended || !sawHeader) throw invalid("The cutout is truncated.")
    if (icc != null && srgbIntent != null) throw invalid("The cutout declares both a color profile and sRGB.")
    // gAMA or cHRM alone would be the only description of the colors, and they aren't kept.
    if (fallbackColorChunks > 0 && icc == null && srgbIntent == null) {
        throw unsupported("The cutout describes its colors with gamma or chromaticity only, which isn't supported.")
    }
    val stride = width * 4
    val raw = try {
        zlibInflate(data.toByteArray(), (stride + 1) * height)
    } catch (e: CutoutFormatException) {
        throw e
    } catch (e: Exception) {
        throw invalid("The cutout's image data is corrupt.")
    }
    val colorSpace = when {
        icc != null -> PngColorSpace.Icc(icc)
        srgbIntent != null -> PngColorSpace.Srgb(srgbIntent)
        else -> PngColorSpace.Untagged
    }
    return RgbaImage(width, height, unfilter(raw, width, height), colorSpace)
}

/** An iCCP body: a 1–79 byte Latin-1 name, a zero, compression method 0, then a zlib-compressed profile. */
private fun readIccChunk(bytes: ByteArray, start: Int, length: Int): ByteArray {
    var nameEnd = start
    while (nameEnd < start + length && bytes[nameEnd] != 0.toByte()) nameEnd++
    val nameLength = nameEnd - start
    if (nameLength !in 1..79 || nameEnd + 2 > start + length) throw invalid("The cutout's color profile is invalid.")
    if (bytes[nameEnd + 1] != 0.toByte()) throw invalid("The cutout's color profile uses an unknown compression.")
    val compressed = bytes.copyOfRange(nameEnd + 2, start + length)
    if (compressed.isEmpty() || compressed.size > MAX_ICC_COMPRESSED_BYTES) throw invalid("The cutout's color profile is too large.")
    val profile = try {
        zlibInflateAtMost(compressed, MAX_ICC_PROFILE_BYTES)
    } catch (e: CutoutFormatException) {
        throw e
    } catch (e: Exception) {
        throw invalid("The cutout's color profile is corrupt.")
    }
    validateIccProfile(profile)
    return profile
}

/**
 * Structural checks only (ICC.1 header and tag table): the declared size matches, the `acsp` signature is present,
 * the data color space is RGB (required for an RGBA PNG) and every tag lies inside the profile. The profile's
 * meaning isn't interpreted; it's kept for the decoder that displays the image.
 */
internal fun validateIccProfile(profile: ByteArray) {
    if (profile.size < 132) throw invalid("The cutout's color profile is truncated.")
    if (readInt(profile, 0).toLong() and 0xFFFFFFFFL != profile.size.toLong()) throw invalid("The cutout's color profile is truncated.")
    if (profile.decodeToString(36, 40) != "acsp") throw invalid("The cutout's color profile is invalid.")
    if (profile.decodeToString(16, 20) != "RGB ") throw unsupported("The cutout's color profile isn't an RGB profile.")
    val tags = readInt(profile, 128).toLong() and 0xFFFFFFFFL
    if (132 + tags * 12 > profile.size) throw invalid("The cutout's color profile is truncated.")
    for (i in 0 until tags.toInt()) {
        val entry = 132 + i * 12
        val tagOffset = readInt(profile, entry + 4).toLong() and 0xFFFFFFFFL
        val tagSize = readInt(profile, entry + 8).toLong() and 0xFFFFFFFFL
        if (tagOffset + tagSize > profile.size) throw invalid("The cutout's color profile is truncated.")
    }
}

private fun unfilter(raw: ByteArray, width: Int, height: Int): ByteArray {
    val stride = width * 4
    val out = ByteArray(stride * height)
    for (y in 0 until height) {
        val filter = raw[y * (stride + 1)].toInt()
        val src = y * (stride + 1) + 1
        val dst = y * stride
        val prev = dst - stride
        for (x in 0 until stride) {
            val value = raw[src + x].toInt() and 0xFF
            val a = if (x >= 4) out[dst + x - 4].toInt() and 0xFF else 0
            val b = if (y > 0) out[prev + x].toInt() and 0xFF else 0
            val c = if (x >= 4 && y > 0) out[prev + x - 4].toInt() and 0xFF else 0
            val predicted = when (filter) {
                0 -> 0
                1 -> a
                2 -> b
                3 -> (a + b) ushr 1
                4 -> paeth(a, b, c)
                else -> throw invalid("The cutout's image data is corrupt.")
            }
            out[dst + x] = (value + predicted).toByte()
        }
    }
    return out
}

/** [onlyFilter] forces one PNG filter type on every row; tests use it to cover each decoder path. */
internal fun encodeRgbaPng(image: RgbaImage, onlyFilter: Int? = null): ByteArray {
    val stride = image.width * 4
    val filtered = ByteArray((stride + 1) * image.height)
    val candidate = ByteArray(stride)
    val best = ByteArray(stride)
    val px = image.pixels
    for (y in 0 until image.height) {
        val row = y * stride
        var bestFilter = 0
        var bestScore = Long.MAX_VALUE
        // The usual heuristic: the filter whose output has the smallest sum of absolute signed bytes compresses best.
        for (filter in onlyFilter?.let { it..it } ?: 0..4) {
            var score = 0L
            for (x in 0 until stride) {
                val value = px[row + x].toInt() and 0xFF
                val a = if (x >= 4) px[row + x - 4].toInt() and 0xFF else 0
                val b = if (y > 0) px[row - stride + x].toInt() and 0xFF else 0
                val c = if (x >= 4 && y > 0) px[row - stride + x - 4].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) ushr 1
                    else -> paeth(a, b, c)
                }
                val out = (value - predicted).toByte()
                candidate[x] = out
                score += kotlin.math.abs(out.toInt())
            }
            if (score < bestScore) {
                bestScore = score
                bestFilter = filter
                candidate.copyInto(best)
            }
        }
        filtered[y * (stride + 1)] = bestFilter.toByte()
        best.copyInto(filtered, y * (stride + 1) + 1)
    }
    val header = ByteArray(13)
    writeInt(header, 0, image.width)
    writeInt(header, 4, image.height)
    header[8] = 8 // bit depth
    header[9] = 6 // RGBA
    val out = ByteChunks()
    out.add(SIGNATURE, 0, SIGNATURE.size)
    out.chunk("IHDR", header)
    when (val space = image.colorSpace) {
        PngColorSpace.Untagged -> Unit
        is PngColorSpace.Srgb -> out.chunk("sRGB", byteArrayOf(space.renderingIntent.toByte()))
        // A fixed name, so no text from the source is copied; the profile itself is kept byte for byte.
        is PngColorSpace.Icc -> out.chunk("iCCP", ICC_NAME + byteArrayOf(0, 0) + zlibDeflate(space.profile))
    }
    out.chunk("IDAT", zlibDeflate(filtered))
    out.chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

private val ICC_NAME = "ICC profile".encodeToByteArray()

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return when {
        pa <= pb && pa <= pc -> a
        pb <= pc -> b
        else -> c
    }
}

private fun readInt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
    bytes[at] = (value ushr 24).toByte()
    bytes[at + 1] = (value ushr 16).toByte()
    bytes[at + 2] = (value ushr 8).toByte()
    bytes[at + 3] = value.toByte()
}

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

internal fun crc32(bytes: ByteArray, from: Int, length: Int): Int {
    var c = -1
    for (i in from until from + length) c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}

private class ByteChunks {
    private val parts = mutableListOf<ByteArray>()
    private var size = 0

    fun add(bytes: ByteArray, from: Int, length: Int) {
        parts += bytes.copyOfRange(from, from + length)
        size += length
    }

    fun chunk(type: String, body: ByteArray) {
        val head = ByteArray(8)
        writeInt(head, 0, body.size)
        type.encodeToByteArray().copyInto(head, 4)
        val crcInput = head.copyOfRange(4, 8) + body
        val crc = ByteArray(4)
        writeInt(crc, 0, crc32(crcInput, 0, crcInput.size))
        add(head, 0, 8)
        add(body, 0, body.size)
        add(crc, 0, 4)
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(size)
        var at = 0
        parts.forEach { it.copyInto(out, at); at += it.size }
        return out
    }
}

/** zlib (RFC 1950) inflate that must produce exactly [expectedSize] bytes; anything else throws. */
internal expect fun zlibInflate(data: ByteArray, expectedSize: Int): ByteArray

/** zlib (RFC 1950) inflate of a complete stream producing at most [maxSize] bytes; larger or truncated input throws. */
internal expect fun zlibInflateAtMost(data: ByteArray, maxSize: Int): ByteArray

/** zlib (RFC 1950) deflate at the default level. */
internal expect fun zlibDeflate(data: ByteArray): ByteArray
