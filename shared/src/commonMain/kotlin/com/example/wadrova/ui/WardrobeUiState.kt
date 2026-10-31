package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.InvalidLookException
import com.example.wadrova.data.SavedLookRepository
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.SavedLook
import com.example.wadrova.domain.canSaveLook
import com.example.wadrova.domain.isDraftDirty
import com.example.wadrova.domain.nextVariationName
import com.example.wadrova.domain.pickGarments
import com.example.wadrova.domain.toggleInOutfit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Screen { WARDROBE, BUILDER, SAVED_LOOKS }

enum class LooksStatus { LOADING, READY, FAILED }

enum class SaveMode { NEW, UPDATE }

data class SaveSheetState(
    val name: String,
    val mode: SaveMode = SaveMode.NEW,
    val saving: Boolean = false,
    val error: String? = null,
)

/** A draft-replacing action waiting for confirmation: opening [look], or a new look when it's null. */
data class PendingDiscard(val look: SavedLook?)

/** Screen state for the wardrobe and builder. Outfit rules stay in the domain functions. */
data class WardrobeUiState(
    val items: List<WardrobeItem>,
    val screen: Screen = Screen.WARDROBE,
    val wardrobeFilter: GarmentCategory? = null,
    val draftIds: List<String> = emptyList(),
    val pickerOpen: Boolean = false,
    val pickerFilter: GarmentCategory? = null,
    val savedLooks: List<SavedLook> = emptyList(),
    val looksStatus: LooksStatus = LooksStatus.LOADING,
    val openedLook: SavedLook? = null,
    val saveSheet: SaveSheetState? = null,
    val pendingDiscard: PendingDiscard? = null,
) {
    private val garments = items.map { it.garment }

    /** The outfit draft in the established display order. */
    val draftItems: List<WardrobeItem>
        get() = pickGarments(garments, draftIds).map { garment -> items.first { it.garment == garment } }

    /** Draft references with no matching garment, which a reopened look can contain. */
    val unknownDraftIds: List<String>
        get() = draftIds.filter { id -> garments.none { it.id == id } }

    /** True when the draft differs from the look it was opened from, or has pieces and was never saved. */
    val isDirty: Boolean
        get() = openedLook?.let { isDraftDirty(it.garmentIds, draftIds) } ?: draftIds.isNotEmpty()

    val canSaveDraft: Boolean
        get() = isDirty && canSaveLook(draftIds, garments)

    val canSubmitSave: Boolean
        get() = saveSheet?.let { !it.saving && it.name.isNotBlank() && canSaveDraft } ?: false

    fun itemsFor(look: SavedLook): List<WardrobeItem> =
        pickGarments(garments, look.garmentIds).map { garment -> items.first { it.garment == garment } }

    fun missingPieceCount(look: SavedLook): Int =
        look.garmentIds.count { id -> garments.none { it.id == id } }

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

    fun openSavedLooks(): WardrobeUiState = copy(screen = Screen.SAVED_LOOKS)

    fun removeUnknownPieces(): WardrobeUiState = copy(draftIds = draftIds - unknownDraftIds.toSet())

    /** Opens [look] in the builder, asking first if that would discard unsaved changes. */
    fun requestOpen(look: SavedLook): WardrobeUiState =
        if (isDirty) copy(pendingDiscard = PendingDiscard(look)) else open(look)

    fun requestNewLook(): WardrobeUiState =
        if (isDirty) copy(pendingDiscard = PendingDiscard(null)) else startNew()

    fun confirmDiscard(): WardrobeUiState {
        val pending = pendingDiscard ?: return this
        return copy(pendingDiscard = null).run { pending.look?.let(::open) ?: startNew() }
    }

    fun keepDraft(): WardrobeUiState = copy(pendingDiscard = null)

    private fun open(look: SavedLook) =
        copy(draftIds = look.garmentIds, openedLook = look, screen = Screen.BUILDER)

    private fun startNew() = copy(draftIds = emptyList(), openedLook = null)

    fun openSaveSheet(): WardrobeUiState =
        if (!canSaveDraft) this else copy(saveSheet = SaveSheetState(name = defaultName(SaveMode.NEW)))

    fun closeSaveSheet(): WardrobeUiState =
        if (saveSheet?.saving == true) this else copy(saveSheet = null)

    fun setSaveName(name: String): WardrobeUiState =
        copy(saveSheet = saveSheet?.copy(name = name, error = null))

    fun setSaveMode(mode: SaveMode): WardrobeUiState =
        copy(saveSheet = saveSheet?.copy(mode = mode, name = defaultName(mode), error = null))

    private fun defaultName(mode: SaveMode): String {
        val opened = openedLook ?: return ""
        return if (mode == SaveMode.UPDATE) opened.name else nextVariationName(opened.name, savedLooks.map { it.name })
    }

    fun saveStarted(): WardrobeUiState = copy(saveSheet = saveSheet?.copy(saving = true, error = null))

    fun saveSucceeded(look: SavedLook): WardrobeUiState = copy(
        savedLooks = listOf(look) + savedLooks.filter { it.id != look.id },
        openedLook = look,
        saveSheet = null,
    )

    fun saveFailed(message: String): WardrobeUiState = copy(saveSheet = saveSheet?.copy(saving = false, error = message))
}

/** Holds the state so the draft survives navigation and Android configuration changes. Saved looks live in [repository]. */
class WardrobeViewModel(
    private val repository: SavedLookRepository,
    items: List<WardrobeItem> = sampleWardrobe,
) : ViewModel() {
    var state by mutableStateOf(WardrobeUiState(items))
        private set

    init {
        loadLooks()
    }

    fun update(change: (WardrobeUiState) -> WardrobeUiState) {
        state = change(state)
    }

    fun loadLooks() {
        state = state.copy(looksStatus = LooksStatus.LOADING)
        viewModelScope.launch {
            state = try {
                state.copy(savedLooks = repository.getAll(), looksStatus = LooksStatus.READY)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.copy(looksStatus = LooksStatus.FAILED)
            }
        }
    }

    /** The saving flag is set before suspending, so a repeated submission is ignored. */
    fun save() {
        val current = state
        val sheet = current.saveSheet ?: return
        if (!current.canSubmitSave) return
        state = current.saveStarted()
        viewModelScope.launch {
            state = try {
                val opened = current.openedLook
                val saved = if (sheet.mode == SaveMode.UPDATE && opened != null) {
                    repository.update(opened.id, sheet.name, current.draftIds)
                } else {
                    repository.create(sheet.name, current.draftIds)
                }
                state.saveSucceeded(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidLookException) {
                state.saveFailed(e.message ?: SAVE_FAILED)
            } catch (_: Exception) {
                state.saveFailed(SAVE_FAILED)
            }
        }
    }

    override fun onCleared() {
        repository.close()
    }

    private companion object {
        const val SAVE_FAILED = "Couldn't save this look. Your changes are still here, so you can try again."
    }
}
