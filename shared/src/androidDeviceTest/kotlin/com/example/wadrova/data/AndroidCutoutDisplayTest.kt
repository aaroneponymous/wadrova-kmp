package com.example.wadrova.data

import android.graphics.BitmapFactory
import android.graphics.Color
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
}
