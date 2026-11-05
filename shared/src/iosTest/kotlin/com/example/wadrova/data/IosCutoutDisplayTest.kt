@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.wadrova.data

import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.CoreGraphics.CGColorSpaceCopyICCData
import platform.CoreGraphics.CGColorSpaceCopyName
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRetain
import platform.Foundation.CFBridgingRelease
import platform.CoreGraphics.CGImageGetAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData

/** The stored cutout through iOS's own decoders: ImageIO, and Skia as used by the app's image loader. */
class IosCutoutDisplayTest {
    private val folder = "generated_images_cutout_test"
    private val store = IosGeneratedImageStore(folder)
    private val source = serverStylePixels()
    private val stored = sanitizedCutoutPng(encodeRgbaPng(source), source.width, source.height)
    private val root: String = NSFileManager.defaultManager
        .URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!.path + "/" + folder

    @BeforeTest
    fun setUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun sourcePixel(x: Int, y: Int): IntArray {
        val i = (y * source.width + x) * 4
        return IntArray(4) { source.pixels[i + it].toInt() and 0xFF }
    }

    @Test
    fun theStoreKeepsTheCutoutByteForByteInABackupExcludedFolder() = runTest {
        val id = store.save(stored)
        assertContentEquals(stored, store.read(id))
        val excluded = NSURL.fileURLWithPath(root).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
        assertEquals(true, (excluded?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue)
    }

    @Test
    fun imageIoDecodesTheCutoutWithItsAlpha() {
        val data = stored.usePinned { NSData.create(bytes = it.addressOf(0), length = stored.size.toULong()) }
        val cfData = CFBridgingRetain(data) as CFDataRef
        val imageSource = assertNotNull(CGImageSourceCreateWithData(cfData, null))
        val image = assertNotNull(CGImageSourceCreateImageAtIndex(imageSource, 0u, null))
        try {
            assertEquals(source.width.toULong(), CGImageGetWidth(image))
            assertEquals(source.height.toULong(), CGImageGetHeight(image))
            val alpha = CGImageGetAlphaInfo(image)
            assertTrue(
                alpha == CGImageAlphaInfo.kCGImageAlphaLast || alpha == CGImageAlphaInfo.kCGImageAlphaPremultipliedLast ||
                    alpha == CGImageAlphaInfo.kCGImageAlphaFirst || alpha == CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst,
                "alpha info $alpha",
            )
        } finally {
            CGImageRelease(image)
            CFRelease(imageSource)
            CFRelease(cfData)
        }
    }

    @Test
    fun theDisplayDecoderKeepsTransparencyAndOpaqueColorsExactly() {
        // Skia decodes premultiplied, so half-transparent pixels can round slightly when read back; opaque and fully
        // transparent pixels must be exact.
        val pixels = stored.decodeToImageBitmap().toPixelMap()
        assertEquals(source.width, pixels.width)
        assertEquals(source.height, pixels.height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val expected = sourcePixel(x, y)
            val c = pixels[x, y]
            val actual = intArrayOf((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt(), (c.alpha * 255).roundToInt())
            assertEquals(expected[3], actual[3], "alpha at $x,$y")
            when (expected[3]) {
                0 -> Unit
                255 -> assertContentEquals(expected, actual, "pixel $x,$y")
                else -> for (ch in 0 until 3) assertTrue(abs(expected[ch] - actual[ch]) <= 2, "edge pixel $x,$y channel $ch")
            }
        }
    }

    /** ImageIO's color space for [png]: whether it's sRGB by name, and the length of its ICC data (0 if none). */
    private fun imageIoColorSpace(png: ByteArray): Pair<Boolean, Long> {
        val data = png.usePinned { NSData.create(bytes = it.addressOf(0), length = png.size.toULong()) }
        val cfData = CFBridgingRetain(data) as CFDataRef
        val imageSource = assertNotNull(CGImageSourceCreateWithData(cfData, null))
        val image = assertNotNull(CGImageSourceCreateImageAtIndex(imageSource, 0u, null))
        try {
            val space = CGImageGetColorSpace(image)
            val name = CGColorSpaceCopyName(space)?.let { CFBridgingRelease(it) as? String }
            val srgbName = CFBridgingRelease(CFRetain(kCGColorSpaceSRGB)) as? String
            val isSrgb = name != null && name == srgbName
            val icc = CGColorSpaceCopyICCData(space)
            val length = icc?.let { CFDataGetLength(it) } ?: 0L
            icc?.let { CFRelease(it) }
            return isSrgb to length
        } finally {
            CGImageRelease(image)
            CFRelease(imageSource)
            CFRelease(cfData)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun anIccCutoutDisplaysWithAlphaAndOpaqueColorsExact() {
        val stored = sanitizedCutoutPng(Base64.decode(IccFixtures.SRGB_ICC), 24, 16)
        val pixels = stored.decodeToImageBitmap().toPixelMap()
        for (y in 0 until 16) for (x in 0 until 24) {
            val a = when {
                (x + y) % 5 == 0 -> 0
                (x * y) % 7 == 3 -> 128
                else -> 255
            }
            val c = pixels[x, y]
            assertEquals(a, (c.alpha * 255).roundToInt(), "alpha at $x,$y")
            if (a == 255) {
                assertContentEquals(
                    intArrayOf((x * 37 + y * 11) % 256, (x * 5 + y * 53 + 17) % 256, (x * x + y * 7) % 256),
                    intArrayOf((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt()),
                    "pixel $x,$y",
                )
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun imageIoSeesTheEmbeddedProfileAndDoesNotTreatP3AsSrgb() {
        val (srgbIsSrgb, srgbIcc) = imageIoColorSpace(sanitizedCutoutPng(Base64.decode(IccFixtures.SRGB_ICC), 24, 16))
        assertTrue(srgbIsSrgb || srgbIcc > 0, "the sRGB profile is seen")
        val (p3IsSrgb, p3Icc) = imageIoColorSpace(sanitizedCutoutPng(Base64.decode(IccFixtures.P3_ICC), 24, 16))
        assertTrue(!p3IsSrgb && p3Icc > 0, "the P3 profile is kept and not read as sRGB")
    }
}
