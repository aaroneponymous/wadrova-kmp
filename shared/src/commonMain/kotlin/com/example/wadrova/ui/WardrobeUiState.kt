package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.GarmentRepository
import com.example.wadrova.data.InvalidGarmentException
import com.example.wadrova.data.InvalidLookException
import com.example.wadrova.data.PhotoChange
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.data.SavedLookRepository
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.SavedLook
import com.example.wadrova.domain.UserGarment
import com.example.wadrova.domain.canSaveLook
import com.example.wadrova.domain.isDraftDirty
import com.example.wadrova.domain.nextVariationName
import com.example.wadrova.domain.pickGarments
import com.example.wadrova.domain.toggleInOutfit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Screen { WARDROBE, BUILDER, GARMENT_DETAILS, GARMENT_FORM }

/** The two sides of the Wardrobe tab. Each keeps its own filters and scroll position. */
enum class WardrobeView { ITEMS, OUTFITS }

enum class LooksStatus { LOADING, READY, FAILED }

enum class SaveMode { NEW, UPDATE }

data class SaveSheetState(
    val name: String,
    val mode: SaveMode = SaveMode.NEW,
    val saving: Boolean = false,
    val error: String? = null,
)

/** The Add/Edit Garment form. [showErrors] turns on field validation after a submit attempt. */
data class GarmentFormState(
    val editing: WardrobeItem? = null,
    val name: String = "",
    val category: GarmentCategory? = null,
    val color: String? = null,
    val brand: String = "",
    val material: String = "",
    val categoryLocked: Boolean = false,
    val photoId: String? = null,
    val importingPhoto: Boolean = false,
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val nameMissing: Boolean get() = name.isBlank()
    val isValid: Boolean get() = !nameMissing && category != null && color != null

    /** The photo imported in this form session and not yet saved, which cancelling must delete. */
    val stagedPhotoId: String? get() = photoId?.takeIf { it != editing?.photoId }

    val photoChange: PhotoChange
        get() = when {
            photoId == editing?.photoId -> PhotoChange.Keep
            photoId == null -> PhotoChange.Remove
            else -> PhotoChange.Set(photoId)
        }
}

/** A draft-replacing action waiting for confirmation: opening [look], or a new look when it's null, which [openBuilder] then shows. */
data class PendingDiscard(val look: SavedLook?, val openBuilder: Boolean = false)

/** Styling [item] would take [replaced] out of the draft, so this waits for confirmation. */
data class PendingStyle(val item: WardrobeItem, val replaced: List<WardrobeItem>)

/** Screen state for the wardrobe and builder. Outfit rules stay in the domain functions. */
data class WardrobeUiState(
    val items: List<WardrobeItem>,
    val screen: Screen = Screen.WARDROBE,
    val wardrobeView: WardrobeView = WardrobeView.ITEMS,
    val wardrobeFilter: GarmentCategory? = null,
    val draftIds: List<String> = emptyList(),
    val pickerOpen: Boolean = false,
    val pickerFilter: GarmentCategory? = null,
    val savedLooks: List<SavedLook> = emptyList(),
    val looksStatus: LooksStatus = LooksStatus.LOADING,
    val openedLook: SavedLook? = null,
    val saveSheet: SaveSheetState? = null,
    val pendingDiscard: PendingDiscard? = null,
    val showArchived: Boolean = false,
    val garmentForm: GarmentFormState? = null,
    val detailGarmentId: String? = null,
    val pendingStyle: PendingStyle? = null,
) {
    private val garments = items.map { it.garment }

    val isShowingArchived: Boolean
        get() = wardrobeView == WardrobeView.ITEMS && showArchived && archivedItems.isNotEmpty()

    /** Pieces offered for new compositions. Archived pieces stay in [items] so saved looks can resolve them. */
    val availableItems: List<WardrobeItem>
        get() = items.filterNot { it.archived }

    val archivedItems: List<WardrobeItem>
        get() = items.filter { it.archived }

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

    fun archivedPieceCount(look: SavedLook): Int = itemsFor(look).count { it.archived }

    fun missingPieceCount(look: SavedLook): Int =
        look.garmentIds.count { id -> garments.none { it.id == id } }

    fun itemsIn(filter: GarmentCategory?): List<WardrobeItem> =
        if (filter == null) availableItems else availableItems.filter { it.garment.category == filter }

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

    fun openWardrobe(): WardrobeUiState = copy(screen = Screen.WARDROBE, pickerOpen = false, detailGarmentId = null)

    /** Looked up by id so the page shows edits as soon as they're saved. */
    val detailItem: WardrobeItem?
        get() = items.find { it.garment.id == detailGarmentId }

    fun openGarmentDetails(item: WardrobeItem): WardrobeUiState =
        copy(screen = Screen.GARMENT_DETAILS, detailGarmentId = item.garment.id)

    /**
     * Opens the builder with [item] in the draft, asking first if the outfit rules would replace pieces.
     * A saved look that's merely open isn't edited: it's safe in the library, so this starts a new look.
     */
    fun requestStylePiece(item: WardrobeItem): WardrobeUiState {
        if (item.archived) return this
        val base = if (openedLook != null && !isDirty) startNew() else this
        val replaced = base.replacedBy(item)
        return when {
            base.isInDraft(item) -> base.openBuilder()
            replaced.isEmpty() -> base.toggle(item).openBuilder()
            else -> copy(pendingStyle = PendingStyle(item, replaced))
        }
    }

    fun confirmStylePiece(): WardrobeUiState {
        val pending = pendingStyle ?: return this
        return copy(pendingStyle = null).toggle(pending.item).openBuilder()
    }

    fun keepCurrentLook(): WardrobeUiState = copy(pendingStyle = null)

    /** The garment form returns to the page it was opened from. */
    private val formParent: Screen
        get() = if (detailGarmentId != null) Screen.GARMENT_DETAILS else Screen.WARDROBE

    fun openPicker(filter: GarmentCategory? = null): WardrobeUiState =
        copy(pickerOpen = true, pickerFilter = filter)

    fun closePicker(): WardrobeUiState = copy(pickerOpen = false)

    fun showWardrobeView(view: WardrobeView): WardrobeUiState = copy(wardrobeView = view)

    fun removeUnknownPieces(): WardrobeUiState = copy(draftIds = draftIds - unknownDraftIds.toSet())

    /** Opens [look] in the builder, asking first if that would discard unsaved changes. */
    fun requestOpen(look: SavedLook): WardrobeUiState =
        if (isDirty) copy(pendingDiscard = PendingDiscard(look)) else open(look)

    fun requestNewLook(): WardrobeUiState =
        if (isDirty) copy(pendingDiscard = PendingDiscard(null)) else startNew()

    /** Opens the builder on an empty look, asking first if that would discard unsaved changes. */
    fun requestNewOutfit(): WardrobeUiState =
        if (isDirty) copy(pendingDiscard = PendingDiscard(null, openBuilder = true)) else startNew().openBuilder()

    fun confirmDiscard(): WardrobeUiState {
        val pending = pendingDiscard ?: return this
        return copy(pendingDiscard = null).run {
            pending.look?.let(::open) ?: startNew().let { if (pending.openBuilder) it.openBuilder() else it }
        }
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

    fun withUserGarments(garments: List<UserGarment>): WardrobeUiState =
        copy(items = garments.map { it.toWardrobeItem() } + items.filter { it.isSample })

    fun showArchivedPieces(show: Boolean): WardrobeUiState = copy(showArchived = show)

    fun openAddGarment(): WardrobeUiState = copy(screen = Screen.GARMENT_FORM, garmentForm = GarmentFormState())

    /** A piece in a look can't change category, because that could break the look's composition. */
    fun openEditGarment(item: WardrobeItem): WardrobeUiState {
        val id = item.garment.id
        return copy(
            screen = Screen.GARMENT_FORM,
            garmentForm = GarmentFormState(
                editing = item,
                name = item.name,
                category = item.garment.category,
                color = item.colorName,
                brand = item.brand.orEmpty(),
                material = item.material.orEmpty(),
                categoryLocked = id in draftIds || savedLooks.any { id in it.garmentIds },
                photoId = item.photoId,
            ),
        )
    }

    fun closeGarmentForm(): WardrobeUiState =
        if (garmentForm?.saving == true) this else copy(screen = formParent, garmentForm = null)

    fun formPhotoImportStarted(): WardrobeUiState =
        copy(garmentForm = garmentForm?.copy(importingPhoto = true, error = null))

    fun formPhotoImported(photoId: String): WardrobeUiState =
        copy(garmentForm = garmentForm?.copy(photoId = photoId, importingPhoto = false))

    fun formPhotoFailed(message: String): WardrobeUiState =
        copy(garmentForm = garmentForm?.copy(importingPhoto = false, error = message))

    fun updateGarmentForm(change: (GarmentFormState) -> GarmentFormState): WardrobeUiState =
        copy(garmentForm = garmentForm?.let { change(it).copy(error = null) })

    fun garmentFormStarted(): WardrobeUiState = copy(garmentForm = garmentForm?.copy(saving = true, error = null))

    fun garmentFormFailed(message: String): WardrobeUiState =
        copy(garmentForm = garmentForm?.copy(saving = false, error = message))

    /** Applies a created or edited piece and returns to the wardrobe. */
    fun garmentSaved(garment: UserGarment): WardrobeUiState {
        val item = garment.toWardrobeItem()
        val updated = if (items.any { it.garment.id == garment.garment.id }) {
            items.map { if (it.garment.id == garment.garment.id) item else it }
        } else {
            listOf(item) + items
        }
        return copy(items = updated, screen = formParent, garmentForm = null)
    }

    /** Applies a change made while the form stays open, such as an approved extraction. */
    fun garmentChangedInPlace(garment: UserGarment): WardrobeUiState {
        val item = garment.toWardrobeItem()
        return copy(
            items = items.map { if (it.garment.id == garment.garment.id) item else it },
            garmentForm = garmentForm?.let { form ->
                if (form.editing?.garment?.id == garment.garment.id) form.copy(editing = item) else form
            },
        )
    }

    /** Archiving takes a piece out of a new draft, but an opened look keeps its reference. */
    fun garmentArchiveChanged(garment: UserGarment): WardrobeUiState {
        val id = garment.garment.id
        val keepInDraft = !garment.archived || openedLook?.garmentIds?.contains(id) == true
        return garmentSaved(garment).copy(draftIds = if (keepInDraft) draftIds else draftIds - id)
    }
}

/** Holds the state so the draft survives navigation and Android configuration changes. Saved looks live in [repository]. */
class WardrobeViewModel(
    private val repository: SavedLookRepository,
    private val garmentRepository: GarmentRepository,
    private val photos: GarmentPhotoStore,
    items: List<WardrobeItem> = sampleWardrobe,
) : ViewModel() {
    var state by mutableStateOf(WardrobeUiState(items))
        private set

    init {
        loadRecords()
        // Only here, before any form can hold a staged photo.
        viewModelScope.launch {
            try {
                garmentRepository.removeUnreferencedPhotos()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun update(change: (WardrobeUiState) -> WardrobeUiState) {
        state = change(state)
    }

    /** Garments load with looks, so a look's pieces are never reported missing before they've loaded. */
    fun loadRecords() {
        state = state.copy(looksStatus = LooksStatus.LOADING)
        viewModelScope.launch {
            state = try {
                val garments = garmentRepository.getAll()
                val looks = repository.getAll()
                state.withUserGarments(garments).copy(savedLooks = looks, looksStatus = LooksStatus.READY)
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

    /** Shows field errors if the form isn't valid. The saving flag blocks repeated submissions. */
    fun submitGarmentForm() {
        val form = state.garmentForm ?: return
        if (form.saving || form.importingPhoto) return
        val category = form.category
        val color = form.color
        if (!form.isValid || category == null || color == null) {
            state = state.updateGarmentForm { it.copy(showErrors = true) }
            return
        }
        val details = GarmentDetails(form.name, category, color, form.brand, form.material)
        runGarmentAction(GARMENT_SAVE_FAILED, GarmentActionResult.SAVED) {
            val editing = form.editing
            if (editing == null) {
                garmentRepository.create(details, form.photoId)
            } else {
                garmentRepository.update(editing.garment.id, details, form.photoChange)
            }
        }
    }

    fun setEditedGarmentArchived(archived: Boolean) {
        val form = state.garmentForm ?: return
        val editing = form.editing ?: return
        if (form.saving || form.importingPhoto) return
        val staged = form.stagedPhotoId
        runGarmentAction(GARMENT_ARCHIVE_FAILED, GarmentActionResult.ARCHIVE_CHANGED) {
            garmentRepository.setArchived(editing.garment.id, archived).also { staged?.let { deleteQuietly(it) } }
        }
    }

    fun onPhotoPicked(result: PhotoPickResult) {
        when (result) {
            PhotoPickResult.Cancelled -> Unit
            PhotoPickResult.Failed -> state = state.formPhotoFailed(PHOTO_FAILED)
            is PhotoPickResult.Picked -> importPhoto(result.photo)
        }
    }

    private fun importPhoto(photo: PickedPhoto) {
        val form = state.garmentForm ?: return
        if (form.saving || form.importingPhoto) return
        state = state.formPhotoImportStarted()
        viewModelScope.launch {
            try {
                val imported = photos.import(photo)
                val current = state.garmentForm
                // The form may have been cancelled while the photo was importing.
                if (current == null || current.editing?.garment?.id != form.editing?.garment?.id) {
                    deleteQuietly(imported)
                } else {
                    current.stagedPhotoId?.let { deleteQuietly(it) }
                    state = state.formPhotoImported(imported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.formPhotoFailed(PHOTO_FAILED)
            }
        }
    }

    /** The saved photo's files stay until the form is saved, so cancelling can bring it back. */
    fun removeFormPhoto() {
        val form = state.garmentForm ?: return
        if (form.saving || form.importingPhoto) return
        form.stagedPhotoId?.let { staged -> viewModelScope.launch { deleteQuietly(staged) } }
        state = state.updateGarmentForm { it.copy(photoId = null) }
    }

    fun cancelGarmentForm() {
        val form = state.garmentForm ?: return
        if (form.saving) return
        form.stagedPhotoId?.let { staged -> viewModelScope.launch { deleteQuietly(staged) } }
        state = state.closeGarmentForm()
    }

    private suspend fun deleteQuietly(photoId: String) {
        try {
            photos.delete(photoId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun runGarmentAction(failure: String, result: GarmentActionResult, action: suspend () -> UserGarment) {
        state = state.garmentFormStarted()
        viewModelScope.launch {
            state = try {
                val garment = action()
                when (result) {
                    GarmentActionResult.SAVED -> state.garmentSaved(garment)
                    GarmentActionResult.ARCHIVE_CHANGED -> state.garmentArchiveChanged(garment)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidGarmentException) {
                state.garmentFormFailed(e.message ?: failure)
            } catch (_: Exception) {
                state.garmentFormFailed(failure)
            }
        }
    }

    private enum class GarmentActionResult { SAVED, ARCHIVE_CHANGED }

    override fun onCleared() {
        repository.close()
    }

    private companion object {
        const val SAVE_FAILED = "Couldn't save this look. Your changes are still here, so you can try again."
        const val GARMENT_SAVE_FAILED = "Couldn't save this piece. Your changes are still here, so you can try again."
        const val PHOTO_FAILED = "Couldn't use that photo. Try a different one."
        const val GARMENT_ARCHIVE_FAILED = "Couldn't update this piece. Please try again."
    }
}
