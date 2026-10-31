package com.example.wadrova.domain

import com.example.wadrova.domain.GarmentCategory.ACCESSORY
import com.example.wadrova.domain.GarmentCategory.BOTTOM
import com.example.wadrova.domain.GarmentCategory.DRESS
import com.example.wadrova.domain.GarmentCategory.FOOTWEAR
import com.example.wadrova.domain.GarmentCategory.OUTERWEAR
import com.example.wadrova.domain.GarmentCategory.TOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutfitCompositionTest {

    private val coat = Garment("coat", OUTERWEAR)
    private val jacket = Garment("jacket", OUTERWEAR)
    private val shirt = Garment("shirt", TOP)
    private val tee = Garment("tee", TOP)
    private val dress = Garment("dress", DRESS)
    private val slip = Garment("slip", DRESS)
    private val jeans = Garment("jeans", BOTTOM)
    private val boots = Garment("boots", FOOTWEAR)
    private val loafers = Garment("loafers", FOOTWEAR)
    private val bag = Garment("bag", ACCESSORY)
    private val scarf = Garment("scarf", ACCESSORY)
    private val wardrobe = listOf(coat, jacket, shirt, tee, dress, slip, jeans, boots, loafers, bag, scarf)

    private fun toggle(ids: List<String>, garment: Garment) = toggleInOutfit(ids, garment, wardrobe)

    @Test
    fun sameCategoryReplacesExistingPiece() {
        assertEquals(listOf("jeans", "tee"), toggle(listOf("shirt", "jeans"), tee))
        assertEquals(listOf("jacket"), toggle(listOf("coat"), jacket))
        assertEquals(listOf("loafers"), toggle(listOf("boots"), loafers))
        assertEquals(listOf("slip"), toggle(listOf("dress"), slip))
    }

    @Test
    fun dressDisplacesTopAndBottom() {
        assertEquals(listOf("boots", "dress"), toggle(listOf("shirt", "jeans", "boots"), dress))
    }

    @Test
    fun topOrBottomDisplacesDress() {
        assertEquals(listOf("boots", "shirt"), toggle(listOf("dress", "boots"), shirt))
        assertEquals(listOf("boots", "jeans"), toggle(listOf("dress", "boots"), jeans))
    }

    @Test
    fun topAndBottomCoexist() {
        assertEquals(listOf("shirt", "jeans"), toggle(listOf("shirt"), jeans))
    }

    @Test
    fun outerwearAndFootwearDoNotConflictWithOtherCategories() {
        assertEquals(listOf("dress", "boots", "coat"), toggle(listOf("dress", "boots"), coat))
    }

    @Test
    fun accessoriesStackAndConflictWithNothing() {
        assertEquals(listOf("dress", "bag", "scarf"), toggle(listOf("dress", "bag"), scarf))
    }

    @Test
    fun togglingAPieceInTheOutfitRemovesIt() {
        assertEquals(listOf("jeans"), toggle(listOf("shirt", "jeans"), shirt))
        assertEquals(emptyList(), toggle(listOf("bag"), bag))
    }

    @Test
    fun unknownIdsSurviveToggling() {
        assertEquals(listOf("gone", "tee"), toggle(listOf("gone", "shirt"), tee))
    }

    @Test
    fun pickGarmentsUsesDisplayOrder() {
        val ids = listOf("bag", "boots", "jeans", "dress", "shirt", "coat")
        assertEquals(listOf(coat, shirt, dress, jeans, boots, bag), pickGarments(wardrobe, ids))
    }

    @Test
    fun pickGarmentsKeepsSelectionOrderWithinCategory() {
        assertEquals(listOf(scarf, bag), pickGarments(wardrobe, listOf("scarf", "bag")))
    }

    @Test
    fun pickGarmentsSkipsUnknownIds() {
        assertEquals(listOf(shirt), pickGarments(wardrobe, listOf("gone", "shirt")))
    }

    @Test
    fun draftIsCleanRegardlessOfOrder() {
        assertTrue(sameGarmentSet(listOf("a", "b"), listOf("b", "a")))
        assertFalse(isDraftDirty(listOf("a", "b"), listOf("b", "a")))
    }

    @Test
    fun draftIsDirtyAfterAddRemoveOrReplace() {
        val baseline = listOf("a", "b")
        assertTrue(isDraftDirty(baseline, listOf("a", "b", "c")))
        assertTrue(isDraftDirty(baseline, listOf("a")))
        assertTrue(isDraftDirty(baseline, listOf("a", "c")))
    }

    @Test
    fun draftIsCleanAfterReturningToOriginalComposition() {
        val baseline = listOf("shirt", "jeans")
        val changed = toggle(baseline, tee)
        assertTrue(isDraftDirty(baseline, changed))
        assertFalse(isDraftDirty(baseline, toggle(changed, shirt)))
    }

    @Test
    fun lookWithOwnedPiecesCanBeSaved() {
        assertTrue(canSaveLook(listOf("shirt", "jeans"), wardrobe))
    }

    @Test
    fun emptyLookCannotBeSaved() {
        assertFalse(canSaveLook(emptyList(), wardrobe))
    }

    @Test
    fun lookWithUnknownPieceCannotBeSaved() {
        assertFalse(canSaveLook(listOf("shirt", "gone"), wardrobe))
    }

    @Test
    fun variationNameAppendsSuffix() {
        assertEquals("Cobalt Easy — Variation", variationName("Cobalt Easy"))
    }

    @Test
    fun variationNameDoesNotRepeatSuffix() {
        assertEquals("Cobalt Easy — Variation", variationName("Cobalt Easy — Variation"))
        assertEquals("Cobalt Easy — Variation", variationName("Cobalt Easy — Variation 2"))
    }

    @Test
    fun variationNameFallsBackForEmptyName() {
        assertEquals("Untitled look — Variation", variationName(""))
    }

    @Test
    fun lookWithDuplicatePieceCannotBeSaved() {
        assertFalse(canSaveLook(listOf("shirt", "shirt"), wardrobe))
    }

    @Test
    fun nextVariationNameUsesPlainSuffixWhenFree() {
        assertEquals("Cobalt — Variation", nextVariationName("Cobalt", listOf("Cobalt")))
    }

    @Test
    fun nextVariationNameCountsUpWithoutRepeatingSuffix() {
        val existing = listOf("Cobalt", "Cobalt — Variation", "Cobalt — Variation 2")
        assertEquals("Cobalt — Variation 3", nextVariationName("Cobalt", existing))
        assertEquals("Cobalt — Variation 3", nextVariationName("Cobalt — Variation", existing))
        assertEquals("Cobalt — Variation 3", nextVariationName("Cobalt — Variation 2", existing))
    }
}
