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
import platform.CoreGraphics.CGImageAlphaInfo
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
}
