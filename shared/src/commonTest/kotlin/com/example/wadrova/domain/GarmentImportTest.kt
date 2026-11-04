package com.example.wadrova.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarmentImportTest {
    private val pending = GarmentImport("kmp-imp-1", "photo", "ab".repeat(32), 10, ImportState.UPLOAD_PENDING, createdAt = 1, updatedAt = 1)

    private fun remote(status: String, failure: String? = null, count: Int? = null) =
        ServerImport("imp_1", "kmp-imp-1", status, "ses_1", count, failure, 3)

    @Test
    fun serverStatusesMapToLocalStates() {
        assertEquals(ImportState.PROCESSING, pending.withServer(remote("queued"), 2).state)
        assertEquals(ImportState.PROCESSING, pending.withServer(remote("processing"), 2).state)
        assertEquals(ImportState.READY, pending.withServer(remote("ready", count = 4), 2).state)
        assertEquals(4, pending.withServer(remote("ready", count = 4), 2).candidateCount)
        assertEquals(ImportState.CLOSED, pending.withServer(remote("cancelled"), 2).state)
        assertEquals(ImportState.CLOSED, pending.withServer(remote("finalized"), 2).state)
        // The API says to treat an unknown status as "not ready".
        assertEquals(ImportState.PROCESSING, pending.withServer(remote("something_new"), 2).state)
    }

    @Test
    fun aFailureKeepsTheServersSafeMessage() {
        val failed = pending.withServer(remote("failed", failure = "This photo couldn't be read."), 2)
        assertEquals(ImportState.FAILED, failed.state)
        assertEquals("This photo couldn't be read.", failed.problemMessage)
        assertEquals(GarmentImport.PROCESSING_FAILED, pending.withServer(remote("failed"), 2).problemMessage)
    }

    @Test
    fun theServersViewClearsAProblemAndRecordsIds() {
        val next = pending.withProblem(ImportProblem.OFFLINE, 2).withServer(remote("queued"), 3)
        assertNull(next.problem)
        assertEquals("imp_1", next.serverImportId)
        assertEquals("ses_1", next.serverSessionId)
        assertEquals("queued", next.serverStatus)
        assertEquals(3, next.pollAfterSeconds)
    }

    @Test
    fun aRequestedCancelSurvivesLateResultsAndFailures() {
        val cancelling = pending.uploadStarted(2).cancelRequested(3)
        assertEquals(ImportState.CANCEL_PENDING, cancelling.withServer(remote("ready"), 4).state)
        assertEquals(ImportState.CANCEL_PENDING, cancelling.uploadInterrupted(ImportProblem.OFFLINE, 4).state)
        assertEquals(ImportState.CANCEL_PENDING, cancelling.rejected("No", 4).state)
    }

    @Test
    fun uploadsCountAttemptsAndMayExistOnServerOnceSent() {
        assertFalse(pending.mayExistOnServer)
        val sent = pending.uploadStarted(2)
        assertEquals(ImportState.UPLOADING, sent.state)
        assertEquals(1, sent.uploadAttempts)
        assertTrue(sent.mayExistOnServer)
        val back = sent.uploadInterrupted(ImportProblem.OFFLINE, 3)
        assertEquals(ImportState.UPLOAD_PENDING, back.state)
        assertEquals(ImportProblem.OFFLINE, back.problem)
        assertEquals(1, back.uploadAttempts)
    }
}
