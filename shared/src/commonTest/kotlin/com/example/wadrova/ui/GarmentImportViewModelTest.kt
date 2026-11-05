package com.example.wadrova.ui

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.data.ApiFailure
import com.example.wadrova.data.Credential
import com.example.wadrova.data.FakeCredentialStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.data.SqlDelightGarmentImportRepository
import com.example.wadrova.data.WadrovaApi
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.WadrovaConnection
import com.example.wadrova.data.deleteTestDatabase
import com.example.wadrova.data.openTestDatabase
import com.example.wadrova.data.sha256Hex
import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import com.example.wadrova.domain.ServerImport
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The import workflow against a fake server that behaves like wadrova-backend's idempotent import API. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarmentImportViewModelTest {
    private val databaseName = "garment_import_vm_test"
    private val dispatcher = StandardTestDispatcher()
    private val photoBytes = byteArrayOf(-1, -40, 7, 8, 9)
    private lateinit var driver: SqlDriver
    private lateinit var imports: SqlDelightGarmentImportRepository
    private val server = FakeWadrovaServer()
    private val photos = FakePhotoStore()
    private val credentials = FakeCredentialStore(mutableMapOf(Credential.WADROVA_ACCESS_TOKEN to "wdr_token"))
    private val models = mutableListOf<GarmentImportViewModel>()

    private fun open() {
        driver = openTestDatabase(databaseName)
        imports = SqlDelightGarmentImportRepository(driver, nowMillis = { dispatcher.scheduler.currentTime }, dispatcher = dispatcher)
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deleteTestDatabase(databaseName)
        open()
        photos.contents["photo-1"] = photoBytes
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
        deleteTestDatabase(databaseName)
    }

    /** Every test ends in the background, which stops polling, so the test scheduler can go idle. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        imports.saveApiUrl("https://api.test")
        try {
            body()
        } finally {
            models.forEach { it.onBackground() }
        }
    }

    private fun TestScope.viewModel(): GarmentImportViewModel {
        val model = GarmentImportViewModel(imports, server, credentials, photos, random = Random(7))
        models += model
        runCurrent()
        return model
    }

    private fun TestScope.pick(model: GarmentImportViewModel, location: String = "content://photo") {
        model.onPhotoPicked(PhotoPickResult.Picked(PickedPhoto(location)))
        runCurrent()
    }

    private fun only(model: GarmentImportViewModel): GarmentImport = model.state.imports.single()

    // Picking

    @Test
    fun cancellingThePickerCreatesNothing() = test {
        val model = viewModel()
        model.onPhotoPicked(PhotoPickResult.Cancelled)
        runCurrent()
        assertNull(model.state.route)
        assertTrue(model.state.imports.isEmpty())
        assertTrue(photos.stored.isEmpty())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aPickerFailureOrUnreadablePhotoShowsAnErrorAndCreatesNothing() = test {
        val model = viewModel()
        model.onPhotoPicked(PhotoPickResult.Failed)
        runCurrent()
        assertEquals(ImportRoute.Preparing, model.state.route)
        assertEquals(GarmentImportViewModel.PHOTO_FAILED, model.state.prepareError)

        photos.failingLocations += "content://broken"
        pick(model, "content://broken")
        assertEquals(GarmentImportViewModel.PHOTO_FAILED, model.state.prepareError)
        assertTrue(model.state.imports.isEmpty())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aPhotoThatCantBeReopenedIsNotImported() = test {
        photos.contents["photo-1"] = ByteArray(0)
        val model = viewModel()
        pick(model)
        assertEquals(GarmentImportViewModel.PHOTO_FAILED, model.state.prepareError)
        assertTrue(model.state.imports.isEmpty())
        assertEquals(listOf("photo-1"), photos.deleted)
    }

    @Test
    fun pickingAPhotoKeepsAPrivateCopyAndUploadsItsExactBytesStraightAway() = test {
        val model = viewModel()
        pick(model)

        val import = only(model)
        assertEquals("photo-1", import.photoId)
        assertEquals(sha256Hex(photoBytes), import.sourceSha256)
        assertEquals(photoBytes.size.toLong(), import.sourceByteSize)
        assertEquals(ImportRoute.Progress(import.id), model.state.route)
        assertTrue(import.id.startsWith("kmp-imp-"))

        val (key, sha, bytes) = server.uploads.single()
        assertEquals(import.id, key)
        assertEquals(import.sourceSha256, sha)
        assertEquals(photoBytes.toList(), bytes)
        assertEquals(listOf("wdr_token"), server.tokens.distinct())

        assertEquals(ImportState.PROCESSING, import.state)
        assertEquals("imp_1", import.serverImportId)
        assertEquals("ses_imp_1", import.serverSessionId)
        assertEquals(1, import.uploadAttempts)
        assertTrue("photo-1" in photos.stored)
    }

    @Test
    fun pickingTheSamePhotoAgainReopensItsImport() = test {
        photos.contents["photo-2"] = photoBytes
        val model = viewModel()
        pick(model)
        model.close()
        pick(model)

        assertEquals(1, model.state.imports.size)
        assertEquals(ImportRoute.Progress(only(model).id), model.state.route)
        assertEquals(listOf("photo-2"), photos.deleted)
        assertEquals(1, server.count("create"))
    }

    // Uploading

    @Test
    fun aLostResponseIsResentWithTheSameKeyAndBytesAndMakesOneImport() = test {
        server.loseNextCreateResponse = true
        val model = viewModel()
        pick(model)
        assertEquals(ImportState.UPLOAD_PENDING, only(model).state)
        assertEquals(ImportProblem.OFFLINE, only(model).problem)
        assertEquals("Waiting for connection", only(model).status().title)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, server.uploads.size)
        assertEquals(1, server.uploads.map { it.first }.distinct().size)
        assertEquals(1, server.uploads.map { it.third }.distinct().size)
        assertEquals(1, server.imports.size)
        assertEquals(ImportState.PROCESSING, only(model).state)
        assertEquals("imp_1", only(model).serverImportId)
    }

    @Test
    fun timeoutsBackOffAndStopAfterABoundedNumberOfAttemptsUntilRetried() = test {
        server.alwaysUnreachable = true
        val model = viewModel()
        pick(model)
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(GarmentImportViewModel.MAX_CONSECUTIVE_FAILURES, server.count("create"))
        assertTrue(only(model).canRetry)
        assertEquals(ImportState.UPLOAD_PENDING, only(model).state)

        server.alwaysUnreachable = false
        model.retry(only(model).id)
        runCurrent()
        assertEquals(ImportState.PROCESSING, only(model).state)
        assertEquals(1, server.imports.size)
    }

    @Test
    fun aTemporarilyUnavailableServerIsRetried() = test {
        server.createFailures += WadrovaApiException(ApiFailure.UNAVAILABLE, "busy")
        val model = viewModel()
        pick(model)
        assertEquals(ImportProblem.SERVER_UNAVAILABLE, only(model).problem)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ImportState.PROCESSING, only(model).state)
    }

    @Test
    fun withoutATokenNothingIsSentUntilAConnectionIsSaved() = test {
        credentials.values.clear()
        val model = viewModel()
        pick(model)
        assertTrue(server.calls.isEmpty())
        assertEquals(ImportState.UPLOAD_PENDING, only(model).state)
        assertEquals(ImportProblem.NOT_CONNECTED, only(model).problem)
        assertTrue(only(model).needsConnection)

        model.openConnection()
        runCurrent()
        model.updateConnection(model.state.connection.copy(url = "https://api.test/", token = " wdr_new "))
        model.saveConnection()
        runCurrent()
        assertEquals("wdr_new", credentials.values[Credential.WADROVA_ACCESS_TOKEN])
        assertEquals("Connected as Drill.", model.state.connection.message)
        assertEquals("", model.state.connection.token)
        assertTrue(model.state.connection.hasSavedToken)
        assertEquals(ImportState.PROCESSING, only(model).state)
        assertEquals(listOf("wdr_new"), server.tokens.distinct())
    }

    @Test
    fun theConnectionMustBeHttpsAndHaveAToken() = test {
        credentials.values.clear()
        val model = viewModel()
        model.openConnection()
        runCurrent()
        assertEquals("https://api.test", model.state.connection.url)
        model.updateConnection(model.state.connection.copy(url = "http://api.test", token = "wdr"))
        model.saveConnection()
        assertEquals("Enter an address that starts with https://.", model.state.connection.message)
        model.updateConnection(model.state.connection.copy(url = "https://api.test", token = ""))
        model.saveConnection()
        assertEquals("Enter your access token.", model.state.connection.message)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aRejectedTokenStopsWithoutLosingThePhoto() = test {
        server.createFailures += WadrovaApiException(ApiFailure.UNAUTHENTICATED, "no")
        val model = viewModel()
        pick(model)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.count("create"))
        assertEquals(ImportProblem.ACCESS_REJECTED, only(model).problem)
        assertEquals("Reconnect to continue", only(model).status().title)
        assertTrue("photo-1" in photos.stored)
    }

    @Test
    fun anUnsupportedImageIsRejectedForGood() = test {
        server.createFailures += WadrovaApiException(ApiFailure.REJECTED, "no", "unsupported_image")
        val model = viewModel()
        pick(model)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.count("create"))
        assertEquals(ImportState.REJECTED, only(model).state)
        assertEquals("Wadrova couldn't read this photo. Try a different one.", only(model).problemMessage)
    }

    @Test
    fun aMissingOrChangedLocalPhotoIsNeverUploaded() = test {
        val model = viewModel()
        credentials.values.clear()
        pick(model)
        photos.contents["photo-1"] = byteArrayOf(1)
        credentials.values[Credential.WADROVA_ACCESS_TOKEN] = "wdr_token"
        model.retry(only(model).id)
        runCurrent()
        assertTrue(server.uploads.isEmpty())
        assertEquals(ImportState.REJECTED, only(model).state)
        assertEquals(GarmentImportViewModel.PHOTO_MISSING, only(model).problemMessage)
    }

    // Processing

    @Test
    fun processingIsFollowedUntilReadyThenPollingStops() = test {
        val model = viewModel()
        pick(model)
        assertEquals("Your photo is in line to be processed.", only(model).status().detail)

        server.set("imp_1", "processing")
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals("processing", only(model).serverStatus)
        assertEquals("Wadrova is looking for the pieces in your photo.", only(model).status().detail)

        server.set("imp_1", "ready", count = 3)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ImportState.READY, only(model).state)
        assertEquals(3, only(model).candidateCount)
        assertEquals("3 pieces found", only(model).status().detail)

        val gets = server.count("get")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(gets, server.count("get"))
    }

    @Test
    fun aProcessingFailureShowsTheServersMessage() = test {
        val model = viewModel()
        pick(model)
        server.set("imp_1", "failed", failure = "We couldn't find clothing to process.")
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ImportState.FAILED, only(model).state)
        assertEquals("We couldn't find clothing to process.", only(model).status().detail)
    }

    @Test
    fun anImportTheServerNoLongerHasIsClosed() = test {
        val model = viewModel()
        pick(model)
        server.imports.clear()
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ImportState.CLOSED, only(model).state)
        assertEquals(GarmentImport.GONE_FROM_SERVER, only(model).problemMessage)
    }

    @Test
    fun pollingStopsInTheBackgroundAndResumesInTheForeground() = test {
        val model = viewModel()
        pick(model)
        model.onBackground()
        runCurrent()
        val gets = server.count("get")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(gets, server.count("get"))

        server.set("imp_1", "ready", count = 1)
        model.onForeground()
        runCurrent()
        assertEquals(ImportState.READY, only(model).state)
        assertEquals("1 piece found", only(model).status().detail)
    }

    @Test
    fun anUploadAlreadyRunningFinishesInTheBackground() = test {
        server.createGate = CompletableDeferred()
        val model = viewModel()
        pick(model)
        assertEquals(ImportState.UPLOADING, only(model).state)
        model.onBackground()
        server.createGate?.complete(Unit)
        runCurrent()
        assertEquals(ImportState.PROCESSING, only(model).state)
        assertEquals(0, server.count("get"))
    }

    // Relaunch

    @Test
    fun aRelaunchResendsAnInterruptedUploadAndRefreshesProcessingImports() = test {
        val first = viewModel()
        server.createGate = CompletableDeferred()
        pick(first)
        val interrupted = only(first)
        assertEquals(ImportState.UPLOADING, interrupted.state)

        // Another import was already with the server when the app was killed.
        photos.contents["photo-9"] = byteArrayOf(9)
        photos.stored += "photo-9"
        imports.createOrReuse("kmp-imp-old", "photo-9", sha256Hex(byteArrayOf(9)), 1)
        server.createGate = null
        server.imports["imp_old"] = ServerImport("imp_old", "kmp-imp-old", "ready", "ses_old", 2, null, null)
        imports.update("kmp-imp-old") { current, now ->
            current.uploadStarted(now).withServer(ServerImport("imp_old", "kmp-imp-old", "queued", "ses_old", null, null, 3), now)
        }

        // The process dies: the first model is gone and the database is reopened.
        first.onBackground()
        models.clear()
        driver.close()
        open()
        val relaunched = viewModel()

        val byId = relaunched.state.imports.associateBy { it.id }
        assertEquals(ImportState.PROCESSING, byId.getValue(interrupted.id).state)
        assertEquals(2, byId.getValue(interrupted.id).uploadAttempts)
        assertEquals(setOf(interrupted.id), server.uploads.map { it.first }.toSet())
        assertEquals(ImportState.READY, byId.getValue("kmp-imp-old").state)
        assertEquals(2, byId.getValue("kmp-imp-old").candidateCount)
    }

    // Cancelling and removing

    @Test
    fun cancellingBeforeAnythingWasSentIsLocal() = test {
        credentials.values.clear()
        val model = viewModel()
        pick(model)
        val id = only(model).id
        model.requestRemoval(id)
        assertEquals(id, model.state.pendingRemoval)
        model.confirmRemoval()
        runCurrent()
        assertTrue(model.state.imports.isEmpty())
        assertNull(model.state.route)
        assertEquals(listOf("photo-1"), photos.deleted)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun keepingAnImportChangesNothing() = test {
        val model = viewModel()
        pick(model)
        model.requestRemoval(only(model).id)
        model.keepImport()
        runCurrent()
        assertNull(model.state.pendingRemoval)
        assertEquals(1, model.state.imports.size)
        assertEquals(0, server.count("cancel"))
    }

    @Test
    fun cancellingASubmittedImportCancelsItOnTheServer() = test {
        val model = viewModel()
        pick(model)
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        runCurrent()
        assertEquals("cancelled", server.imports.getValue("imp_1").status)
        assertTrue(model.state.imports.isEmpty())
        assertEquals(listOf("photo-1"), photos.deleted)
    }

    @Test
    fun cancellingAfterALostResponseFindsTheImportOnTheServer() = test {
        server.loseNextCreateResponse = true
        val model = viewModel()
        pick(model)
        assertNull(only(model).serverImportId)
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, server.count("create"))
        assertEquals(1, server.count("list"))
        assertEquals("cancelled", server.imports.getValue("imp_1").status)
        assertTrue(model.state.imports.isEmpty())
    }

    @Test
    fun aCancelWaitsForTheConnectionAndIsKeptAcrossRelaunches() = test {
        val model = viewModel()
        pick(model)
        server.cancelFailures += List(GarmentImportViewModel.MAX_CONSECUTIVE_FAILURES) { WadrovaApiException(ApiFailure.UNREACHABLE, "x") }
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(ImportState.CANCEL_PENDING, only(model).state)
        assertEquals("Cancelling", only(model).status().title)

        model.onBackground()
        models.clear()
        driver.close()
        open()
        val relaunched = viewModel()
        assertTrue(relaunched.state.imports.isEmpty())
        assertEquals("cancelled", server.imports.getValue("imp_1").status)
    }

    @Test
    fun removingAFailedImportDismissesItOnTheServerToo() = test {
        val model = viewModel()
        pick(model)
        server.set("imp_1", "failed")
        advanceTimeBy(3_100)
        runCurrent()
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        runCurrent()
        assertEquals(1, server.count("cancel"))
        assertTrue(model.state.imports.isEmpty())
    }

    @Test
    fun removingARejectedImportIsLocal() = test {
        server.createFailures += WadrovaApiException(ApiFailure.REJECTED, "no", "payload_too_large")
        val model = viewModel()
        pick(model)
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        runCurrent()
        assertEquals(0, server.count("cancel"))
        assertTrue(model.state.imports.isEmpty())
    }

    // Navigation

    @Test
    fun leavingAnImportPageNeverStopsTheImport() = test {
        val model = viewModel()
        pick(model)
        val id = only(model).id
        model.openConnection()
        runCurrent()
        assertEquals(ImportRoute.Connection(ImportRoute.Progress(id)), model.state.route)
        model.close()
        assertEquals(ImportRoute.Progress(id), model.state.route)
        model.openReview(id)
        model.close()
        assertEquals(ImportRoute.Progress(id), model.state.route)
        model.close()
        assertNull(model.state.route)

        server.set("imp_1", "ready", count = 2)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ImportState.READY, only(model).state)
        model.open(id)
        assertNotNull(model.state.import(id))
    }

    @Test
    fun cancellingAfterOnlyRefusedUploadsIsLocalBecauseNothingCanExistOnTheServer() = test {
        server.createFailures += WadrovaApiException(ApiFailure.UNAUTHENTICATED, "no")
        val model = viewModel()
        pick(model)
        assertEquals(ImportProblem.ACCESS_REJECTED, only(model).problem)
        assertEquals(0, only(model).uploadAttempts)
        model.requestRemoval(only(model).id)
        model.confirmRemoval()
        runCurrent()
        assertTrue(model.state.imports.isEmpty())
        assertEquals(listOf("create"), server.calls)
    }

    @Test
    fun aRefusalAfterAnUncertainAttemptStillCancelsOnTheServer() = test {
        // The first attempt gets no response, so it may have created the import; the retry is then refused.
        server.createFailures += WadrovaApiException(ApiFailure.UNREACHABLE, "x")
        val model = viewModel()
        pick(model)
        server.createFailures += WadrovaApiException(ApiFailure.UNAUTHENTICATED, "revoked")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ImportProblem.ACCESS_REJECTED, only(model).problem)
        assertEquals(1, only(model).uploadAttempts)
        assertTrue(only(model).mayExistOnServer)
    }
}
