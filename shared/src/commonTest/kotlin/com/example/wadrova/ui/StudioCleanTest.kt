package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentCategory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioCleanTest {
    private fun argb(width: Int, height: Int, visible: (Int, Int) -> Boolean) =
        IntArray(width * height) { i -> if (visible(i % width, i / width)) 0xFF336699.toInt() else 0x00FFFFFF }

    @Test
    fun visibleBoundsCoverEveryNonTransparentPixelAndNothingElse() {
        assertEquals(VisibleBounds(3, 2, 8, 6), visibleBounds(10, 10, argb(10, 10) { x, y -> x in 3..7 && y in 2..5 }))
        // A detached speck (a chain link, a fringe strand) is part of the garment and widens the bounds.
        assertEquals(VisibleBounds(0, 2, 8, 10), visibleBounds(10, 10, argb(10, 10) { x, y -> (x in 3..7 && y in 2..5) || (x == 0 && y == 9) }))
        assertEquals(VisibleBounds(0, 0, 4, 4), visibleBounds(4, 4, argb(4, 4) { _, _ -> true }))
        assertNull(visibleBounds(4, 4, argb(4, 4) { _, _ -> false }))
        // Faint alpha still counts as visible: nothing is trimmed away.
        assertEquals(VisibleBounds(1, 1, 2, 2), visibleBounds(3, 3, IntArray(9).also { it[4] = 0x01000000 }))
    }

    private fun assertFitsUncroppedAndUndistorted(frameW: Float, frameH: Float, bounds: VisibleBounds, category: GarmentCategory) {
        val layout = cleanLayout(frameW, frameH, bounds, category)
        val pad = minOf(frameW, frameH) * CLEAN_PADDING
        val eps = 0.01f
        assertTrue(layout.garmentLeft >= pad - eps && layout.garmentLeft + layout.garmentWidth <= frameW - pad + eps, "inside horizontally: $layout")
        assertTrue(layout.garmentTop >= pad - eps && layout.garmentTop + layout.garmentHeight <= frameH - pad + eps, "inside vertically: $layout")
        val sourceAspect = bounds.width.toFloat() / bounds.height
        assertTrue(abs(layout.garmentWidth / layout.garmentHeight - sourceAspect) < 0.001f * sourceAspect + 0.001f, "aspect kept: $layout")
        assertEquals(frameW / 2, layout.garmentLeft + layout.garmentWidth / 2, 0.01f)
        assertTrue(layout.scale <= CLEAN_MAX_UPSCALE)
        assertTrue(layout.shadowLeft >= 0f && layout.shadowLeft + layout.shadowWidth <= frameW + eps)
        assertTrue(layout.shadowTop + layout.shadowHeight <= frameH + eps)
    }

    @Test
    fun everySilhouetteFitsWholeKeepsItsProportionsAndIsCentered() {
        // The real CC0 cutouts' sizes, plus extremes: a scarf strand, a belt, a square.
        val sizes = listOf(337 to 394, 439 to 578, 85 to 117, 71 to 274, 3 to 600, 900 to 40, 500 to 500, 1600 to 1600)
        for ((w, h) in sizes) for (category in GarmentCategory.entries) {
            assertFitsUncroppedAndUndistorted(1080f, 1350f, VisibleBounds(0, 0, w, h), category)
            assertFitsUncroppedAndUndistorted(400f, 500f, VisibleBounds(5, 7, 5 + w, 7 + h), category)
        }
    }

    @Test
    fun aSmallCutoutIsShownSmallerRatherThanEnlargedIntoBlur() {
        val shoe = cleanLayout(1080f, 1350f, VisibleBounds(0, 0, 85, 117), GarmentCategory.FOOTWEAR)
        assertEquals(CLEAN_MAX_UPSCALE, shoe.scale)
        assertEquals(85 * CLEAN_MAX_UPSCALE, shoe.garmentWidth, 0.01f)
        // A large cutout is scaled down to fit and never past the cap.
        val coat = cleanLayout(1080f, 1350f, VisibleBounds(0, 0, 1439, 1578), GarmentCategory.OUTERWEAR)
        assertTrue(coat.scale < 1f)
    }

    @Test
    fun categoriesReadAtABelievableRelativeSize() {
        val bounds = VisibleBounds(0, 0, 400, 500)
        val coat = cleanLayout(1080f, 1350f, bounds, GarmentCategory.OUTERWEAR)
        val bag = cleanLayout(1080f, 1350f, bounds, GarmentCategory.ACCESSORY)
        val shoe = cleanLayout(1080f, 1350f, bounds, GarmentCategory.FOOTWEAR)
        assertTrue(coat.garmentHeight > shoe.garmentHeight && coat.garmentHeight > bag.garmentHeight)
    }

    @Test
    fun theLayoutIsDeterministic() {
        val bounds = VisibleBounds(4, 9, 341, 403)
        assertEquals(cleanLayout(1080f, 1350f, bounds, GarmentCategory.BOTTOM), cleanLayout(1080f, 1350f, bounds, GarmentCategory.BOTTOM))
        assertEquals(cleanLayout(1080f, 1350f, bounds, GarmentCategory.BOTTOM).hashCode(), cleanLayout(1080f, 1350f, bounds, GarmentCategory.BOTTOM).hashCode())
    }
}
