package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.GarmentCategory.ACCESSORY
import com.example.wadrova.domain.GarmentCategory.BOTTOM
import com.example.wadrova.domain.GarmentCategory.DRESS
import com.example.wadrova.domain.GarmentCategory.FOOTWEAR
import com.example.wadrova.domain.GarmentCategory.OUTERWEAR
import com.example.wadrova.domain.GarmentCategory.TOP
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoodboardLayoutTest {
    private fun photos(vararg categories: GarmentCategory) = categories.map { MoodPiece(it, 1.5f) }

    private fun area(slot: MoodSlot) = slot.width * slot.height

    private fun assertValid(pieces: List<MoodPiece>, layout: MoodLayout) {
        assertEquals(pieces.size, layout.slots.size)
        layout.slots.forEachIndexed { i, slot ->
            assertTrue(slot.x >= -0.0001f && slot.x + slot.width <= 1.0001f, "piece $i fits the width: $slot")
            assertTrue(slot.y >= -0.0001f &&slot.y + slot.height <= layout.height + 0.0001f, "piece $i fits the height: $slot")
            assertTrue(abs(slot.height / slot.width - pieces[i].aspect) < 0.0001f, "piece $i keeps its ratio")
            layout.slots.forEachIndexed { j, other ->
                if (i < j) {
                    val apart = slot.x + slot.width <= other.x + 0.0001f || other.x + other.width <= slot.x + 0.0001f ||
                        slot.y + slot.height <= other.y + 0.0001f || other.y + other.height <= slot.y + 0.0001f
                    assertTrue(apart, "pieces $i and $j don't overlap: $slot $other")
                }
            }
        }
    }

    @Test
    fun emptyOutfitHasNoHeight() {
        assertEquals(MoodLayout(emptyList(), 0f), moodboardLayout(emptyList()))
    }

    @Test
    fun aSinglePieceIsCentredAndCompact() {
        val pieces = photos(DRESS)
        val layout = moodboardLayout(pieces)
        assertValid(pieces, layout)
        val slot = layout.slots.single()
        assertEquals(1f, slot.x * 2 + slot.width, 0.0001f)
        assertTrue(layout.height < 1f)
    }

    @Test
    fun everyPieceOfEveryOutfitSizeIsPlacedWithoutOverlapOrDistortion() {
        val outfits = listOf(
            photos(TOP, BOTTOM),
            photos(TOP, BOTTOM, FOOTWEAR),
            photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY),
            photos(OUTERWEAR, DRESS, FOOTWEAR, ACCESSORY),
            photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY),
            photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY),
            photos(ACCESSORY, ACCESSORY, ACCESSORY),
            photos(FOOTWEAR, ACCESSORY),
            listOf(MoodPiece(TOP, 1.5f), MoodPiece(BOTTOM, 1.2f), MoodPiece(ACCESSORY, 1.2f), MoodPiece(ACCESSORY, 1.5f)),
        )
        outfits.forEach { assertValid(it, moodboardLayout(it)) }
    }

    @Test
    fun outerwearLeadsALayeredOutfitAndSupportingPiecesAreSmaller() {
        val pieces = photos(ACCESSORY, FOOTWEAR, BOTTOM, TOP, OUTERWEAR)
        val (accessory, shoes, bottom, top, coat) = moodboardLayout(pieces).slots
        assertTrue(area(coat) > area(top))
        assertTrue(area(top) > area(shoes))
        assertTrue(area(bottom) > area(shoes))
        assertTrue(area(shoes) > area(accessory))
        assertTrue(top.y < bottom.y, "the top sits above the bottom")
    }

    @Test
    fun aDressLeadsEvenWithACoat() {
        val (coat, dress) = moodboardLayout(photos(OUTERWEAR, DRESS)).slots
        assertTrue(area(dress) > area(coat))
    }

    @Test
    fun withoutOuterwearTheTopLeadsAndStillSitsAboveTheBottom() {
        val (top, bottom) = moodboardLayout(photos(TOP, BOTTOM)).slots
        assertTrue(area(top) > area(bottom))
        assertTrue(top.y < bottom.y)
    }

    @Test
    fun severalAccessoriesShareARowBeneathTheOutfit() {
        val pieces = photos(TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY)
        val slots = moodboardLayout(pieces).slots
        val outfitBottom = slots.take(3).maxOf { it.y + it.height }
        val accessories = slots.drop(3)
        assertTrue(accessories.all { it.y > outfitBottom })
        assertEquals(1, accessories.map { it.y + it.height }.distinct().size)
    }

    @Test
    fun smallOutfitsStayShorterThanLargeOnes() {
        val two = moodboardLayout(photos(TOP, BOTTOM)).height
        val eight = moodboardLayout(photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY)).height
        assertTrue(two < 1f)
        assertTrue(two < eight)
    }
}
