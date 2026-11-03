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
    fun smallAccessoriesPairUpUnderTheLeadToFillTheSpaceBesideTheOutfit() {
        // Like Ahoy: jacket, tank and trousers photos with illustrated sneakers, belt, hat, scarf and bag.
        val pieces = photos(OUTERWEAR, TOP, BOTTOM) + listOf(
            MoodPiece(FOOTWEAR, 0.5f),
            MoodPiece(ACCESSORY, 0.3f),
            MoodPiece(ACCESSORY, 0.7f),
            MoodPiece(ACCESSORY, 1.9f),
            MoodPiece(ACCESSORY, 1.2f),
        )
        val layout = moodboardLayout(pieces)
        assertValid(pieces, layout)
        val (coat, top, bottom, shoes) = layout.slots
        val (first, second) = layout.slots.drop(4)
        assertEquals(first.y + first.height, second.y + second.height, 0.0001f, "the first two share a row")
        assertTrue(second.x + second.width <= coat.x + coat.width + 0.0001f, "in the lead's column")
        assertTrue(shoes.y < coat.y + coat.height + 0.1f, "the shoes sit close below the lead")
        assertTrue(layout.slots.drop(4).all { it.height <= 0.3301f })
        assertTrue(top.y < bottom.y)
    }

    @Test
    fun tallAccessoriesShareARowBeneathTheOutfitWhenThatIsShorter() {
        val pieces = photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY)
        val slots = moodboardLayout(pieces).slots
        val outfitBottom = slots.take(4).maxOf { it.y + it.height }
        val accessories = slots.drop(4)
        assertTrue(accessories.all { it.y > outfitBottom && it.height <= 0.3301f })
        assertEquals(1, accessories.map { it.y + it.height }.distinct().size)
    }

    @Test
    fun aThreePieceOutfitLeavesLittleSpaceBelowTheLead() {
        // Blouse and skirt photos with illustrated boots.
        val pieces = listOf(MoodPiece(TOP, 1.5f), MoodPiece(BOTTOM, 1.5f), MoodPiece(FOOTWEAR, 1.16f))
        val layout = moodboardLayout(pieces)
        assertValid(pieces, layout)
        val lead = layout.slots[0]
        assertTrue(layout.height < 1f, "height ${layout.height}")
        assertTrue(layout.height - (lead.y + lead.height) < 0.1f)
    }

    @Test
    fun trimmedShoesAndAccessoriesKeepTheirDrawnSize() {
        val full = moodboardLayout(listOf(MoodPiece(DRESS, 1.5f), MoodPiece(FOOTWEAR, 1.2f), MoodPiece(ACCESSORY, 1.2f))).slots
        val trimmed = moodboardLayout(listOf(MoodPiece(DRESS, 1.5f), MoodPiece(FOOTWEAR, 1.1f, 0.7f), MoodPiece(ACCESSORY, 1.1f, 0.7f))).slots
        assertEquals(full[1].width * 0.7f, trimmed[1].width, 0.0001f)
        assertEquals(full[2].width * 0.7f, trimmed[2].width, 0.0001f)
    }

    @Test
    fun aLeadBesideATallerStackIsCentredOnIt() {
        val layout = moodboardLayout(photos(OUTERWEAR, TOP, BOTTOM))
        val coat = layout.slots[0]
        assertEquals(coat.y, layout.height - (coat.y + coat.height), 0.0001f)
    }

    @Test
    fun theFourPieceDressAndTrenchLookKeepsItsLayout() {
        val slots = moodboardLayout(photos(DRESS, OUTERWEAR, ACCESSORY, FOOTWEAR)).slots
        val expected = listOf(
            MoodSlot(0f, 0f, 0.55f, 0.825f),
            MoodSlot(0.6f, 0.1f, 0.4f, 0.6f),
            MoodSlot(0.33f, 0.875f, 0.22f, 0.33f),
            MoodSlot(0.6f, 0.75f, 0.3f, 0.45f),
        )
        expected.zip(slots).forEach { (want, got) ->
            listOf(want.x to got.x, want.y to got.y, want.width to got.width, want.height to got.height)
                .forEach { (a, b) -> assertEquals(a, b, 0.0001f, "$want vs $got") }
        }
    }

    @Test
    fun smallOutfitsStayShorterThanLargeOnes() {
        val two = moodboardLayout(photos(TOP, BOTTOM)).height
        val eight = moodboardLayout(photos(OUTERWEAR, TOP, BOTTOM, FOOTWEAR, ACCESSORY, ACCESSORY, ACCESSORY, ACCESSORY)).height
        assertTrue(two < 1f)
        assertTrue(two < eight)
    }
}
