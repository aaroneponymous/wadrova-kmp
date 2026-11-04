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
import com.example.wadrova.domain.replacedByAdding
import com.example.wadrova.domain.toggleInOutfit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Screen { WARDROBE, BUILDER, GARMENT_DETAILS, GARMENT_FORM }

/** The two sides of the Wardrobe tab. Each keeps its own filters and scroll position. */
enum class WardrobeView { ITEMS, OUTFITS }

enum class LooksStatus { LOADING, READY, FAILED }

enum class SaveMode { NEW, UPDATE }

/**
 * The Save Look sheet. Each mode keeps its own name, so switching modes never loses what was typed.
 * [nameEdited] holds back a missing-name warning until the user has touched the field.
 */
data class SaveSheetState(
    val newName: String,
    val updateName: String = "",
    val mode: SaveMode = SaveMode.NEW,
    val saving: Boolean = false,
    val error: String? = null,
    val nameEdited: Boolean = false,
) {
    val name: String get() = if (mode == SaveMode.UPDATE) updateName else newName
}

/** Confirms a save once the repository has stored it. */
enum class SaveNotice(val message: String) {
    LOOK_SAVED("Look saved"),
    CHANGES_SAVED("Changes saved"),
    VARIATION_SAVED("Variation saved"),
}

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
    val confirmingDiscard: Boolean = false,
) {
    val nameMissing: Boolean get() = name.isBlank()
    val isValid: Boolean get() = !nameMissing && category != null && color != null

    /** The photo imported in this form session and not yet saved, which cancelling must delete. */
    val stagedPhotoId: String? get() = photoId?.takeIf { it != editing?.photoId }

    /** True when leaving the form would lose metadata or photo changes. */
    val isDirty: Boolean
        get() = name != (editing?.name.orEmpty()) ||
            category != editing?.garment?.category ||
            color != editing?.colorName ||
            brand != (editing?.brand.orEmpty()) ||
            material != (editing?.material.orEmpty()) ||
            photoId != editing?.photoId

    val photoChange: PhotoChange
        get() = when {
            photoId == editing?.photoId -> PhotoChange.Keep
            photoId == null -> PhotoChange.Remove
            else -> PhotoChange.Set(photoId)
        }
}

/** What happens once discarding unsaved pieces is confirmed. */
enum class PendingDiscard { LEAVE_LOOK, START_NEW }

/** A saved look being changed in the builder. It exists only while the builder is open, apart from the new-look draft. */
data class LookEdit(val look: SavedLook, val garmentIds: List<String> = look.garmentIds) {
    val isModified: Boolean get() = isDraftDirty(look.garmentIds, garmentIds)
}

/** A saved look waiting for delete confirmation, then for the repository. */
data class PendingLookDelete(val look: SavedLook, val deleting: Boolean = false, val error: String? = null)

/**
 * Adding [item] would take [replaced] out of the look, so this waits for confirmation.
 * [openBuilder] is set when it came from styling a piece, which then opens the builder.
 */
data class PendingReplacement(val item: WardrobeItem, val replaced: List<WardrobeItem>, val openBuilder: Boolean = false)

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
    val editing: LookEdit? = null,
    val saveSheet: SaveSheetState? = null,
    val pendingDiscard: PendingDiscard? = null,
    val pendingLookDelete: PendingLookDelete? = null,
    val showArchived: Boolean = false,
    val garmentForm: GarmentFormState? = null,
    val detailGarmentId: String? = null,
    val pendingReplacement: PendingReplacement? = null,
    val saveNotice: SaveNotice? = null,
    /** A just-saved look that Your Outfits scrolls to next time it shows, so a save never looks lost. */
    val revealLookId: String? = null,
    /** The Add a piece sheet: extract pieces from a photo, or add one manually. */
    val choosingAddMethod: Boolean = false,
) {
    private val garments = items.map { it.garment }

    val isShowingArchived: Boolean
        get() = wardrobeView == WardrobeView.ITEMS && showArchived && archivedItems.isNotEmpty()

    /** Pieces offered for new compositions. Archived pieces stay in [items] so saved looks can resolve them. */
    val availableItems: List<WardrobeItem>
        get() = items.filterNot { it.archived }

    val archivedItems: List<WardrobeItem>
        get() = items.filter { it.archived }

    val openedLook: SavedLook?
        get() = editing?.look

    /** The pieces the current context works on: the look being edited, otherwise the new-look draft in [draftIds]. */
    val builderIds: List<String>
        get() = editing?.garmentIds ?: draftIds

    val builderPieceCount: Int
        get() = builderIds.size

    /** The builder's pieces in the established display order. */
    val draftItems: List<WardrobeItem>
        get() = pickGarments(garments, builderIds).map { garment -> items.first { it.garment == garment } }

    /** Piece references with no matching garment, which a reopened look can contain. */
    val unknownDraftIds: List<String>
        get() = builderIds.filter { id -> garments.none { it.id == id } }

    /** True when the look differs from its saved version, or the new look has pieces. */
    val isDirty: Boolean
        get() = editing?.isModified ?: draftIds.isNotEmpty()

    /** A saved look with every piece removed can't be saved, so it offers a way back instead. */
    val isEditingEmptyLook: Boolean
        get() = editing != null && builderIds.isEmpty()

    private val hasEditToLose: Boolean
        get() = editing?.isModified == true

    val discardMessage: String
        get() {
            val look = openedLook?.name
            return when {
                pendingDiscard == PendingDiscard.START_NEW && hasEditToLose && draftIds.isNotEmpty() ->
                    "Your changes to $look and the new look you were building haven't been saved. $look stays as it was."
                hasEditToLose -> "Your changes to $look haven't been saved. The saved look stays as it was."
                else -> "The look you're working on hasn't been saved."
            }
        }

    val canSaveDraft: Boolean
        get() = isDirty && canSaveLook(builderIds, garments)

    val canSubmitSave: Boolean
        get() = saveSheet?.let { !it.saving && saveBlocker == null } ?: false

    /** Why the look can't be saved as it stands, or null if it can. */
    val saveBlocker: String?
        get() = when {
            builderIds.isEmpty() -> "Add at least one piece to save."
            unknownDraftIds.isNotEmpty() -> "Remove unavailable pieces before saving."
            !canSaveDraft -> "Change a piece to save this look."
            saveSheet?.name?.isBlank() == true -> "Enter a name to save this look."
            else -> null
        }

    fun itemsFor(look: SavedLook): List<WardrobeItem> =
        pickGarments(garments, look.garmentIds).map { garment -> items.first { it.garment == garment } }

    fun archivedPieceCount(look: SavedLook): Int = itemsFor(look).count { it.archived }

    fun missingPieceCount(look: SavedLook): Int =
        look.garmentIds.count { id -> garments.none { it.id == id } }

    fun itemsIn(filter: GarmentCategory?): List<WardrobeItem> =
        if (filter == null) availableItems else availableItems.filter { it.garment.category == filter }

    fun isInDraft(item: WardrobeItem): Boolean = item.garment.id in builderIds

    /** The pieces that adding [item] would remove from the current context. */
    fun replacedBy(item: WardrobeItem): List<WardrobeItem> {
        val replaced = replacedByAdding(builderIds, item.garment, garments)
        return draftItems.filter { it.garment.id in replaced }
    }

    private fun withBuilderIds(ids: List<String>): WardrobeUiState =
        editing?.let { copy(editing = it.copy(garmentIds = ids)) } ?: copy(draftIds = ids)

    fun toggle(item: WardrobeItem): WardrobeUiState =
        withBuilderIds(toggleInOutfit(builderIds, item.garment, garments))

    /** Adds or removes [item], asking first if adding it would replace pieces already in the look. */
    fun requestToggle(item: WardrobeItem): WardrobeUiState {
        val replaced = replacedBy(item)
        return if (replaced.isEmpty()) toggle(item) else copy(pendingReplacement = PendingReplacement(item, replaced))
    }

    fun confirmReplacement(): WardrobeUiState {
        val pending = pendingReplacement ?: return this
        val replaced = copy(pendingReplacement = null).toggle(pending.item)
        return if (pending.openBuilder) replaced.openBuilder() else replaced
    }

    /** Leaves the look exactly as it was. */
    fun cancelReplacement(): WardrobeUiState = copy(pendingReplacement = null)

    fun filterWardrobe(filter: GarmentCategory?): WardrobeUiState = copy(wardrobeFilter = filter)

    fun filterPicker(filter: GarmentCategory?): WardrobeUiState = copy(pickerFilter = filter)

    /** Opens the builder on the new-look draft, never on a saved look. Each visit starts the picker on All. */
    fun openBuilder(): WardrobeUiState = copy(screen = Screen.BUILDER, editing = null, pickerFilter = null)

    /** Leaves for wherever the builder was opened from, asking first if changes to a saved look would be lost. */
    fun requestLeaveBuilder(): WardrobeUiState =
        if (hasEditToLose) copy(pendingDiscard = PendingDiscard.LEAVE_LOOK) else leaveBuilder()

    private fun leaveBuilder() = copy(screen = parentScreen, pickerOpen = false, editing = null, saveNotice = null)

    fun openWardrobe(): WardrobeUiState = copy(screen = Screen.WARDROBE, pickerOpen = false, detailGarmentId = null)

    /** Looked up by id so the page shows edits as soon as they're saved. */
    val detailItem: WardrobeItem?
        get() = items.find { it.garment.id == detailGarmentId }

    fun openGarmentDetails(item: WardrobeItem): WardrobeUiState =
        copy(screen = Screen.GARMENT_DETAILS, detailGarmentId = item.garment.id)

    /** Opens the builder with [item] in the new-look draft, asking first if the outfit rules would replace pieces. */
    fun requestStylePiece(item: WardrobeItem): WardrobeUiState {
        if (item.archived) return this
        val replaced = replacedBy(item)
        return when {
            isInDraft(item) -> openBuilder()
            replaced.isEmpty() -> toggle(item).openBuilder()
            else -> copy(pendingReplacement = PendingReplacement(item, replaced, openBuilder = true))
        }
    }

    /** The builder and the garment form return to the page they were opened from. */
    private val parentScreen: Screen
        get() = if (detailGarmentId != null) Screen.GARMENT_DETAILS else Screen.WARDROBE

    /** Reopens on the category last chosen in this builder visit, unless a shortcut names one. */
    fun openPicker(filter: GarmentCategory? = pickerFilter): WardrobeUiState =
        copy(pickerOpen = true, pickerFilter = filter)

    fun closePicker(): WardrobeUiState = copy(pickerOpen = false)

    fun showWardrobeView(view: WardrobeView): WardrobeUiState = copy(wardrobeView = view)

    fun removeUnknownPieces(): WardrobeUiState = withBuilderIds(builderIds - unknownDraftIds.toSet())

    /** Opens [look] for editing. A new-look draft in progress waits untouched, so there's nothing to confirm. */
    fun openLook(look: SavedLook): WardrobeUiState =
        copy(editing = LookEdit(look), screen = Screen.BUILDER, pickerFilter = null, saveNotice = null)

    /** Puts an edited look's saved pieces back. The saved record never changed. */
    fun restoreSavedLook(): WardrobeUiState = copy(editing = editing?.let { LookEdit(it.look) })

    /** Opens the builder on an empty look, asking first if that would discard unsaved pieces. */
    fun requestNewOutfit(): WardrobeUiState =
        if (hasEditToLose || draftIds.isNotEmpty()) copy(pendingDiscard = PendingDiscard.START_NEW) else startNew()

    fun confirmDiscard(): WardrobeUiState {
        val pending = pendingDiscard ?: return this
        return copy(pendingDiscard = null).run {
            when (pending) {
                PendingDiscard.LEAVE_LOOK -> leaveBuilder()
                PendingDiscard.START_NEW -> startNew()
            }
        }
    }

    fun keepDraft(): WardrobeUiState = copy(pendingDiscard = null)

    private fun startNew() = copy(draftIds = emptyList()).openBuilder()

    /** A saved look starts as a new variation, with its own name ready if the user switches to updating it. */
    fun openSaveSheet(): WardrobeUiState {
        if (!canSaveDraft) return this
        val opened = openedLook
        val newName = opened?.let { nextVariationName(it.name, savedLooks.map { look -> look.name }) }.orEmpty()
        return copy(saveSheet = SaveSheetState(newName = newName, updateName = opened?.name.orEmpty()))
    }

    fun closeSaveSheet(): WardrobeUiState =
        if (saveSheet?.saving == true) this else copy(saveSheet = null)

    /** Changes only the current mode's name. */
    fun setSaveName(name: String): WardrobeUiState = copy(
        saveSheet = saveSheet?.takeUnless { it.saving }?.let { sheet ->
            val named = if (sheet.mode == SaveMode.UPDATE) sheet.copy(updateName = name) else sheet.copy(newName = name)
            named.copy(nameEdited = true, error = null)
        } ?: saveSheet,
    )

    fun setSaveMode(mode: SaveMode): WardrobeUiState =
        copy(saveSheet = saveSheet?.takeUnless { it.saving }?.copy(mode = mode, error = null) ?: saveSheet)

    fun clearSaveNotice(): WardrobeUiState = copy(saveNotice = null)

    fun lookRevealed(): WardrobeUiState = copy(revealLookId = null)

    fun requestDeleteLook(look: SavedLook): WardrobeUiState = copy(pendingLookDelete = PendingLookDelete(look))

    fun cancelDeleteLook(): WardrobeUiState =
        if (pendingLookDelete?.deleting == true) this else copy(pendingLookDelete = null)

    fun deleteLookStarted(): WardrobeUiState =
        copy(pendingLookDelete = pendingLookDelete?.copy(deleting = true, error = null))

    fun deleteLookFailed(message: String): WardrobeUiState =
        copy(pendingLookDelete = pendingLookDelete?.copy(deleting = false, error = message))

    /** Removes only this look. The new-look draft and the garments aren't part of it. */
    fun lookDeleted(id: String): WardrobeUiState = copy(
        savedLooks = savedLooks.filter { it.id != id },
        editing = editing?.takeUnless { it.look.id == id },
        pendingLookDelete = null,
    )

    fun saveStarted(): WardrobeUiState = copy(saveSheet = saveSheet?.copy(saving = true, error = null))

    /** The builder now shows the saved look. A new look's draft is finished, so its pieces leave the selection. */
    fun saveSucceeded(look: SavedLook): WardrobeUiState = copy(
        savedLooks = listOf(look) + savedLooks.filter { it.id != look.id },
        draftIds = if (editing == null) emptyList() else draftIds,
        editing = LookEdit(look),
        saveSheet = null,
        revealLookId = look.id,
        saveNotice = when {
            editing == null -> SaveNotice.LOOK_SAVED
            look.id == editing.look.id -> SaveNotice.CHANGES_SAVED
            else -> SaveNotice.VARIATION_SAVED
        },
    )

    fun saveFailed(message: String): WardrobeUiState = copy(saveSheet = saveSheet?.copy(saving = false, error = message))

    fun withUserGarments(garments: List<UserGarment>): WardrobeUiState =
        copy(items = garments.map { it.toWardrobeItem() })

    fun showArchivedPieces(show: Boolean): WardrobeUiState = copy(showArchived = show)

    fun openAddGarment(): WardrobeUiState =
        copy(screen = Screen.GARMENT_FORM, garmentForm = GarmentFormState(), choosingAddMethod = false)

    fun openAddPieceChooser(): WardrobeUiState = copy(choosingAddMethod = true)

    fun closeAddPieceChooser(): WardrobeUiState = copy(choosingAddMethod = false)

    /**
     * From the builder's picker when there's nothing to pick: leaves the builder the usual way, so a
     * saved look's changes are confirmed first and never carried out of it, then opens the garment form.
     */
    fun requestAddFirstPiece(): WardrobeUiState {
        val left = copy(pickerOpen = false).requestLeaveBuilder()
        return if (left.pendingDiscard != null) left else left.openAddGarment()
    }

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
        if (garmentForm?.saving == true) this else copy(screen = parentScreen, garmentForm = null)

    fun keepEditingGarmentForm(): WardrobeUiState = copy(garmentForm = garmentForm?.copy(confirmingDiscard = false))

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
        return copy(items = updated, screen = parentScreen, garmentForm = null)
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

    /** Archiving takes a piece out of the new-look draft. Saved looks keep their references. */
    fun garmentArchiveChanged(garment: UserGarment): WardrobeUiState =
        garmentSaved(garment).copy(draftIds = if (garment.archived) draftIds - garment.garment.id else draftIds)
}

/** Holds the state so the draft survives navigation and Android configuration changes. Saved looks live in [repository]. */
class WardrobeViewModel(
    private val repository: SavedLookRepository,
    private val garmentRepository: GarmentRepository,
    private val photos: GarmentPhotoStore,
) : ViewModel() {
    var state by mutableStateOf(WardrobeUiState(items = emptyList()))
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
                    repository.update(opened.id, sheet.name, current.builderIds)
                } else {
                    repository.create(sheet.name, current.builderIds)
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

    /** The deleting flag is set before suspending, so a repeated confirmation is ignored. */
    fun deleteLook() {
        val pending = state.pendingLookDelete ?: return
        if (pending.deleting) return
        state = state.deleteLookStarted()
        viewModelScope.launch {
            state = try {
                repository.delete(pending.look.id)
                state.lookDeleted(pending.look.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.deleteLookFailed(DELETE_LOOK_FAILED)
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

    /** Leaves straight away when nothing would be lost, otherwise asks first. */
    fun requestCancelGarmentForm() {
        val form = state.garmentForm ?: return
        if (form.saving) return
        if (form.isDirty) {
            state = state.copy(garmentForm = form.copy(confirmingDiscard = true))
        } else {
            cancelGarmentForm()
        }
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
        const val DELETE_LOOK_FAILED = "Couldn't delete this look. It's still saved, so you can try again."
        const val GARMENT_SAVE_FAILED = "Couldn't save this piece. Your changes are still here, so you can try again."
        const val PHOTO_FAILED = "Couldn't use that photo. Try a different one."
        const val GARMENT_ARCHIVE_FAILED = "Couldn't update this piece. Please try again."
    }
}
