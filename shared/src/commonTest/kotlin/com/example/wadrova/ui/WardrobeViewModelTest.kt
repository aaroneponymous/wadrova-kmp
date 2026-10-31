package com.example.wadrova.ui

import com.example.wadrova.data.InvalidLookException
import com.example.wadrova.data.SavedLookRepository
import com.example.wadrova.domain.SavedLook
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
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)))
        assertEquals(listOf(look), viewModel.state.savedLooks)
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun loadFailureIsReportedAndCanBeRetried() {
        val repository = FakeRepository().apply { failure = IllegalStateException() }
        val viewModel = WardrobeViewModel(repository)
        assertEquals(LooksStatus.FAILED, viewModel.state.looksStatus)
        repository.failure = null
        viewModel.loadLooks()
        assertEquals(LooksStatus.READY, viewModel.state.looksStatus)
    }

    @Test
    fun savingMakesTheDraftCleanAndAddsTheLook() {
        val viewModel = WardrobeViewModel(FakeRepository())
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
        val viewModel = WardrobeViewModel(repository)
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
        val viewModel = WardrobeViewModel(repository)
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
        val viewModel = WardrobeViewModel(repository)
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
        val viewModel = WardrobeViewModel(repository)
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
        val viewModel = WardrobeViewModel(FakeRepository(looks))
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
        val viewModel = WardrobeViewModel(repository)
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
        val viewModel = WardrobeViewModel(FakeRepository(listOf(look)))
        viewModel.update { it.toggle(shirt).requestOpen(look) }
        assertEquals(listOf(shirt.garment.id), viewModel.state.draftIds)
        assertEquals(look, viewModel.state.pendingDiscard?.look)

        viewModel.update { it.keepDraft() }
        assertEquals(listOf(shirt.garment.id), viewModel.state.draftIds)

        viewModel.update { it.requestOpen(look).confirmDiscard() }
        assertEquals(look.garmentIds, viewModel.state.draftIds)
        assertEquals(look, viewModel.state.openedLook)
    }
}
