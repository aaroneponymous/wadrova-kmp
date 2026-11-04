package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportStatusTest {
    private val base = GarmentImport("kmp-imp-1", "photo", "ab".repeat(32), 10, ImportState.UPLOAD_PENDING, createdAt = 1, updatedAt = 1)

    private fun status(state: ImportState, problem: ImportProblem? = null, serverStatus: String? = null, count: Int? = null, message: String? = null) =
        base.copy(state = state, problem = problem, serverStatus = serverStatus, candidateCount = count, problemMessage = message).status()

    @Test
    fun everyStateHasPlainLanguageAndNoPercentages() {
        assertEquals("Uploading", status(ImportState.UPLOADING).title)
        assertEquals("Waiting for connection", status(ImportState.UPLOAD_PENDING, ImportProblem.OFFLINE).title)
        assertEquals("Connect to continue", status(ImportState.UPLOAD_PENDING, ImportProblem.NOT_CONNECTED).title)
        assertEquals("Finding your pieces", status(ImportState.PROCESSING, serverStatus = "queued").title)
        assertEquals("Reconnect to check progress", status(ImportState.PROCESSING, ImportProblem.ACCESS_REJECTED).title)
        assertEquals("Ready to review", status(ImportState.READY, count = 2).title)
        assertEquals("No pieces found", status(ImportState.READY, count = 0).title)
        assertEquals("Couldn't process photo", status(ImportState.FAILED, message = "Safe text").title)
        assertEquals("Safe text", status(ImportState.FAILED, message = "Safe text").detail)
        assertEquals("Couldn't upload photo", status(ImportState.REJECTED).title)
        assertEquals("Cancelling", status(ImportState.CANCEL_PENDING).title)
        assertEquals("No longer available", status(ImportState.CLOSED).title)
        ImportState.entries.forEach { state ->
            val shown = status(state)
            assertFalse("%" in shown.title + shown.detail.orEmpty())
        }
    }

    @Test
    fun onlyMovingImportsShowAsWorking() {
        assertTrue(status(ImportState.UPLOADING).working)
        assertTrue(status(ImportState.PROCESSING).working)
        assertFalse(status(ImportState.READY, count = 1).working)
        assertFalse(status(ImportState.UPLOAD_PENDING, ImportProblem.NOT_CONNECTED).working)
        assertFalse(status(ImportState.FAILED).working)
    }

    @Test
    fun retryAndConnectAreOfferedOnlyWhereTheyHelp() {
        assertTrue(base.copy(problem = ImportProblem.OFFLINE).canRetry)
        assertFalse(base.canRetry)
        assertFalse(base.copy(state = ImportState.REJECTED, problem = ImportProblem.OFFLINE).canRetry)
        assertTrue(base.copy(problem = ImportProblem.ACCESS_REJECTED).needsConnection)
        assertFalse(base.copy(problem = ImportProblem.OFFLINE).needsConnection)
    }

    @Test
    fun addAPieceOpensTheChooserAndManualStillOpensTheForm() {
        val wardrobe = WardrobeUiState(items = emptyList())
        val choosing = wardrobe.openAddPieceChooser()
        assertTrue(choosing.choosingAddMethod)
        assertEquals(Screen.WARDROBE, choosing.screen)
        assertFalse(choosing.closeAddPieceChooser().choosingAddMethod)

        val manual = choosing.openAddGarment()
        assertFalse(manual.choosingAddMethod)
        assertEquals(Screen.GARMENT_FORM, manual.screen)
        assertNull(manual.garmentForm?.editing)
    }

    @Test
    fun importPagesHideTheTabBar() {
        assertTrue(showsPrimaryBar(PrimaryTab.WARDROBE, Screen.WARDROBE, MirrorRoute.HOME, extracting = false))
        assertFalse(showsPrimaryBar(PrimaryTab.WARDROBE, Screen.WARDROBE, MirrorRoute.HOME, extracting = false, importOpen = true))
    }
}
