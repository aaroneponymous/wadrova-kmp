package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.SavedLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WardrobeUiStateTest {

    private fun item(id: String) = sampleWardrobe.first { it.garment.id == id }

    private val initial = WardrobeUiState(sampleWardrobe)

    @Test
    fun togglingAddsAndRemovesAPiece() {
        val added = initial.toggle(item("poplin-shirt"))
        assertTrue(added.isInDraft(item("poplin-shirt")))
        assertFalse(added.toggle(item("poplin-shirt")).isInDraft(item("poplin-shirt")))
    }

    @Test
    fun replacedByPreviewsSameCategoryReplacementWithoutChangingTheDraft() {
        val state = initial.toggle(item("poplin-shirt"))
        assertEquals(listOf(item("poplin-shirt")), state.replacedBy(item("everyday-tee")))
        assertEquals(listOf("poplin-shirt"), state.draftIds)
    }

    @Test
    fun replacedByPreviewsDressReplacingTopAndBottom() {
        val state = initial.toggle(item("poplin-shirt")).toggle(item("straight-jeans"))
        assertEquals(listOf(item("poplin-shirt"), item("straight-jeans")), state.replacedBy(item("slip-dress")))
    }

    @Test
    fun replacedByIsEmptyForPiecesInTheDraftAndForAccessories() {
        val state = initial.toggle(item("leather-tote"))
        assertEquals(emptyList(), state.replacedBy(item("leather-tote")))
        assertEquals(emptyList(), state.replacedBy(item("silk-scarf")))
    }

    @Test
    fun draftItemsUseDisplayOrder() {
        val state = initial
            .toggle(item("leather-tote"))
            .toggle(item("court-sneakers"))
            .toggle(item("straight-jeans"))
            .toggle(item("poplin-shirt"))
            .toggle(item("wool-coat"))
        assertEquals(
            listOf("wool-coat", "poplin-shirt", "straight-jeans", "court-sneakers", "leather-tote"),
            state.draftItems.map { it.garment.id },
        )
    }

    @Test
    fun navigationPreservesDraftAndWardrobeFilter() {
        val state = initial
            .filterWardrobe(GarmentCategory.TOP)
            .toggle(item("poplin-shirt"))
            .openBuilder()
            .openPicker(GarmentCategory.DRESS)
            .toggle(item("slip-dress"))
            .openWardrobe()
        assertEquals(Screen.WARDROBE, state.screen)
        assertFalse(state.pickerOpen)
        assertEquals(GarmentCategory.TOP, state.wardrobeFilter)
        assertEquals(listOf("slip-dress"), state.draftIds)
    }

    @Test
    fun filtersSelectOnlyTheirCategoryAndNullSelectsEverything() {
        assertEquals(sampleWardrobe, initial.itemsIn(null))
        assertTrue(initial.itemsIn(GarmentCategory.DRESS).all { it.garment.category == GarmentCategory.DRESS })
        assertEquals(2, initial.itemsIn(GarmentCategory.DRESS).size)
    }

    private val savedLook = SavedLook("look", "Quiet Monday", listOf("poplin-shirt"), 1, 1)

    @Test
    fun wardrobeOpensOnItems() {
        assertEquals(WardrobeView.ITEMS, initial.wardrobeView)
    }

    @Test
    fun switchingViewsKeepsTheFilterAndSelection() {
        val state = initial.filterWardrobe(GarmentCategory.TOP).toggle(item("poplin-shirt"))
        val back = state.showWardrobeView(WardrobeView.OUTFITS).showWardrobeView(WardrobeView.ITEMS)
        assertEquals(GarmentCategory.TOP, back.wardrobeFilter)
        assertEquals(listOf("poplin-shirt"), back.draftIds)
    }

    @Test
    fun theArchivedListOnlyShowsInItems() {
        val archived = item("poplin-shirt").copy(archived = true)
        val state = WardrobeUiState(listOf(archived)).showArchivedPieces(true)
        assertTrue(state.isShowingArchived)
        assertFalse(state.showWardrobeView(WardrobeView.OUTFITS).isShowingArchived)
    }

    @Test
    fun aNewOutfitOpensTheBuilderOnAnEmptyDraft() {
        val state = initial.showWardrobeView(WardrobeView.OUTFITS).requestNewOutfit()
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(emptyList(), state.draftIds)
        assertEquals(null, state.openedLook)
    }

    @Test
    fun aNewOutfitAsksBeforeDiscardingASelection() {
        val selected = initial.toggle(item("poplin-shirt")).showWardrobeView(WardrobeView.OUTFITS)
        val asking = selected.requestNewOutfit()
        assertEquals(Screen.WARDROBE, asking.screen)
        assertEquals(listOf("poplin-shirt"), asking.draftIds)

        val kept = asking.keepDraft()
        assertEquals(Screen.WARDROBE, kept.screen)
        assertEquals(listOf("poplin-shirt"), kept.draftIds)

        val confirmed = asking.confirmDiscard()
        assertEquals(Screen.BUILDER, confirmed.screen)
        assertEquals(emptyList(), confirmed.draftIds)
    }

    @Test
    fun aNewOutfitDoesNotEditAnOpenedLook() {
        val opened = initial.copy(savedLooks = listOf(savedLook)).requestOpen(savedLook).openWardrobe()
        val state = opened.requestNewOutfit()
        assertEquals(null, state.openedLook)
        assertEquals(emptyList(), state.draftIds)
        assertEquals(listOf(savedLook), state.savedLooks)
    }

    @Test
    fun startingANewLookInsideTheBuilderStillStaysThere() {
        val state = initial.toggle(item("poplin-shirt")).openBuilder().requestNewLook().confirmDiscard()
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(emptyList(), state.draftIds)
    }
}
