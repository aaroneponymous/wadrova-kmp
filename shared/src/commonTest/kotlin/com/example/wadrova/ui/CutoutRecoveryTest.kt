package com.example.wadrova.ui

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.data.ApiFailure
import com.example.wadrova.data.Credential
import com.example.wadrova.data.CutoutRecovery
import com.example.wadrova.data.CutoutRecoveryEligibility
import com.example.wadrova.data.CutoutRecoveryResult
import com.example.wadrova.data.FakeCredentialStore
import com.example.wadrova.data.FakeGeneratedImageStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentImportRepository
import com.example.wadrova.data.GeneratedImageGuard
import com.example.wadrova.data.GeneratedImageStore
import com.example.wadrova.data.IccFixtures
import com.example.wadrova.data.PhotoChange
import com.example.wadrova.data.PngColorSpace
import com.example.wadrova.data.ReviewedPiece
import com.example.wadrova.data.RgbaImage
import com.example.wadrova.data.SqlDelightGarmentImportRepository
import com.example.wadrova.data.SqlDelightGarmentRepository
import com.example.wadrova.data.SqlDelightMirrorRepository
import com.example.wadrova.data.SqlDelightSavedLookRepository
import com.example.wadrova.data.WadrovaApi
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.WadrovaConnection
import com.example.wadrova.data.assertSanitizedCopyOf
import com.example.wadrova.data.decodeRgbaPng
import com.example.wadrova.data.deleteTestDatabase
import com.example.wadrova.data.encodeRgbaPng
import com.example.wadrova.data.openTestDatabase
import com.example.wadrova.data.serverStylePixels
import com.example.wadrova.domain.CandidateAsset
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.ServerImport
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Recovering transparent cutouts for garments imported before cutouts were kept. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class CutoutRecoveryTest {
    private val databaseName = "cutout_recovery_test"
    private val dispatcher = StandardTestDispatcher()
    private val server = FakeWadrovaServer()
    private val photos = FakePhotoStore(mutableSetOf("source-photo", "photo-j", "photo-new"))
    private val generated = FakeGeneratedImageStore()
    private val guard = GeneratedImageGuard()
    private val credentials = FakeCredentialStore(mutableMapOf(Credential.WADROVA_ACCESS_TOKEN to "wdr_token"))
    private lateinit var driver: SqlDriver
    private lateinit var imports: SqlDelightGarmentImportRepository
    private lateinit var garments: SqlDelightGarmentRepository
    private var garmentId = ""

    private val jacket = server.candidate("cnd_j1", rank = 0, phrase = "jacket")
    private val pants = server.candidate("cnd_p1", rank = 1, phrase = "pants")

    /** The real profile structure, so recovery is checked with ICC-bearing cutouts like the live ones. */
    private val profile = (decodeRgbaPng(Base64.decode(IccFixtures.SRGB_ICC)).colorSpace as PngColorSpace.Icc).profile
    private val source = serverStylePixels()
    private val iccCutout = encodeRgbaPng(RgbaImage(source.width, source.height, source.pixels, PngColorSpace.Icc(profile)))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deleteTestDatabase(databaseName)
        driver = openTestDatabase(databaseName)
        var ids = 0
        imports = SqlDelightGarmentImportRepository(driver, dispatcher = dispatcher, newGarmentId = { "user-g${++ids}" })
        garments = SqlDelightGarmentRepository(driver, photos, generated, dispatcher = dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
        deleteTestDatabase(databaseName)
    }

    private fun recovery(api: WadrovaApi = server, store: GeneratedImageStore = generated, repo: GarmentImportRepository = imports) =
        CutoutRecovery(repo, api, store, guard, connection = { loadConnection(imports, credentials) }, imageDispatcher = dispatcher)

    /** A garment saved by M4.5 (photo only), its import finalized and completed, like the older QA garments. */
    private fun test(finalize: Boolean = true, complete: Boolean = true, body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        imports.saveApiUrl("https://api.test")
        server.imports["imp_1"] = ServerImport("imp_1", "kmp-imp-1", "ready", "ses_1", 2, null, null)
        server.addCandidates("ses_1", jacket, pants)
        imports.createOrReuse("kmp-imp-1", "source-photo", "ab".repeat(32), 10)
        imports.update("kmp-imp-1") { current, now -> current.uploadStarted(now).withServer(server.imports.getValue("imp_1"), now) }
        garmentId = imports.reviewChoices("kmp-imp-1", listOf("cnd_j1", "cnd_p1")).getValue("cnd_j1").garmentId
        val manifest = FinalizeManifest(listOf("cnd_j1" to garmentId), listOf("cnd_p1"))
        imports.saveReviewedPieces(
            "kmp-imp-1",
            listOf(ReviewedPiece(garmentId, GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), "photo-j", "cnd_j1", "cnd_j1", 1)),
            manifest,
        )
        if (finalize) server.finalizeImport(WadrovaConnection("https://api.test", "wdr_token"), "imp_1", manifest)
        if (complete) imports.completeImport("kmp-imp-1")
        server.assets[jacket.cutout.id] = iccCutout
        server.calls.clear()
        body()
    }

    private suspend fun garment() = garments.getAll().single { it.garment.id == garmentId }

    @Test
    fun anEligibleGarmentGetsItsCutoutWithTheProfileKeptAndNothingElseChanged() = test {
        val before = garment()
        assertNull(before.cutoutImageId)
        assertEquals(CutoutRecoveryEligibility.ELIGIBLE, recovery().eligibility(garmentId))
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))

        val after = garment()
        val cutoutId = assertNotNull(after.cutoutImageId)
        assertEquals(before.copy(cutoutImageId = cutoutId), after)
        assertEquals(1, garments.getAll().size)
        val stored = generated.stored.getValue(cutoutId)
        assertSanitizedCopyOf(source, stored)
        assertContentEquals(profile, assertIs<PngColorSpace.Icc>(decodeRgbaPng(stored).colorSpace).profile)
        // Provenance, decisions and the import are untouched.
        assertEquals(listOf(garmentId), imports.savedGarmentIds("kmp-imp-1"))
        assertEquals(FinalizeManifest(listOf("cnd_j1" to garmentId), listOf("cnd_p1")), imports.storedManifest("kmp-imp-1"))
        assertEquals(listOf("get", "candidates", "asset"), server.calls)
        assertEquals(CutoutRecoveryEligibility.ALREADY_HAS_CUTOUT, recovery().eligibility(garmentId))
    }

    @Test
    fun aSecondRecoveryIsANoOpThatDownloadsNothing() = test {
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))
        server.calls.clear()
        assertEquals(CutoutRecoveryResult.NOT_NEEDED, recovery().recover(garmentId))
        assertTrue(server.calls.isEmpty())
        assertEquals(1, generated.stored.size)
    }

    @Test
    fun manualReplacedPhotoAndUnsyncedGarmentsAreNotEligible() = test {
        val manual = garments.create(GarmentDetails("Tee", GarmentCategory.TOP, "White"), "photo-new")
        assertEquals(CutoutRecoveryEligibility.NOT_IMPORTED, recovery().eligibility(manual.garment.id))
        assertEquals(CutoutRecoveryResult.NOT_NEEDED, recovery().recover(manual.garment.id))

        garments.update(garmentId, GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), PhotoChange.Set("photo-new"))
        assertEquals(CutoutRecoveryEligibility.PHOTO_REPLACED, recovery().eligibility(garmentId))
        assertEquals(CutoutRecoveryResult.NOT_NEEDED, recovery().recover(garmentId))
        assertTrue(server.calls.isEmpty())
        assertTrue(generated.stored.isEmpty())
    }

    @Test
    fun anImportThatHasntSyncedIsNotEligible() = test(finalize = false, complete = false) {
        assertEquals(CutoutRecoveryEligibility.NOT_SYNCED, recovery().eligibility(garmentId))
        assertEquals(CutoutRecoveryResult.NOT_NEEDED, recovery().recover(garmentId))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aCutoutWhoseFileIsMissingIsReplacedButAPresentOneIsNot() = test {
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))
        val first = garment().cutoutImageId!!
        generated.stored.remove(first) // a restore that brought the database back without the file
        assertEquals(CutoutRecoveryEligibility.ELIGIBLE, recovery().eligibility(garmentId))
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))
        val second = garment().cutoutImageId!!
        assertTrue(second != first && second in generated.stored)
    }

    @Test
    fun theExactSavedRevisionIsRecoveredNotTheNewestOne() = test {
        // A newer revision exists on the server; the garment was saved from revision 1.
        val newer = server.candidate("cnd_j1x", rank = 0, parent = "cnd_j1", revision = 2)
        server.sessions.getValue("ses_1") += newer
        server.assets[newer.cutout.id] = encodeRgbaPng(RgbaImage(100, 200, ByteArray(100 * 200 * 4) { -1 }))
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))
        assertSanitizedCopyOf(source, generated.stored.getValue(garment().cutoutImageId!!))
    }

    @Test
    fun aServerRecordThatDoesntMapThisCandidateToThisGarmentIsRefused() = test {
        // Another account's or server's import with the same id: its decisions don't name this garment.
        server.imports["imp_1"] = server.imports.getValue("imp_1").copy(acceptedGarments = mapOf("cnd_j1" to "user-someone-else"))
        assertEquals(CutoutRecoveryResult.NOT_ON_SERVER, recovery().recover(garmentId))
        server.imports["imp_1"] = server.imports.getValue("imp_1").copy(sessionId = "ses_other", acceptedGarments = mapOf("cnd_j1" to garmentId))
        assertEquals(CutoutRecoveryResult.NOT_ON_SERVER, recovery().recover(garmentId))
        assertNull(garment().cutoutImageId)
        assertTrue(generated.stored.isEmpty())
    }

    @Test
    fun missingImportCandidateOrAssetAreNotOnTheServer() = test {
        server.assets.remove(jacket.cutout.id)
        assertEquals(CutoutRecoveryResult.NOT_ON_SERVER, recovery().recover(garmentId))
        server.sessions.getValue("ses_1").removeAll { it.id == "cnd_j1" }
        assertEquals(CutoutRecoveryResult.NOT_ON_SERVER, recovery().recover(garmentId))
        server.imports.remove("imp_1")
        assertEquals(CutoutRecoveryResult.NOT_ON_SERVER, recovery().recover(garmentId))
        assertNull(garment().cutoutImageId)
    }

    @Test
    fun offlineExpiredTokenAndNoConnectionKeepTheGarmentAsItIs() = test {
        server.alwaysUnreachable = true
        assertEquals(CutoutRecoveryResult.UNREACHABLE, recovery().recover(garmentId))
        server.alwaysUnreachable = false
        server.getFailure = WadrovaApiException(ApiFailure.UNAUTHENTICATED, "Connect again.")
        assertEquals(CutoutRecoveryResult.NOT_CONNECTED, recovery().recover(garmentId))
        server.getFailure = null
        credentials.values.remove(Credential.WADROVA_ACCESS_TOKEN)
        assertEquals(CutoutRecoveryResult.NOT_CONNECTED, recovery().recover(garmentId))
        assertNull(garment().cutoutImageId)
        assertEquals("photo-j", garment().photoId)
        assertTrue(generated.stored.isEmpty())
    }

    @Test
    fun aChecksumMismatchOrAnUnusablePngLinksNothing() = test {
        server.corruptAssets += jacket.cutout.id
        assertEquals(CutoutRecoveryResult.UNREACHABLE, recovery().recover(garmentId))
        server.corruptAssets.clear()
        server.assets[jacket.cutout.id] = "png-cnd_j1".encodeToByteArray()
        assertEquals(CutoutRecoveryResult.UNUSABLE, recovery().recover(garmentId))
        server.assets[jacket.cutout.id] = encodeRgbaPng(serverStylePixels(width = 99))
        assertEquals(CutoutRecoveryResult.UNUSABLE, recovery().recover(garmentId))
        assertNull(garment().cutoutImageId)
        assertTrue(generated.stored.isEmpty())
    }

    @Test
    fun aStorageFailureLinksNothing() = test {
        val failing = object : GeneratedImageStore by generated {
            override suspend fun save(bytes: ByteArray): String = throw IllegalStateException("disk full")
        }
        assertEquals(CutoutRecoveryResult.STORAGE_FAILED, recovery(store = failing).recover(garmentId))
        assertNull(garment().cutoutImageId)
    }

    @Test
    fun theLinkIsConditionalOnThePhotoAndCutoutStillMatching() = test {
        generated.put("staged")
        assertFalse(imports.linkRecoveredCutout(garmentId, "photo-other", null, "staged"))
        assertFalse(imports.linkRecoveredCutout(garmentId, "photo-j", "a-cutout-it-doesnt-have", "staged"))
        assertTrue(imports.linkRecoveredCutout(garmentId, "photo-j", null, "staged"))
        assertFalse(imports.linkRecoveredCutout(garmentId, "photo-j", null, "another"))
        assertEquals("staged", garment().cutoutImageId)
    }

    @Test
    fun replacingThePhotoWhileRecoveringLinksNothingAndDeletesTheDownload() = test {
        val api = object : WadrovaApi by server {
            override suspend fun assetContent(connection: WadrovaConnection, asset: CandidateAsset): ByteArray =
                server.assetContent(connection, asset).also {
                    garments.update(garmentId, GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), PhotoChange.Set("photo-new"))
                }
        }
        assertEquals(CutoutRecoveryResult.CHANGED, recovery(api = api).recover(garmentId))
        assertEquals("photo-new", garment().photoId)
        assertNull(garment().cutoutImageId)
        assertTrue(generated.stored.isEmpty())
    }

    @Test
    fun twoRecoveriesAtOnceLinkOneCutout() = test {
        val gate = CompletableDeferred<Unit>()
        val api = object : WadrovaApi by server {
            override suspend fun assetContent(connection: WadrovaConnection, asset: CandidateAsset): ByteArray {
                gate.await()
                return server.assetContent(connection, asset)
            }
        }
        val first = async { recovery(api = api).recover(garmentId) }
        val second = async { recovery(api = api).recover(garmentId) }
        runCurrent()
        gate.complete(Unit)
        val results = setOf(first.await(), second.await())
        assertEquals(setOf(CutoutRecoveryResult.RECOVERED, CutoutRecoveryResult.NOT_NEEDED), results)
        assertEquals(setOf(garment().cutoutImageId), generated.stored.keys)
        assertEquals(1, garments.getAll().size)
    }

    @Test
    fun aCancelBeforeTheLinkLeavesNothingAndACancelDuringItKeepsTheLink() = test {
        // Cancelled while the file is being stored: nothing linked, the file removed.
        val job = launch {
            val closing = object : GeneratedImageStore by generated {
                override suspend fun save(bytes: ByteArray): String = generated.save(bytes).also { coroutineContext.cancel() }
            }
            recovery(store = closing).recover(garmentId)
        }
        runCurrent()
        job.join()
        assertNull(garment().cutoutImageId)
        assertTrue(generated.stored.isEmpty())

        // Cancelled during the link: it completes and the file is kept.
        val linking = launch {
            val repo = object : GarmentImportRepository by imports {
                override suspend fun linkRecoveredCutout(garmentId: String, photoId: String, expectedCutoutId: String?, newCutoutId: String): Boolean {
                    coroutineContext.cancel()
                    return imports.linkRecoveredCutout(garmentId, photoId, expectedCutoutId, newCutoutId)
                }
            }
            recovery(repo = repo).recover(garmentId)
        }
        runCurrent()
        linking.join()
        val cutoutId = assertNotNull(garment().cutoutImageId)
        assertTrue(cutoutId in generated.stored)
    }

    @Test
    fun aRecoveredCutoutSurvivesTheLaunchSweepAndLooksKeepTheGarment() = test {
        val looks = SqlDelightSavedLookRepository(driver)
        val look = looks.create("Monday", listOf(garmentId))
        assertEquals(CutoutRecoveryResult.RECOVERED, recovery().recover(garmentId))
        generated.put("stray")
        SqlDelightMirrorRepository(driver, generated, FakeCredentialStore(), "http://default", guard = guard).removeUnreferencedImages()
        assertEquals(setOf(garment().cutoutImageId), generated.stored.keys)
        assertEquals(listOf(garmentId), looks.getAll().single { it.id == look.id }.garmentIds)
    }

    @Test
    fun theScreenOffersRecoveryOnlyAfterALocalCheckAndRemembersPermanentFailures() = test {
        var reloads = 0
        server.assets.remove(jacket.cutout.id)
        val model = CutoutRecoveryViewModel(recovery(), onRecovered = { reloads++ })
        model.check(garmentId)
        runCurrent()
        assertTrue(model.state.getValue(garmentId).available)
        assertTrue(server.calls.isEmpty())
        model.recover(garmentId)
        runCurrent()
        val failed = model.state.getValue(garmentId)
        assertFalse(failed.available)
        assertEquals("Wadrova no longer has this piece's cutout for this account.", failed.message)
        // Opening the piece again doesn't offer, or download, the same unavailable cutout.
        model.check(garmentId)
        runCurrent()
        model.recover(garmentId)
        runCurrent()
        assertEquals(1, server.count("asset"))
        assertEquals(0, reloads)
    }

    @Test
    fun theScreenReportsSuccessAndReloadsAndAllowsRetryAfterATransientFailure() = test {
        var reloads = 0
        val model = CutoutRecoveryViewModel(recovery(), onRecovered = { reloads++ })
        server.alwaysUnreachable = true
        model.check(garmentId)
        runCurrent()
        model.recover(garmentId)
        runCurrent()
        assertTrue(model.state.getValue(garmentId).canRetry)
        assertTrue(model.state.getValue(garmentId).available)
        server.alwaysUnreachable = false
        model.recover(garmentId)
        runCurrent()
        assertEquals(CutoutRecoveryViewModel.RECOVERED, model.state.getValue(garmentId).message)
        assertEquals(1, reloads)
        assertNotNull(garment().cutoutImageId)
    }
}
