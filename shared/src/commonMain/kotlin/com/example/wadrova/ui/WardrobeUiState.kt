package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.pickGarments
import com.example.wadrova.domain.toggleInOutfit

enum class Screen { WARDROBE, BUILDER }

/** Screen state for the wardrobe and builder. Outfit rules stay in the domain functions. */
data class WardrobeUiState(
    val items: List<WardrobeItem>,
    val screen: Screen = Screen.WARDROBE,
    val wardrobeFilter: GarmentCategory? = null,
    val draftIds: List<String> = emptyList(),
    val pickerOpen: Boolean = false,
    val pickerFilter: GarmentCategory? = null,
) {
    private val garments = items.map { it.garment }

    /** The outfit draft in the established display order. */
    val draftItems: List<WardrobeItem>
        get() = pickGarments(garments, draftIds).map { garment -> items.first { it.garment == garment } }

    fun itemsIn(filter: GarmentCategory?): List<WardrobeItem> =
        if (filter == null) items else items.filter { it.garment.category == filter }

    fun isInDraft(item: WardrobeItem): Boolean = item.garment.id in draftIds

    /** The pieces that adding [item] would remove from the draft. */
    fun replacedBy(item: WardrobeItem): List<WardrobeItem> {
        if (isInDraft(item)) return emptyList()
        val remaining = toggleInOutfit(draftIds, item.garment, garments)
        return draftItems.filter { it.garment.id !in remaining }
    }

    fun toggle(item: WardrobeItem): WardrobeUiState =
        copy(draftIds = toggleInOutfit(draftIds, item.garment, garments))

    fun filterWardrobe(filter: GarmentCategory?): WardrobeUiState = copy(wardrobeFilter = filter)

    fun filterPicker(filter: GarmentCategory?): WardrobeUiState = copy(pickerFilter = filter)

    fun openBuilder(): WardrobeUiState = copy(screen = Screen.BUILDER)

    fun openWardrobe(): WardrobeUiState = copy(screen = Screen.WARDROBE, pickerOpen = false)

    fun openPicker(filter: GarmentCategory? = null): WardrobeUiState =
        copy(pickerOpen = true, pickerFilter = filter)

    fun closePicker(): WardrobeUiState = copy(pickerOpen = false)
}

/** Holds the state so the draft survives navigation and Android configuration changes. */
class WardrobeViewModel(items: List<WardrobeItem> = sampleWardrobe) : ViewModel() {
    var state by mutableStateOf(WardrobeUiState(items))
        private set

    fun update(change: (WardrobeUiState) -> WardrobeUiState) {
        state = change(state)
    }
}
