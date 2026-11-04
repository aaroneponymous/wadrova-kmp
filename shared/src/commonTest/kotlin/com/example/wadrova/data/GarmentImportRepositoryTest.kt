package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import com.example.wadrova.domain.ServerImport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GarmentImportRepositoryTest {
    private val databaseName = "garment_import_test"
    private val sha = "ab".repeat(32)
    private var now = 100L
    private lateinit var driver: SqlDriver
    private lateinit var imports: GarmentImportRepository

    private fun open() {
        driver = openTestDatabase(databaseName)
        imports = SqlDelightGarmentImportRepository(driver, nowMillis = { now })
    }

    @BeforeTest
    fun setUp() {
        deleteTestDatabase(databaseName)
        open()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        deleteTestDatabase(databaseName)
    }

    @Test
    fun aNewImportWaitsToUploadAndSurvivesReopening() = runTest {
        val (created, isNew) = imports.createOrReuse("kmp-imp-1", "photo-1", sha, 1234)
        assertTrue(isNew)
        assertEquals(ImportState.UPLOAD_PENDING, created.state)
        assertEquals(0, created.uploadAttempts)
        assertEquals(1234, created.sourceByteSize)

        driver.close()
        open()
        assertEquals(listOf(created), imports.getAll())
    }

    @Test
    fun theSameBytesReuseAnOpenImportButNotAFinishedOne() = runTest {
        imports.createOrReuse("kmp-imp-1", "photo-1", sha, 10)
        val (again, isNew) = imports.createOrReuse("kmp-imp-2", "photo-2", sha, 10)
        assertFalse(isNew)
        assertEquals("kmp-imp-1", again.id)
        assertEquals(1, imports.getAll().size)

        imports.update("kmp-imp-1") { current, at -> current.rejected("No", at) }
        val (fresh, freshIsNew) = imports.createOrReuse("kmp-imp-3", "photo-3", sha, 10)
        assertTrue(freshIsNew)
        assertEquals("kmp-imp-3", fresh.id)
    }

    @Test
    fun updatesApplyToTheStoredRowSoACancelIsNeverLost() = runTest {
        imports.createOrReuse("kmp-imp-1", "photo-1", sha, 10)
        imports.update("kmp-imp-1") { current, at -> current.uploadStarted(at) }
        // The user cancels while the upload is out, then the upload's response arrives.
        imports.update("kmp-imp-1") { current, at -> current.cancelRequested(at) }
        now = 200
        val after = imports.update("kmp-imp-1") { current, at -> current.withServer(remote("queued"), at) }!!

        assertEquals(ImportState.CANCEL_PENDING, after.state)
        assertEquals("imp_1", after.serverImportId)
        assertEquals("ses_1", after.serverSessionId)
        assertEquals(1, after.uploadAttempts)
        assertEquals(200, after.updatedAt)
        assertEquals(after, imports.get("kmp-imp-1"))
    }

    @Test
    fun everyFieldRoundTrips() = runTest {
        imports.createOrReuse("kmp-imp-1", "photo-1", sha, 10)
        imports.update("kmp-imp-1") { current, at -> current.withServer(remote("ready", candidates = 3, poll = 4), at).withProblem(ImportProblem.OFFLINE, at) }
        driver.close()
        open()
        val stored = imports.get("kmp-imp-1")!!
        assertEquals(ImportState.READY, stored.state)
        assertEquals(3, stored.candidateCount)
        assertEquals(4, stored.pollAfterSeconds)
        assertEquals("ready", stored.serverStatus)
        assertEquals(ImportProblem.OFFLINE, stored.problem)
    }

    @Test
    fun aRelaunchSendsInterruptedUploadsAgain() = runTest {
        imports.createOrReuse("kmp-imp-1", "photo-1", sha, 10)
        imports.createOrReuse("kmp-imp-2", "photo-2", "cd".repeat(32), 10)
        imports.update("kmp-imp-1") { current, at -> current.uploadStarted(at) }
        imports.update("kmp-imp-2") { current, at -> current.withServer(remote("processing"), at) }
        driver.close()
        open()

        imports.resetInterruptedUploads()
        assertEquals(ImportState.UPLOAD_PENDING, imports.get("kmp-imp-1")!!.state)
        assertEquals(1, imports.get("kmp-imp-1")!!.uploadAttempts)
        assertEquals(ImportState.PROCESSING, imports.get("kmp-imp-2")!!.state)
    }

    @Test
    fun removingAnImportAndMissingRows() = runTest {
        imports.createOrReuse("kmp-imp-1", "photo-1", sha, 10)
        imports.delete("kmp-imp-1")
        assertNull(imports.get("kmp-imp-1"))
        assertNull(imports.update("kmp-imp-1") { current, at -> current.uploadStarted(at) })
    }

    @Test
    fun theApiAddressIsASettingAndNoTokenIsStoredHere() = runTest {
        assertNull(imports.apiUrl())
        imports.saveApiUrl("https://wadrova.example")
        driver.close()
        open()
        assertEquals("https://wadrova.example", imports.apiUrl())
        val keys = driver.executeQuery(null, "SELECT key FROM app_setting", { cursor ->
            val all = mutableListOf<String>()
            while (cursor.next().value) all += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(all)
        }, 0).value
        assertEquals(listOf("wadrova_api_url"), keys)
    }

    private fun remote(status: String, candidates: Int? = null, poll: Int? = 2) =
        ServerImport("imp_1", "kmp-imp-1", status, "ses_1", candidates, null, poll)
}
