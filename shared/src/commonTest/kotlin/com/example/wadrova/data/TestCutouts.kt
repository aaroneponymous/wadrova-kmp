package com.example.wadrova.data

/**
 * Pixels shaped like a backend cutout: an opaque garment inside a transparent border, a few half-transparent edge
 * pixels, and the photo's color still present under every transparent pixel (as `putalpha` leaves it).
 */
internal fun serverStylePixels(width: Int = 100, height: Int = 200): RgbaImage {
    val pixels = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val i = (y * width + x) * 4
        val inside = x in 10 until width - 10 && y in 10 until height - 10
        val edge = inside && (x == 10 || y == 10)
        pixels[i] = (40 + x).toByte()
        pixels[i + 1] = (90 + y).toByte()
        pixels[i + 2] = (x * y).toByte()
        pixels[i + 3] = when {
            edge && (x + y) % 2 == 0 -> 128.toByte()
            inside -> 255.toByte()
            else -> 0
        }
    }
    return RgbaImage(width, height, pixels)
}

internal fun serverStyleCutout(width: Int = 100, height: Int = 200): ByteArray = encodeRgbaPng(serverStylePixels(width, height))

/** Checks [stored] against [source]: every visible pixel and all alpha identical, no color under alpha 0. */
internal fun assertSanitizedCopyOf(source: RgbaImage, stored: ByteArray) {
    val image = decodeRgbaPng(stored)
    check(image.width == source.width && image.height == source.height) { "size changed" }
    for (i in source.pixels.indices step 4) {
        val alpha = source.pixels[i + 3]
        check(image.pixels[i + 3] == alpha) { "alpha changed at pixel ${i / 4}" }
        if (alpha == 0.toByte()) {
            check(image.pixels[i] == 0.toByte() && image.pixels[i + 1] == 0.toByte() && image.pixels[i + 2] == 0.toByte()) {
                "hidden color kept at pixel ${i / 4}"
            }
        } else {
            for (c in 0 until 3) check(image.pixels[i + c] == source.pixels[i + c]) { "visible color changed at pixel ${i / 4}" }
        }
    }
}
