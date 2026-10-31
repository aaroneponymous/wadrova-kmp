package com.example.wadrova.ui

import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentRepository
import com.example.wadrova.data.InvalidGarmentException
import com.example.wadrova.data.InvalidLookException
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

        override fun close() = Unit
    }

    private class FakeGarmentRepository(var garments: List<UserGarment> = emptyList()) : GarmentRepository {
        var creates = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getAll(): List<UserGarment> {
            failure?.let { throw it }
            return garments
        }

        override suspend fun create(details: GarmentDetails): UserGarment {
            creates++
            gate?.await()
            failure?.let { throw it }
            return details.toGarment("user-$creates", archived = false).also { garments = listOf(it) + garments }
        }

        override suspend fun update(id: String, details: GarmentDetails): UserGarment {
            failure?.let { throw it }
            return details.toGarment(id, archived = false)
        }

        override suspend fun setArchived(id: String, archived: Boolean): UserGarment {
            failure?.let { throw it }
            return garments.first { it.garment.id == id }.copy(archived = archived)
        }

        private fun GarmentDetails.toGarment(id: String, archived: Boolean) =
            UserGarment(Garment(id, category), name, color, brand, material, archived)
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
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository())
        assertEquals(listOf(look), viewModel.state.savedLooks)
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun loadFailureIsReportedAndCanBeRetried() {
        val repository = FakeRepository().apply { failure = IllegalStateException() }
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
        assertEquals(LooksStatus.FAILED, viewModel.state.looksStatus)
        repository.failure = null
        viewModel.loadRecords()
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun savingMakesTheDraftCleanAndAddsTheLook() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository())
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
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
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
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
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
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
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
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
        viewModel.update { it.requestOpen(look) }
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
        val viewModel = WardrobeViewModel(FakeRepository(looks), FakeGarmentRepository())
        viewModel.update { it.requestOpen(looks[0]) }
        assertEquals(Screen.BUILDER, viewModel.state.screen)
        assertEquals(listOf(shirt.garment.id), viewModel.state.draftIds)
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
        val viewModel = WardrobeViewModel(repository, FakeGarmentRepository())
        viewModel.update { it.requestOpen(look) }
        viewModel.update { it.toggle(tote).openSaveSheet().setSaveMode(SaveMode.UPDATE) }
        viewModel.save()
        assertEquals(1, repository.updates)
        assertEquals(0, repository.creates)
        assertEquals("a", viewModel.state.openedLook?.id)
        assertEquals(1, viewModel.state.savedLooks.size)
    }

    @Test
    fun openingALookAsksBeforeDiscardingUnsavedChanges() {
        val look = SavedLook("a", "Monday", listOf(tote.garment.id), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository())
        viewModel.update { it.toggle(shirt).requestOpen(look) }
        assertEquals(listOf(shirt.garment.id), viewModel.state.draftIds)
        assertEquals(look, viewModel.state.pendingDiscard?.look)

        viewModel.update { it.keepDraft() }
        assertEquals(listOf(shirt.garment.id), viewModel.state.draftIds)

        viewModel.update { it.requestOpen(look).confirmDiscard() }
        assertEquals(look.garmentIds, viewModel.state.draftIds)
        assertEquals(look, viewModel.state.openedLook)
    }

    private fun WardrobeViewModel.fillForm() = update {
        it.updateGarmentForm { form ->
            form.copy(name = "Field Jacket", category = GarmentCategory.OUTERWEAR, color = "Green")
        }
    }

    @Test
    fun userGarmentsLoadAlongsideSamplesAndAreNotLabelledSample() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(userGarment("mine"))))
        val mine = viewModel.state.items.first { it.garment.id == "mine" }
        assertFalse(mine.isSample)
        assertEquals(sampleWardrobe.size + 1, viewModel.state.availableItems.size)
        assertTrue(viewModel.state.items.filter { it.garment.id != "mine" }.all { it.isSample })
    }

    @Test
    fun garmentLoadFailureIsReportedWithoutClaimingPiecesAreMissing() {
        val garments = FakeGarmentRepository().apply { failure = IllegalStateException() }
        val look = SavedLook("a", "Monday", listOf("mine"), 1, 1)
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), garments)
        assertEquals(LooksStatus.FAILED, viewModel.state.looksStatus)
        assertEquals(sampleWardrobe, viewModel.state.items)
    }

    @Test
    fun invalidFormShowsErrorsAndWritesNothing() {
        val garments = FakeGarmentRepository()
        val viewModel = WardrobeViewModel(FakeRepository(), garments)
        viewModel.update { it.openAddGarment() }
        viewModel.submitGarmentForm()
        assertEquals(true, viewModel.state.garmentForm?.showErrors)
        assertEquals(0, garments.creates)
        assertEquals(Screen.GARMENT_FORM, viewModel.state.screen)
    }

    @Test
    fun createdGarmentCanBeAddedToTheDraftWithExistingRules() {
        val viewModel = WardrobeViewModel(FakeRepository(), FakeGarmentRepository())
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
        val viewModel = WardrobeViewModel(FakeRepository(), garments)
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
        val viewModel = WardrobeViewModel(FakeRepository(), garments)
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
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)))
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
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)))
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
        val draftOnly = WardrobeViewModel(FakeRepository(), FakeGarmentRepository(listOf(mine)))
        val draftItem = draftOnly.state.items.first { it.garment.id == "mine" }
        draftOnly.update { it.toggle(draftItem).openEditGarment(draftItem) }
        draftOnly.setEditedGarmentArchived(true)
        assertEquals(emptyList(), draftOnly.state.draftIds)

        val reopened = WardrobeViewModel(FakeRepository(listOf(look)), FakeGarmentRepository(listOf(mine)))
        val lookItem = reopened.state.items.first { it.garment.id == "mine" }
        reopened.update { it.requestOpen(look).openEditGarment(lookItem) }
        reopened.setEditedGarmentArchived(true)
        assertEquals(listOf("mine"), reopened.state.draftIds)
        assertFalse(reopened.state.isDirty)
    }
}
