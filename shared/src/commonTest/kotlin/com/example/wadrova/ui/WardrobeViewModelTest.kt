package com.example.wadrova.ui

import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentRepository
import com.example.wadrova.data.InvalidGarmentException
import com.example.wadrova.data.InvalidLookException
import com.example.wadrova.data.PhotoChange
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.data.SavedLookRepository
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.SavedLook
import com.example.wadrova.domain.UserGarment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class WardrobeViewModelTest {

    private class FakeRepository(var looks: List<SavedLook> = emptyList()) : SavedLookRepository {
        var creates = 0
        var updates = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getAll(): List<SavedLook> {
            failure?.let { throw it }
            return looks
        }

        override suspend fun create(name: String, garmentIds: List<String>): SavedLook {
            creates++
            gate?.await()
            failure?.let { throw it }
            return SavedLook("id-$creates", name, garmentIds, 1, 1).also { looks = listOf(it) + looks }
        }

        override suspend fun update(id: String, name: String, garmentIds: List<String>): SavedLook {
            updates++
            failure?.let { throw it }
            return SavedLook(id, name, garmentIds, 1, 2)
        }

        var deletes = 0
        var deleteFailure: Exception? = null
        var deleteGate: CompletableDeferred<Unit>? = null

        override suspend fun delete(id: String) {
            deletes++
            deleteGate?.await()
            deleteFailure?.let { throw it }
            looks = looks.filter { it.id != id }
        }

        override fun close() = Unit
    }

    private class FakeGarmentRepository(var garments: List<UserGarment> = emptyList()) : GarmentRepository {
        var creates = 0
        var sweeps = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getAll(): List<UserGarment> {
            failure?.let { throw it }
            return garments
        }

        override suspend fun create(details: GarmentDetails, photoId: String?): UserGarment {
            creates++
            gate?.await()
            failure?.let { throw it }
            return details.toGarment("user-$creates", archived = false, photoId).also { garments = listOf(it) + garments }
        }

        override suspend fun update(id: String, details: GarmentDetails, photo: PhotoChange): UserGarment {
            failure?.let { throw it }
            val photoId = when (photo) {
                PhotoChange.Keep -> garments.firstOrNull { it.garment.id == id }?.photoId
                PhotoChange.Remove -> null
                is PhotoChange.Set -> photo.photoId
            }
            return details.toGarment(id, archived = false, photoId)
        }

        override suspend fun setExtractedImage(id: String, imageId: String?): UserGarment =
            garments.first { it.garment.id == id }.copy(extractedImageId = imageId)

        override suspend fun removeUnreferencedPhotos() {
            sweeps++
        }

        override suspend fun setArchived(id: String, archived: Boolean): UserGarment {
            failure?.let { throw it }
            return garments.first { it.garment.id == id }.copy(archived = archived)
        }

        private fun GarmentDetails.toGarment(id: String, archived: Boolean, photoId: String?) =
            UserGarment(Garment(id, category), name, color, brand, material, archived, photoId)
    }

    private fun userGarment(id: String, category: GarmentCategory = GarmentCategory.OUTERWEAR, archived: Boolean = false) =
        UserGarment(Garment(id, category), "Field Jacket", "Green", null, null, archived)

    private fun item(id: String) = sampleWardrobe.first { it.garment.id == id }

    private val shirt = item("poplin-shirt")
    private val tote = item("leather-tote")

    private fun WardrobeViewModel.saveDraft(name: String) {
        update { it.openSaveSheet() }
        update { it.setSaveName(name) }
        save()
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsSavedLooksOnStart() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(), FakePhotoStore())
        assertEquals(listOf(look), viewModel.state.savedLooks)
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun loadFailureIsReportedAndCanBeRetried() {
        val repository = FakeRepository().apply { failure = IllegalStateException() }
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        assertEquals(LooksStatus.FAILED, viewModel.state.looksStatus)
        repository.failure = null
        viewModel.loadRecords()
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun savingMakesTheDraftCleanAndAddsTheLook() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.toggle(shirt) }
        viewModel.saveDraft("Monday")
        val state = viewModel.state
        assertNull(state.saveSheet)
        assertEquals("Monday", state.openedLook?.name)
        assertEquals(listOf(state.openedLook), state.savedLooks)
        assertFalse(state.isDirty)
    }

    @Test
    fun repeatedSubmissionSavesOnce() {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository().apply { this.gate = gate }
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.toggle(shirt) }
        viewModel.saveDraft("Monday")
        assertTrue(viewModel.state.saveSheet?.saving == true)
        viewModel.save()
        viewModel.save()
        viewModel.update { it.closeSaveSheet() }
        gate.complete(Unit)
        assertEquals(1, repository.creates)
        assertEquals(1, viewModel.state.savedLooks.size)
    }

    @Test
    fun failedSaveKeepsTheDraftAndAllowsRetry() {
        val repository = FakeRepository()
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.toggle(shirt).toggle(tote) }
        repository.failure = IllegalStateException()
        viewModel.saveDraft("Monday")
        val failed = viewModel.state
        assertEquals(listOf(shirt.garment.id, tote.garment.id), failed.draftIds)
        assertEquals(false, failed.saveSheet?.saving)
        assertTrue(failed.saveSheet?.error != null)
        assertTrue(failed.isDirty)
        assertTrue(viewModel.state.savedLooks.isEmpty())

        repository.failure = null
        viewModel.save()
        assertNull(viewModel.state.saveSheet)
        assertEquals("Monday", viewModel.state.openedLook?.name)
    }

    @Test
    fun repositoryRejectionIsShownToTheUser() {
        val repository = FakeRepository()
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.toggle(shirt) }
        repository.failure = InvalidLookException("This look includes a piece that is no longer in your wardrobe.")
        viewModel.saveDraft("Monday")
        assertEquals(
            "This look includes a piece that is no longer in your wardrobe.",
            viewModel.state.saveSheet?.error,
        )
    }

    @Test
    fun unknownPieceBlocksSavingUntilRemoved() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id, "gone"), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openLook(look) }
        viewModel.update { it.toggle(tote) }
        assertEquals(listOf("gone"), viewModel.state.unknownDraftIds)
        assertFalse(viewModel.state.canSaveDraft)
        viewModel.update { it.openSaveSheet() }
        viewModel.save()
        assertNull(viewModel.state.saveSheet)
        assertEquals(0, repository.creates)

        viewModel.update { it.removeUnknownPieces() }
        assertTrue(viewModel.state.canSaveDraft)
    }

    @Test
    fun reopeningRestoresPiecesAndAnEditOffersASequentialVariationName() {
        val looks = listOf(
            SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1),
            SavedLook("b", "Monday — Variation", listOf(tote.garment.id), 1, 1),
        )
        val viewModel = WardrobeViewModel(FakeRepository(looks), FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openLook(looks[0]) }
        assertEquals(Screen.BUILDER, viewModel.state.screen)
        assertEquals(listOf(shirt.garment.id), viewModel.state.builderIds)
        assertFalse(viewModel.state.isDirty)

        viewModel.update { it.toggle(tote).openSaveSheet() }
        assertEquals("Monday — Variation 2", viewModel.state.saveSheet?.name)
        viewModel.update { it.setSaveMode(SaveMode.UPDATE) }
        assertEquals("Monday", viewModel.state.saveSheet?.name)
    }

    @Test
    fun updateModeKeepsTheLooksIdentity() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openLook(look) }
        viewModel.update { it.toggle(tote).openSaveSheet().setSaveMode(SaveMode.UPDATE) }
        viewModel.save()
        assertEquals(1, repository.updates)
        assertEquals(0, repository.creates)
        assertEquals("a", viewModel.state.openedLook?.id)
        assertEquals(1, viewModel.state.savedLooks.size)
    }

    private val mondayLook = SavedLook("a", "Monday", listOf("poplin-shirt"), 1, 1)
    private val tuesdayLook = SavedLook("b", "Tuesday", listOf("leather-tote"), 1, 1)

    private fun deleteViewModel(repository: FakeRepository) =
        WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())

    @Test
    fun cancellingDeletionLeavesEverythingAlone() {
        val repository = FakeRepository(listOf(mondayLook, tuesdayLook))
        val viewModel = deleteViewModel(repository)
        viewModel.update { it.requestDeleteLook(mondayLook) }
        assertEquals(mondayLook, viewModel.state.pendingLookDelete?.look)
        assertEquals(0, repository.deletes)

        viewModel.update { it.cancelDeleteLook() }
        assertNull(viewModel.state.pendingLookDelete)
        assertEquals(0, repository.deletes)
        assertEquals(listOf(mondayLook, tuesdayLook), viewModel.state.savedLooks)
    }

    @Test
    fun confirmingDeletionRemovesOnlyThatLookAndTheDraftSurvives() {
        val repository = FakeRepository(listOf(mondayLook, tuesdayLook))
        val viewModel = deleteViewModel(repository)
        viewModel.update { it.toggle(tote).showWardrobeView(WardrobeView.OUTFITS).requestDeleteLook(mondayLook) }
        viewModel.deleteLook()

        assertEquals(listOf(tuesdayLook), repository.looks)
        assertEquals(listOf(tuesdayLook), viewModel.state.savedLooks)
        assertEquals(1, viewModel.state.savedLooks.size)
        assertEquals(listOf(tote.garment.id), viewModel.state.draftIds)
        assertEquals(WardrobeView.OUTFITS, viewModel.state.wardrobeView)
        assertEquals(Screen.WARDROBE, viewModel.state.screen)
        assertNull(viewModel.state.pendingLookDelete)
    }

    @Test
    fun deletingTheLastLookLeavesAnEmptyLibraryThatCanStillBuild() {
        val viewModel = deleteViewModel(FakeRepository(listOf(mondayLook)))
        viewModel.update { it.showWardrobeView(WardrobeView.OUTFITS).requestDeleteLook(mondayLook) }
        viewModel.deleteLook()
        assertEquals(emptyList(), viewModel.state.savedLooks)
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)

        viewModel.update { it.requestNewOutfit() }
        assertEquals(Screen.BUILDER, viewModel.state.screen)
        assertNull(viewModel.state.openedLook)
    }

    @Test
    fun failedDeletionKeepsTheLookAndCanBeRetried() {
        val repository = FakeRepository(listOf(mondayLook)).apply { deleteFailure = IllegalStateException() }
        val viewModel = deleteViewModel(repository)
        viewModel.update { it.requestDeleteLook(mondayLook) }
        viewModel.deleteLook()

        val failed = viewModel.state
        assertEquals(listOf(mondayLook), failed.savedLooks)
        assertEquals(mondayLook, failed.pendingLookDelete?.look)
        assertEquals(false, failed.pendingLookDelete?.deleting)
        assertTrue(failed.pendingLookDelete?.error != null)

        repository.deleteFailure = null
        viewModel.deleteLook()
        assertEquals(emptyList(), viewModel.state.savedLooks)
        assertNull(viewModel.state.pendingLookDelete)
    }

    @Test
    fun repeatedConfirmationDeletesOnceAndCantBeCancelledMidway() {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(listOf(mondayLook)).apply { deleteGate = gate }
        val viewModel = deleteViewModel(repository)
        viewModel.update { it.requestDeleteLook(mondayLook) }
        viewModel.deleteLook()
        viewModel.deleteLook()
        viewModel.update { it.cancelDeleteLook() }
        assertTrue(viewModel.state.pendingLookDelete?.deleting == true)
        gate.complete(Unit)
        assertEquals(1, repository.deletes)
        assertEquals(emptyList(), viewModel.state.savedLooks)
    }

    @Test
    fun aDeletedLookLeavesNoEditingReference() {
        val viewModel = deleteViewModel(FakeRepository(listOf(mondayLook, tuesdayLook)))
        viewModel.update { it.openLook(tuesdayLook).lookDeleted("a") }
        assertEquals(tuesdayLook, viewModel.state.openedLook)
        viewModel.update { it.lookDeleted("b") }
        assertNull(viewModel.state.openedLook)
    }

    @Test
    fun openingALookAndAskingToDeleteItAreIndependent() {
        val viewModel = deleteViewModel(FakeRepository(listOf(mondayLook)))
        viewModel.update { it.showWardrobeView(WardrobeView.OUTFITS).requestDeleteLook(mondayLook) }
        assertEquals(Screen.WARDROBE, viewModel.state.screen)
        assertNull(viewModel.state.openedLook)

        viewModel.update { it.cancelDeleteLook().openLook(mondayLook) }
        assertEquals(Screen.BUILDER, viewModel.state.screen)
        assertNull(viewModel.state.pendingLookDelete)
    }

    @Test
    fun selectingAfterVisitingASavedLookBuildsANewLookAndLeavesTheSavedOneAlone() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id, item("straight-jeans").garment.id), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())

        viewModel.update { it.openLook(look) }
        viewModel.update { it.requestLeaveBuilder().showWardrobeView(WardrobeView.ITEMS) }
        assertEquals(emptyList(), viewModel.state.draftIds)
        assertNull(viewModel.state.openedLook)

        viewModel.update { it.toggle(tote).openBuilder() }
        assertNull(viewModel.state.openedLook)
        assertEquals(listOf(tote.garment.id), viewModel.state.builderIds)

        viewModel.saveDraft("Tuesday")
        assertEquals(1, repository.creates)
        assertEquals(0, repository.updates)
        assertEquals(look, repository.looks.first { it.id == "a" })
        assertEquals(look, viewModel.state.savedLooks.first { it.id == "a" })
        assertEquals("id-1", viewModel.state.openedLook?.id)
    }

    @Test
    fun savingANewLookClearsTheSelection() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.toggle(shirt).openBuilder() }
        viewModel.saveDraft("Monday")
        assertEquals(listOf(shirt.garment.id), viewModel.state.builderIds)

        viewModel.update { it.requestLeaveBuilder() }
        assertEquals(Screen.WARDROBE, viewModel.state.screen)
        assertEquals(emptyList(), viewModel.state.draftIds)
        assertNull(viewModel.state.openedLook)
    }

    @Test
    fun updatingALookKeepsTheNewDraftAndLeavesNothingBehind() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        val jeans = item("straight-jeans")
        viewModel.update { it.toggle(jeans).openLook(look) }
        viewModel.update { it.toggle(tote).openSaveSheet().setSaveMode(SaveMode.UPDATE) }
        viewModel.save()
        assertEquals(1, repository.updates)
        assertEquals(listOf(shirt.garment.id, tote.garment.id), viewModel.state.savedLooks.single().garmentIds)
        assertFalse(viewModel.state.isDirty)

        viewModel.update { it.requestLeaveBuilder() }
        assertNull(viewModel.state.pendingDiscard)
        assertEquals(listOf(jeans.garment.id), viewModel.state.draftIds)
    }

    @Test
    fun savingAVariationGetsANewIdAndKeepsTheOriginal() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openLook(look) }
        viewModel.update { it.toggle(tote).openSaveSheet() }
        assertEquals("Monday — Variation", viewModel.state.saveSheet?.name)
        viewModel.save()

        assertEquals(1, repository.creates)
        assertEquals(0, repository.updates)
        assertEquals("id-1", viewModel.state.openedLook?.id)
        assertEquals(look, viewModel.state.savedLooks.first { it.id == "a" })
    }

    @Test
    fun emptyingASavedLookNeverWritesAndCanBeDiscarded() {
        val look = SavedLook("a", "Monday", listOf(shirt.garment.id), 1, 1)
        val repository = FakeRepository(listOf(look))
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openLook(look).toggle(shirt) }
        assertTrue(viewModel.state.isEditingEmptyLook)
        assertFalse(viewModel.state.canSaveDraft)
        viewModel.update { it.openSaveSheet() }
        viewModel.save()
        assertNull(viewModel.state.saveSheet)

        viewModel.update { it.restoreSavedLook() }
        assertEquals(look.garmentIds, viewModel.state.builderIds)
        assertFalse(viewModel.state.isDirty)
        assertEquals(look, viewModel.state.openedLook)
        assertEquals(0, repository.creates)
        assertEquals(0, repository.updates)
        assertEquals(look, repository.looks.single())
    }

    private fun WardrobeViewModel.fillForm() = update {
        it.updateGarmentForm { form ->
            form.copy(name = "Field Jacket", category = GarmentCategory.OUTERWEAR, color = "Green")
        }
    }

    @Test
    fun userGarmentsLoadAlongsideSamplesAndAreNotLabelledSample() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(userGarment("mine"))), FakePhotoStore())
        val mine = viewModel.state.items.first { it.garment.id == "mine" }
        assertFalse(mine.isSample)
        assertEquals(sampleWardrobe.size + 1, viewModel.state.availableItems.size)
        assertTrue(viewModel.state.items.filter { it.garment.id != "mine" }.all { it.isSample })
    }

    @Test
    fun garmentLoadFailureIsReportedWithoutClaimingPiecesAreMissing() {
        val garments = FakeGarmentRepository().apply { failure = IllegalStateException() }
        val look = SavedLook("a", "Monday", listOf("mine"), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), garments, FakePhotoStore())
        assertEquals(LooksStatus.FAILED, viewModel.state.looksStatus)
        assertEquals(sampleWardrobe, viewModel.state.items)
    }

    @Test
    fun invalidFormShowsErrorsAndWritesNothing() {
        val garments = FakeGarmentRepository()
        val viewModel = WardrobeViewModel(FakeRepository(), garments, FakePhotoStore())
        viewModel.update { it.openAddGarment() }
        viewModel.submitGarmentForm()
        assertEquals(true, viewModel.state.garmentForm?.showErrors)
        assertEquals(0, garments.creates)
        assertEquals(Screen.GARMENT_FORM, viewModel.state.screen)
    }

    @Test
    fun createdGarmentCanBeAddedToTheDraftWithExistingRules() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(), FakePhotoStore())
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        viewModel.submitGarmentForm()
        assertEquals(Screen.WARDROBE, viewModel.state.screen)
        val mine = viewModel.state.availableItems.first { !it.isSample }
        val coat = item("wool-coat")
        viewModel.update { it.toggle(coat).toggle(mine) }
        assertEquals(listOf(mine.garment.id), viewModel.state.draftIds)
    }

    @Test
    fun repeatedGarmentSubmissionCreatesOne() {
        val gate = CompletableDeferred<Unit>()
        val garments = FakeGarmentRepository().apply { this.gate = gate }
        val viewModel = WardrobeViewModel(FakeRepository(), garments, FakePhotoStore())
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        viewModel.submitGarmentForm()
        viewModel.submitGarmentForm()
        viewModel.update { it.closeGarmentForm() }
        assertEquals(Screen.GARMENT_FORM, viewModel.state.screen)
        gate.complete(Unit)
        assertEquals(1, garments.creates)
    }

    @Test
    fun failedGarmentSaveKeepsTheForm() {
        val garments = FakeGarmentRepository()
        val viewModel = WardrobeViewModel(FakeRepository(), garments, FakePhotoStore())
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        garments.failure = IllegalStateException()
        viewModel.submitGarmentForm()
        val form = viewModel.state.garmentForm
        assertEquals("Field Jacket", form?.name)
        assertEquals(false, form?.saving)
        assertTrue(form?.error != null)

        garments.failure = InvalidGarmentException("This piece no longer exists.")
        viewModel.submitGarmentForm()
        assertEquals("This piece no longer exists.", viewModel.state.garmentForm?.error)
    }

    @Test
    fun editingKeepsIdentityAndLocksCategoryWhenPieceIsInALook() {
        val mine = userGarment("mine")
        val look = SavedLook("a", "Monday", listOf("mine"), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)), FakePhotoStore())
        val item = viewModel.state.items.first { it.garment.id == "mine" }
        viewModel.update { it.openEditGarment(item) }
        assertEquals(true, viewModel.state.garmentForm?.categoryLocked)
        viewModel.update { it.updateGarmentForm { form -> form.copy(name = "Chore Coat") } }
        viewModel.submitGarmentForm()
        val edited = viewModel.state.items.first { it.garment.id == "mine" }
        assertEquals("Chore Coat", edited.name)
        assertEquals(viewModel.state.items.size, viewModel.state.items.distinctBy { it.garment.id }.size)
    }

    @Test
    fun archivedPieceLeavesNewCompositionsButStillResolvesInSavedLook() {
        val mine = userGarment("mine")
        val look = SavedLook("a", "Monday", listOf("mine"), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)), FakePhotoStore())
        val item = viewModel.state.items.first { it.garment.id == "mine" }
        viewModel.update { it.openEditGarment(item) }
        viewModel.setEditedGarmentArchived(true)

        val state = viewModel.state
        assertTrue(state.availableItems.none { it.garment.id == "mine" })
        assertTrue(state.itemsIn(GarmentCategory.OUTERWEAR).none { it.garment.id == "mine" })
        assertEquals(listOf("mine"), state.itemsFor(look).map { it.garment.id })
        assertEquals(1, state.archivedPieceCount(look))
        assertEquals(0, state.missingPieceCount(look))
    }

    @Test
    fun archivingRemovesAPieceFromANewDraftButNotFromAnOpenedLook() {
        val mine = userGarment("mine")
        val look = SavedLook("a", "Monday", listOf("mine"), 1, 1)
        val draftOnly = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(mine)), FakePhotoStore())
        val draftItem = draftOnly.state.items.first { it.garment.id == "mine" }
        draftOnly.update { it.toggle(draftItem).openEditGarment(draftItem) }
        draftOnly.setEditedGarmentArchived(true)
        assertEquals(emptyList(), draftOnly.state.draftIds)

        val reopened = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)), FakePhotoStore())
        val lookItem = reopened.state.items.first { it.garment.id == "mine" }
        reopened.update { it.openLook(look).requestLeaveBuilder().openEditGarment(lookItem) }
        reopened.setEditedGarmentArchived(true)
        assertEquals(listOf("mine"), reopened.state.savedLooks.single().garmentIds)
        reopened.update { it.openLook(look) }
        assertEquals(listOf("mine"), reopened.state.builderIds)
        assertFalse(reopened.state.isDirty)
    }

    private val picked = PhotoPickResult.Picked(PickedPhoto("picked"))

    private fun photoViewModel(photos: FakePhotoStore, garments: FakeGarmentRepository = FakeGarmentRepository()) =
        WardrobeViewModel(FakeRepository(), garments, photos)

    @Test
    fun cancelledPickChangesNothing() {
        val viewModel = photoViewModel(FakePhotoStore())
        viewModel.update { it.openAddGarment() }
        viewModel.onPhotoPicked(PhotoPickResult.Cancelled)
        assertNull(viewModel.state.garmentForm?.photoId)
        assertNull(viewModel.state.garmentForm?.error)
    }

    @Test
    fun unreadablePhotoShowsAnErrorAndKeepsTheForm() {
        val photos = FakePhotoStore().apply { importFailure = IllegalStateException() }
        val viewModel = photoViewModel(photos)
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        viewModel.onPhotoPicked(picked)
        val form = viewModel.state.garmentForm
        assertEquals("Field Jacket", form?.name)
        assertNull(form?.photoId)
        assertEquals(false, form?.importingPhoto)
        assertTrue(form?.error != null)

        viewModel.onPhotoPicked(PhotoPickResult.Failed)
        assertTrue(viewModel.state.garmentForm?.error != null)
    }

    @Test
    fun savingANewGarmentKeepsItsPhoto() {
        val photos = FakePhotoStore()
        val viewModel = photoViewModel(photos)
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        viewModel.onPhotoPicked(picked)
        viewModel.submitGarmentForm()
        assertEquals("photo-1", viewModel.state.availableItems.first { !it.isSample }.photoId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun cancellingDeletesAStagedPhotoButNeverTheSavedOne() {
        val photos = FakePhotoStore(mutableSetOf("saved"))
        val mine = userGarment("mine").copy(photoId = "saved")
        val viewModel = photoViewModel(photos, FakeGarmentRepository(listOf(mine)))
        val item = viewModel.state.items.first { it.garment.id == "mine" }
        viewModel.update { it.openEditGarment(item) }
        viewModel.onPhotoPicked(picked)
        assertEquals("photo-1", viewModel.state.garmentForm?.photoId)
        viewModel.cancelGarmentForm()
        assertEquals(listOf("photo-1"), photos.deleted)
        assertEquals("saved", viewModel.state.items.first { it.garment.id == "mine" }.photoId)
    }

    @Test
    fun removeAppliesOnlyWhenSaved() {
        val photos = FakePhotoStore(mutableSetOf("saved"))
        val mine = userGarment("mine").copy(photoId = "saved")
        val viewModel = photoViewModel(photos, FakeGarmentRepository(listOf(mine)))
        val item = viewModel.state.items.first { it.garment.id == "mine" }
        viewModel.update { it.openEditGarment(item) }
        viewModel.removeFormPhoto()
        assertEquals(PhotoChange.Remove, viewModel.state.garmentForm?.photoChange)
        assertEquals(emptyList(), photos.deleted)
        viewModel.cancelGarmentForm()
        assertEquals("saved", viewModel.state.items.first { it.garment.id == "mine" }.photoId)

        viewModel.update { it.openEditGarment(item) }
        viewModel.removeFormPhoto()
        viewModel.submitGarmentForm()
        assertNull(viewModel.state.items.first { it.garment.id == "mine" }.photoId)
    }

    @Test
    fun pickingAgainDeletesThePreviousStagedPhotoOnly() {
        val photos = FakePhotoStore()
        val viewModel = photoViewModel(photos)
        viewModel.update { it.openAddGarment() }
        viewModel.onPhotoPicked(picked)
        viewModel.onPhotoPicked(picked)
        assertEquals("photo-2", viewModel.state.garmentForm?.photoId)
        assertEquals(listOf("photo-1"), photos.deleted)
    }

    @Test
    fun failedSaveKeepsTheStagedPhotoForRetry() {
        val photos = FakePhotoStore()
        val garments = FakeGarmentRepository()
        val viewModel = photoViewModel(photos, garments)
        viewModel.update { it.openAddGarment() }
        viewModel.fillForm()
        viewModel.onPhotoPicked(picked)
        garments.failure = IllegalStateException()
        viewModel.submitGarmentForm()
        assertEquals("photo-1", viewModel.state.garmentForm?.photoId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun archivingFromTheFormDiscardsAStagedPhotoAndKeepsTheSavedOne() {
        val photos = FakePhotoStore(mutableSetOf("saved"))
        val mine = userGarment("mine").copy(photoId = "saved")
        val viewModel = photoViewModel(photos, FakeGarmentRepository(listOf(mine)))
        val item = viewModel.state.items.first { it.garment.id == "mine" }
        viewModel.update { it.openEditGarment(item) }
        viewModel.onPhotoPicked(picked)
        viewModel.setEditedGarmentArchived(true)
        assertEquals(listOf("photo-1"), photos.deleted)
        assertEquals("saved", viewModel.state.items.first { it.garment.id == "mine" }.photoId)
    }

    @Test
    fun orphanedPhotosAreSweptOnceAtStartup() {
        val garments = FakeGarmentRepository()
        val viewModel = photoViewModel(FakePhotoStore(), garments)
        viewModel.loadRecords()
        assertEquals(1, garments.sweeps)
    }

    @Test
    fun leavingAnUntouchedFormDoesNotAsk() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(userGarment("mine"))), FakePhotoStore())
        viewModel.update { it.openEditGarment(it.items.first { item -> item.garment.id == "mine" }) }
        viewModel.requestCancelGarmentForm()
        assertEquals(null, viewModel.state.garmentForm)

        viewModel.update { it.openAddGarment() }
        viewModel.requestCancelGarmentForm()
        assertEquals(null, viewModel.state.garmentForm)
    }

    @Test
    fun leavingAnEditedFormAsksAndKeepsTheEdits() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(userGarment("mine"))), FakePhotoStore())
        viewModel.update { it.openEditGarment(it.items.first { item -> item.garment.id == "mine" }) }
        viewModel.update { it.updateGarmentForm { form -> form.copy(brand = "Acme") } }

        viewModel.requestCancelGarmentForm()
        assertEquals(true, viewModel.state.garmentForm?.confirmingDiscard)

        viewModel.update { it.keepEditingGarmentForm() }
        assertEquals(false, viewModel.state.garmentForm?.confirmingDiscard)
        assertEquals("Acme", viewModel.state.garmentForm?.brand)

        viewModel.requestCancelGarmentForm()
        viewModel.cancelGarmentForm()
        assertEquals(null, viewModel.state.garmentForm)
    }

    @Test
    fun aRemovedPhotoOrNewFormEntryCountsAsAChange() {
        val form = GarmentFormState()
        assertFalse(form.isDirty)
        assertTrue(form.copy(name = "Coat").isDirty)
        assertTrue(form.copy(color = "Black").isDirty)
        assertTrue(form.copy(photoId = "staged").isDirty)
    }
}
