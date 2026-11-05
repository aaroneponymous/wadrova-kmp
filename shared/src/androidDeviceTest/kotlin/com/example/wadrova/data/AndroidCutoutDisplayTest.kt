package com.example.wadrova.data

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import com.example.wadrova.ui.VisibleBounds
import com.example.wadrova.ui.computeVisibleBounds
import org.jetbrains.compose.resources.decodeToImageBitmap

/** The stored cutout through Android's own decoders: what the app actually draws. */
class AndroidCutoutDisplayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidGeneratedImageStore(context)
    private val root = File(context.noBackupFilesDir, "generated_images")
    private val source = serverStylePixels()
    private val stored = sanitizedCutoutPng(encodeRgbaPng(source), source.width, source.height)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun sourcePixel(x: Int, y: Int): IntArray {
        val i = (y * source.width + x) * 4
        return IntArray(4) { source.pixels[i + it].toInt() and 0xFF }
    }

    @Test
    fun theStoreKeepsTheCutoutByteForByteInTheBackupExcludedFolder() = runTest {
        val id = store.save(stored)
        assertContentEquals(stored, store.read(id))
        assertTrue(File(root, "$id.png").isFile)
        assertTrue(root.path.startsWith(context.noBackupFilesDir.path))
    }

    @Test
    fun bitmapFactoryDecodesEveryPixelAndAlphaExactly() {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = BitmapFactory.decodeByteArray(stored, 0, stored.size, options)
        assertEquals(source.width, bitmap.width)
        assertEquals(source.height, bitmap.height)
        assertTrue(bitmap.hasAlpha())
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val expected = sourcePixel(x, y)
            val p = bitmap.getPixel(x, y)
            val actual = intArrayOf(Color.red(p), Color.green(p), Color.blue(p), Color.alpha(p))
            if (expected[3] == 0) {
                assertContentEquals(intArrayOf(0, 0, 0, 0), actual, "hidden pixel $x,$y")
            } else {
                assertContentEquals(expected, actual, "pixel $x,$y")
            }
        }
    }

    @Test
    fun theDisplayDecoderKeepsTransparencyAndOpaqueColorsExactly() {
        // decodeToImageBitmap is what PhotoBitmapLoader uses; it decodes premultiplied, so half-transparent pixels can
        // round slightly when read back, while opaque and fully transparent pixels must be exact.
        val pixels = stored.decodeToImageBitmap().toPixelMap()
        assertEquals(source.width, pixels.width)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val expected = sourcePixel(x, y)
            val c = pixels[x, y]
            val actual = intArrayOf(Math.round(c.red * 255), Math.round(c.green * 255), Math.round(c.blue * 255), Math.round(c.alpha * 255))
            assertEquals(expected[3], actual[3], "alpha at $x,$y")
            when (expected[3]) {
                0 -> Unit
                255 -> assertContentEquals(expected, actual, "pixel $x,$y")
                else -> for (ch in 0 until 3) assertTrue(kotlin.math.abs(expected[ch] - actual[ch]) <= 2, "edge pixel $x,$y channel $ch")
            }
        }
    }

    /** The bitmap's own RGBA bytes, unconverted (getPixel would convert to sRGB). */
    private fun rawRgba(png: ByteArray): Pair<ColorSpace?, ByteArray> {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inPremultiplied = false })
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return bitmap.colorSpace to buffer.array()
    }

    /** The 24x16 ICC fixtures' pixels (see CutoutIccTest), sanitized: hidden pixels all zero. */
    private fun expectedIccFixturePixels(): ByteArray = ByteArray(24 * 16 * 4).also { out ->
        for (y in 0 until 16) for (x in 0 until 24) {
            val a = when {
                (x + y) % 5 == 0 -> 0
                (x * y) % 7 == 3 -> 128
                else -> 255
            }
            val i = (y * 24 + x) * 4
            if (a != 0) {
                out[i] = ((x * 37 + y * 11) % 256).toByte()
                out[i + 1] = ((x * 5 + y * 53 + 17) % 256).toByte()
                out[i + 2] = ((x * x + y * 7) % 256).toByte()
                out[i + 3] = a.toByte()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun anIccCutoutDecodesAsSrgbWithEveryPixelExact() {
        val stored = sanitizedCutoutPng(Base64.decode(IccFixtures.SRGB_ICC), 24, 16)
        val (space, pixels) = rawRgba(stored)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), space)
        assertContentEquals(expectedIccFixturePixels(), pixels)
        // The display decoder too: alpha and opaque colors exact.
        val map = stored.decodeToImageBitmap().toPixelMap()
        val expected = expectedIccFixturePixels()
        for (y in 0 until 16) for (x in 0 until 24) {
            val i = (y * 24 + x) * 4
            val c = map[x, y]
            assertEquals(expected[i + 3].toInt() and 0xFF, Math.round(c.alpha * 255), "alpha at $x,$y")
            if (expected[i + 3] == 255.toByte()) {
                assertEquals(expected[i].toInt() and 0xFF, Math.round(c.red * 255), "red at $x,$y")
                assertEquals(expected[i + 1].toInt() and 0xFF, Math.round(c.green * 255), "green at $x,$y")
                assertEquals(expected[i + 2].toInt() and 0xFF, Math.round(c.blue * 255), "blue at $x,$y")
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aP3CutoutKeepsItsProfileSoAndroidDoesNotReadItAsSrgb() {
        val stored = sanitizedCutoutPng(Base64.decode(IccFixtures.P3_ICC), 24, 16)
        val (space, pixels) = rawRgba(stored)
        assertTrue(space != null && space != ColorSpace.get(ColorSpace.Named.SRGB), "decoded as $space")
        // Decoded in its own color space, so the stored values arrive unchanged.
        assertContentEquals(expectedIccFixturePixels(), pixels)
    }

    @Test
    fun studioCleanReadsTheVisibleBoundsWithoutChangingTheStoredCutout() = runTest {
        val id = store.save(stored)
        val bitmap = store.read(id)!!.decodeToImageBitmap()
        // serverStylePixels: opaque garment from 10..89 x 10..189 inside a transparent border.
        assertEquals(VisibleBounds(10, 10, 90, 190), computeVisibleBounds(bitmap))
        assertContentEquals(stored, store.read(id))
    }
}
