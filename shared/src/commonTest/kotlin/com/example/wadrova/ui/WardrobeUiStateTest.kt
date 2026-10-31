package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentCategory
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
}
