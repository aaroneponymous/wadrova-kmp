package com.example.wadrova.ui

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.data.ApiFailure
import com.example.wadrova.data.Credential
import com.example.wadrova.data.FakeCredentialStore
import com.example.wadrova.data.FakeGeneratedImageStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.CutoutProblem
import com.example.wadrova.data.GarmentImportRepository
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.GeneratedImageStore
import com.example.wadrova.data.GeneratedImageGuard
import com.example.wadrova.data.ReviewedPiece
import com.example.wadrova.data.SqlDelightGarmentImportRepository
import com.example.wadrova.data.SqlDelightGarmentRepository
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.assertSanitizedCopyOf
import com.example.wadrova.data.deleteTestDatabase
import com.example.wadrova.data.openTestDatabase
import com.example.wadrova.data.serverStyleCutout
import com.example.wadrova.data.serverStylePixels
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Review, save and finish, wired as in the app: the review model saves, the import model finishes with the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarmentReviewViewModelTest {
    private val databaseName = "garment_review_vm_test"
    private val dispatcher = StandardTestDispatcher()
    private val server = FakeWadrovaServer()
    private val photos = FakePhotoStore(mutableSetOf("source-photo"))
    private val credentials = FakeCredentialStore(mutableMapOf(Credential.WADROVA_ACCESS_TOKEN to "wdr_token"))
    private val generated = FakeGeneratedImageStore()
    private val guard = GeneratedImageGuard()
    private lateinit var driver: SqlDriver
    private lateinit var imports: SqlDelightGarmentImportRepository
    private lateinit var garments: SqlDelightGarmentRepository
    private val importModels = mutableListOf<GarmentImportViewModel>()
    private var keys = 0

    private val jacket = server.candidate("cnd_j1", rank = 0, phrase = "jacket", suggested = GarmentCategory.OUTERWEAR)
    private val pants = server.candidate("cnd_p1", rank = 1, phrase = "pants")
    private val faint = server.candidate("cnd_b1", rank = 2, detected = false, phrase = "coat")

    private fun open() {
        driver = openTestDatabase(databaseName)
        var ids = 0
        imports = SqlDelightGarmentImportRepository(driver, nowMillis = { dispatcher.scheduler.currentTime }, dispatcher = dispatcher, newGarmentId = { "user-g${++ids}" })
        garments = SqlDelightGarmentRepository(driver, photos, generated, dispatcher = dispatcher)
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deleteTestDatabase(databaseName)
        open()
        server.imports["imp_1"] = ServerImport("imp_1", "kmp-imp-1", "ready", "ses_1", 2, null, null)
        server.addCandidates("ses_1", jacket, pants, faint)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        driver.close()
        deleteTestDatabase(databaseName)
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        imports.saveApiUrl("https://api.test")
        imports.createOrReuse("kmp-imp-1", "source-photo", "ab".repeat(32), 10)
        imports.update("kmp-imp-1") { current, now -> current.uploadStarted(now).withServer(server.imports.getValue("imp_1"), now) }
        try {
            body()
        } finally {
            importModels.forEach { it.onBackground() }
        }
    }

    /** One app instance: the import model plus the review model that hands saved imports to it. */
    private fun TestScope.app(
        reviewImports: GarmentImportRepository = imports,
        reviewPhotos: GarmentPhotoStore = photos,
        reviewGenerated: GeneratedImageStore = generated,
    ): Pair<GarmentReviewViewModel, GarmentImportViewModel> {
        val importModel = GarmentImportViewModel(imports, server, credentials, photos, random = Random(3)).also { importModels += it }
        val review = GarmentReviewViewModel(
            reviewImports, server, credentials, reviewPhotos, reviewGenerated, guard,
            onSaved = importModel::piecesSaved, newKey = { "kmp-cor-${++keys}" }, imageDispatcher = dispatcher,
        )
        runCurrent()
        review.open("kmp-imp-1")
        runCurrent()
        return review to importModel
    }

    private fun GarmentReviewViewModel.describe(lineageId: String, color: String = "Grey", category: GarmentCategory? = null) {
        color(lineageId, color)
        category?.let { categorize(lineageId, it) }
    }

    // Loading

    @Test
    fun theReviewShowsRealCandidatesWithVerifiedImagesAndHonestDefaults() = test {
        val (review, _) = app()
        val state = review.state
        assertEquals(listOf("cnd_j1", "cnd_p1"), state.pieces.map { it.id })
        assertEquals("png-cnd_j1".encodeToByteArray().toList(), state.images.getValue(jacket.cutout.id).toList())
        assertEquals("Jacket", state.name(state.pieces[0]))
        // A resolved suggestion pre-fills the category; an unresolved one leaves it to the user. Color is never guessed.
        assertEquals(GarmentCategory.OUTERWEAR, state.category(state.pieces[0]))
        assertNull(state.category(state.pieces[1]))
        assertNull(state.color(state.pieces[0]))
        // The below-threshold mask overlapping the jacket is offered as an alternative outline, not as a piece.
        assertEquals(listOf("cnd_b1"), state.alternatives(state.pieces[0]).map { it.id })
    }

    @Test
    fun aCorruptImageIsShownAsUnavailableNotFaked() = test {
        server.corruptAssets += pants.cutout.id
        val (review, _) = app()
        assertTrue(pants.cutout.id in review.state.failedImages)
        assertTrue(pants.cutout.id !in review.state.images)
    }

    @Test
    fun selectionsAndDetailsSurviveARelaunchAndAPinnedRevisionIsntSwitched() = test {
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.rename("cnd_j1", "Wool jacket")
        review.describe("cnd_j1")
        runCurrent()
        // A newer revision appears without this app asking for it.
        server.sessions.getValue("ses_1") += server.candidate("cnd_j1x", rank = 0, parent = "cnd_j1", revision = 2)

        val (reopened, _) = app()
        val lineage = reopened.state.pieces.first()
        assertEquals(listOf("cnd_j1", "cnd_j1x"), lineage.revisions.map { it.id })
        assertEquals("cnd_j1", reopened.state.shown(lineage).id)
        assertTrue(reopened.state.choice(lineage).selected)
        assertEquals("Wool jacket", reopened.state.name(lineage))
        assertEquals("Grey", reopened.state.color(lineage))
    }

    // Saving

    @Test
    fun incompleteDetailsStopTheSaveAndOpenThePiece() = test {
        val (review, _) = app()
        review.toggle("cnd_p1")
        review.save()
        runCurrent()
        assertEquals("cnd_p1", review.state.detailLineageId)
        assertNotNull(review.state.saveError)
        assertTrue(garments.getAll().isEmpty())
        assertEquals(ImportState.READY, imports.get("kmp-imp-1")!!.state)
        assertTrue("finalize" !in server.calls)
    }

    @Test
    fun savingMakesOrdinaryGarmentsFromTheVerifiedCutoutsAndFinishesWithTheServer() = test {
        val (review, importModel) = app()
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", color = "Black", category = GarmentCategory.BOTTOM)
        review.save()
        runCurrent()

        assertEquals(2, review.state.savedCount)
        val saved = garments.getAll().associateBy { it.garment.id }
        val jacketChoice = review.state.choice(review.state.lineages.first { it.id == "cnd_j1" })
        val jacketGarment = saved.getValue(jacketChoice.garmentId)
        assertEquals("Jacket", jacketGarment.name)
        assertEquals(GarmentCategory.OUTERWEAR, jacketGarment.garment.category)
        assertEquals("png-cnd_j1".encodeToByteArray().toList(), photos.importedBytes.getValue(jacketGarment.photoId!!).toList())
        assertEquals(GarmentCategory.BOTTOM, saved.values.first { it.name == "Pants" }.garment.category)

        val recorded = server.finalizedManifest("imp_1")!!
        assertEquals(setOf("cnd_j1", "cnd_p1"), recorded.accepted.map { it.first }.toSet())
        assertEquals(saved.keys, recorded.accepted.map { it.second }.toSet())
        assertTrue(recorded.rejected.isEmpty())
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        assertTrue("source-photo" in photos.deleted)
        assertTrue(importModel.state.pending.isEmpty())
    }

    @Test
    fun aRepeatedSaveTapSavesOnce() = test {
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        review.save()
        runCurrent()
        assertEquals(1, photos.importedBytes.size)
        assertEquals(1, garments.getAll().size)
    }

    @Test
    fun aFailedDownloadSavesNothingCleansUpAndCanBeRetriedWithoutDuplicates() = test {
        // The pants image fails its checksum when the review opens, so it isn't cached, and fails again while saving.
        server.corruptAssets += pants.cutout.id
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", category = GarmentCategory.BOTTOM)
        review.save()
        runCurrent()
        assertNotNull(review.state.saveError)
        assertTrue(garments.getAll().isEmpty())
        assertEquals(photos.importedBytes.keys, photos.deleted.filter { it != "source-photo" }.toSet())
        assertEquals(ImportState.READY, imports.get("kmp-imp-1")!!.state)

        server.corruptAssets.clear()
        review.save()
        runCurrent()
        assertEquals(2, garments.getAll().size)
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    // Transparent cutouts

    @Test
    fun aSavedPieceKeepsItsCutoutTransparentWithNoHiddenColorBesideItsPhoto() = test {
        val cutout = serverStyleCutout()
        server.assets[jacket.cutout.id] = cutout
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        runCurrent()

        val garment = garments.getAll().single()
        val cutoutId = assertNotNull(garment.cutoutImageId)
        assertEquals(setOf(cutoutId), generated.stored.keys)
        assertSanitizedCopyOf(serverStylePixels(), generated.stored.getValue(cutoutId))
        // The flattened photo is still made from the verified server bytes, exactly as before.
        assertEquals(cutout.toList(), photos.importedBytes.getValue(garment.photoId!!).toList())
        assertEquals(FinalizeManifest(listOf("cnd_j1" to garment.garment.id), listOf("cnd_p1")), server.finalizedManifest("imp_1"))
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    @Test
    fun aCutoutThatCantBeKeptLeavesThePieceWithItsPhotoAndTheSameDecisions() = test {
        // The fake server's default bytes aren't a PNG at all; a wrongly sized one is refused too.
        server.assets[pants.cutout.id] = serverStyleCutout(width = 99)
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", category = GarmentCategory.BOTTOM)
        review.save()
        runCurrent()

        val saved = garments.getAll()
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.cutoutImageId == null && it.photoId != null })
        assertTrue(generated.stored.isEmpty())
        assertEquals(setOf("cnd_j1", "cnd_p1"), server.finalizedManifest("imp_1")!!.accepted.map { it.first }.toSet())
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    @Test
    fun aFailedSaveRemovesTheCutoutsItWroteAndARetryLinksExactlyOne() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        // The transaction fails once, after both cutouts were written (as a full disk would).
        val failing = FailingSaveOnce(imports)
        val (review, _) = app(failing)
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", category = GarmentCategory.BOTTOM)
        review.save()
        runCurrent()
        assertNotNull(review.state.saveError)
        assertTrue(failing.failed)
        assertTrue(garments.getAll().isEmpty())
        assertTrue(generated.stored.isEmpty())
        assertEquals(2, generated.deleted.size)

        review.save()
        runCurrent()
        val saved = garments.getAll()
        assertEquals(2, saved.size)
        assertEquals(saved.mapNotNull { it.cutoutImageId }.toSet(), generated.stored.keys)
        assertEquals(2, generated.stored.size)
    }

    @Test
    fun savingAnAlreadySavedImportAgainKeepsTheFirstCutoutAndDropsTheNewOne() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        runCurrent()
        val first = garments.getAll().single().cutoutImageId

        // A second review of the same import (say, a stale screen after a crash) saves again.
        val (again, _) = app()
        again.toggle("cnd_j1")
        again.describe("cnd_j1")
        again.save()
        runCurrent()
        assertEquals(first, garments.getAll().single().cutoutImageId)
        assertEquals(setOf(first), generated.stored.keys)
    }

    @Test
    fun theSaveReportsHowManyCutoutsWereKeptAndWhyOthersWerent() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout(width = 99)
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", category = GarmentCategory.BOTTOM)
        review.save()
        runCurrent()
        val report = assertNotNull(review.state.cutoutReport)
        assertEquals(2, report.attempted)
        assertEquals(1, report.saved)
        assertEquals(mapOf(CutoutProblem.INTEGRITY to 1), report.skipped)
        assertEquals("cutouts attempted=2 saved=1 skipped=1 INTEGRITY=1", report.toString())
    }

    // Cancellation and failures around the commit. Each case checks that committed garments keep their files,
    // uncommitted files go, nothing is duplicated and the finalize request is unchanged.

    private fun TestScope.selectBoth(review: GarmentReviewViewModel) {
        review.toggle("cnd_j1")
        review.toggle("cnd_p1")
        review.describe("cnd_j1")
        review.describe("cnd_p1", category = GarmentCategory.BOTTOM)
        runCurrent()
    }

    private suspend fun assertCommittedGarmentsKeepTheirFiles() {
        val saved = garments.getAll()
        assertEquals(2, saved.size)
        assertEquals(2, saved.map { it.garment.id }.toSet().size)
        saved.forEach { g ->
            assertTrue(g.photoId!! in photos.stored, "photo of ${g.name}")
            assertTrue(assertNotNull(g.cutoutImageId) in generated.stored, "cutout of ${g.name}")
        }
    }

    private suspend fun TestScope.relaunchAndFinish() {
        app()
        runCurrent()
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        val manifest = imports.storedManifest("kmp-imp-1")!!
        assertEquals(setOf("cnd_j1", "cnd_p1"), manifest.accepted.map { it.first }.toSet())
        assertTrue(server.finalizeBodies.isNotEmpty())
        assertTrue(server.finalizeBodies.all { it == manifest })
    }

    @Test
    fun aCancelBeforeTheCommitSavesNothingAndRemovesEveryFileItWrote() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        lateinit var review: GarmentReviewViewModel
        // The app closes while the second cutout is being stored.
        val closing = HookedGeneratedStore(generated) { if (generated.stored.size == 1) review.viewModelScope.cancel() }
        review = app(reviewGenerated = closing).first
        selectBoth(review)
        review.save()
        runCurrent()
        assertTrue(garments.getAll().isEmpty())
        assertTrue(generated.stored.isEmpty())
        assertTrue(photos.stored == setOf("source-photo"))
        assertEquals(ImportState.READY, imports.get("kmp-imp-1")!!.state)

        // A relaunched review (selections are restored) saves once, with no duplicates.
        val (again, _) = app()
        again.save()
        runCurrent()
        assertCommittedGarmentsKeepTheirFiles()
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    @Test
    fun aCancelDuringTheCommitLetsItFinishAndKeepsEveryLinkedFile() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        lateinit var review: GarmentReviewViewModel
        val repo = HookedSave(imports, before = { review.viewModelScope.cancel() })
        review = app(reviewImports = repo).first
        selectBoth(review)
        review.save()
        runCurrent()
        assertTrue(repo.committed)
        assertCommittedGarmentsKeepTheirFiles()
        // The saved import isn't left behind: the import driver finishes it even though the review was cancelled.
        assertTrue(imports.get("kmp-imp-1")!!.state in setOf(ImportState.FINALIZE_PENDING, ImportState.COMPLETED))
        assertEquals(emptyList(), photos.deleted.filter { it != "source-photo" })
        relaunchAndFinish()
        assertCommittedGarmentsKeepTheirFiles()
    }

    @Test
    fun aCancelRightAfterTheCommitKeepsEveryLinkedFile() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        lateinit var review: GarmentReviewViewModel
        val repo = HookedSave(imports, after = { review.viewModelScope.cancel() })
        review = app(reviewImports = repo).first
        selectBoth(review)
        review.save()
        runCurrent()
        assertCommittedGarmentsKeepTheirFiles()
        assertEquals(emptyList(), generated.deleted)
        relaunchAndFinish()
    }

    @Test
    fun anErrorAfterTheCommitBeforeTheBookkeepingStillKeepsLinkedFiles() = test {
        // Not relying on the protected section alone: the commit lands, then the call fails before the attempt
        // hands its files over. Cleanup must see that garments use them.
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        val repo = HookedSave(imports, after = { throw IllegalStateException("lost after commit") })
        val (review, _) = app(reviewImports = repo)
        selectBoth(review)
        review.save()
        runCurrent()
        assertNotNull(review.state.saveError)
        assertCommittedGarmentsKeepTheirFiles()
        assertEquals(emptyList(), generated.deleted)
        assertEquals(emptyList(), photos.deleted.filter { it != "source-photo" })
        relaunchAndFinish()
    }

    @Test
    fun aCancelDuringCleanupOfAFailedSaveStillRemovesTheUncommittedFiles() = test {
        server.assets[jacket.cutout.id] = serverStyleCutout()
        server.assets[pants.cutout.id] = serverStyleCutout()
        lateinit var review: GarmentReviewViewModel
        val failing = FailingSaveOnce(imports)
        // The app closes as cleanup deletes its first file.
        val closingPhotos = HookedPhotoStore(photos) { review.viewModelScope.cancel() }
        review = app(reviewImports = failing, reviewPhotos = closingPhotos).first
        selectBoth(review)
        review.save()
        runCurrent()
        assertTrue(failing.failed)
        assertTrue(garments.getAll().isEmpty())
        assertEquals(setOf("source-photo"), photos.stored)
        assertTrue(generated.stored.isEmpty())

        val (again, _) = app()
        again.save()
        runCurrent()
        assertCommittedGarmentsKeepTheirFiles()
    }

    @Test
    fun anImageThatDoesntDecodeIsNotSaved() = test {
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        photos.undecodable += "png-cnd_j1".encodeToByteArray()
        review.save()
        runCurrent()
        assertNotNull(review.state.saveError)
        assertTrue(garments.getAll().isEmpty())
    }

    @Test
    fun finishingWithNothingSelectedRejectsEveryPiece() = test {
        val (review, _) = app()
        review.requestFinishEmpty()
        assertTrue(review.state.confirmingFinishEmpty)
        review.save()
        runCurrent()
        assertEquals(0, review.state.savedCount)
        assertEquals(FinalizeManifest(emptyList(), listOf("cnd_j1", "cnd_p1")), server.finalizedManifest("imp_1"))
        assertTrue(garments.getAll().isEmpty())
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    // Finishing with the server

    @Test
    fun aLostFinalizeResponseIsResentWithTheSameDecisionsAndNothingIsDuplicated() = test {
        server.loseNextFinalizeResponse = true
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        runCurrent()
        assertEquals(ImportState.FINALIZE_PENDING, imports.get("kmp-imp-1")!!.state)
        assertEquals(ImportProblem.OFFLINE, imports.get("kmp-imp-1")!!.problem)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, server.finalizeBodies.size)
        assertEquals(server.finalizeBodies[0], server.finalizeBodies[1])
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        assertEquals(1, garments.getAll().size)
        assertEquals(server.finalizeBodies[0].copy(accepted = server.finalizeBodies[0].accepted.sortedBy { it.first }), server.finalizedManifest("imp_1"))
    }

    @Test
    fun anUnfinishedImportIsFinishedAfterARelaunch() = test {
        repeat(GarmentImportViewModel.MAX_CONSECUTIVE_FAILURES) { server.finalizeFailures += WadrovaApiException(ApiFailure.UNREACHABLE, "x") }
        val (review, first) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        runCurrent()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(ImportState.FINALIZE_PENDING, imports.get("kmp-imp-1")!!.state)
        assertEquals("Pieces saved", imports.get("kmp-imp-1")!!.status().title)

        first.onBackground()
        importModels.clear()
        driver.close()
        open()
        val relaunched = GarmentImportViewModel(imports, server, credentials, photos, random = Random(3)).also { importModels += it }
        runCurrent()
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        assertTrue(relaunched.state.pending.isEmpty())
        assertEquals(1, garments.getAll().size)
        assertEquals(1, server.finalizeBodies.toSet().size)
    }

    @Test
    fun aLineageThatAppearsBeforeTheFirstSendIsReconciledAsRejected() = test {
        // Saved decisions that miss a lineage, as if a correction finished between saving and the first send.
        val choice = imports.reviewChoices("kmp-imp-1", listOf("cnd_j1")).getValue("cnd_j1")
        photos.stored += "cut"
        imports.saveReviewedPieces(
            "kmp-imp-1",
            listOf(com.example.wadrova.data.ReviewedPiece(choice.garmentId, com.example.wadrova.data.GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), "cut", "cnd_j1", "cnd_j1", 1)),
            FinalizeManifest(listOf("cnd_j1" to choice.garmentId), emptyList()),
        )
        assertNull(imports.get("kmp-imp-1")!!.finalizeSentAt)
        GarmentImportViewModel(imports, server, credentials, photos, random = Random(3)).also { importModels += it }
        runCurrent()
        assertEquals(1, server.finalizeBodies.size)
        assertEquals(FinalizeManifest(listOf("cnd_j1" to choice.garmentId), listOf("cnd_p1")), server.finalizedManifest("imp_1"))
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
    }

    @Test
    fun onceSentTheDecisionsNeverChangeByThemselves() = test {
        val (review, importModel) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        server.finalizeFailures += WadrovaApiException(ApiFailure.UNREACHABLE, "x")
        review.save()
        runCurrent()
        val frozen = imports.get("kmp-imp-1")!!
        assertNotNull(frozen.finalizeSentAt)
        val sent = imports.storedManifest("kmp-imp-1")!!
        // A correction elsewhere turned the faint mask into a detection after the decisions were sent.
        server.sessions.getValue("ses_1") += server.candidate("cnd_b1r", rank = 2, parent = "cnd_b1", revision = 2)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(server.finalizeBodies[0], server.finalizeBodies[1])
        assertEquals(sent, imports.storedManifest("kmp-imp-1"))
        val stuck = imports.get("kmp-imp-1")!!
        assertEquals(ImportState.FINALIZE_PENDING, stuck.state)
        assertEquals(ImportProblem.FINALIZE_INCOMPLETE, stuck.problem)
        assertEquals("Saved, not synced", stuck.status().title)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(2, server.finalizeBodies.size)

        // Only the user's explicit choice extends the decisions; the refused request stored nothing.
        importModel.addNewPiecesAsNotKept("kmp-imp-1")
        runCurrent()
        assertEquals(listOf("cnd_b1r", "cnd_p1"), server.finalizedManifest("imp_1")!!.rejected)
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        assertEquals(1, garments.getAll().size)
    }

    @Test
    fun aConflictingFinalizeStaysUnresolvedUntilWadrovaConfirms() = test {
        repeat(2) { server.finalizeFailures += WadrovaApiException(ApiFailure.REJECTED, "refused", "invalid_state", "finalized_differently") }
        val (review, importModel) = app()
        review.toggle("cnd_j1")
        review.describe("cnd_j1")
        review.save()
        runCurrent()
        val stuck = imports.get("kmp-imp-1")!!
        assertEquals(ImportProblem.FINALIZE_REFUSED, stuck.problem)
        assertEquals("This import was already finished with different choices.", stuck.problemMessage)
        assertEquals("Saved, not synced", stuck.status().title)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, server.finalizeBodies.size)
        assertEquals(listOf("kmp-imp-1"), importModel.state.pending.map { it.id })

        // Try again resends exactly the same decisions, and it's still unresolved while Wadrova refuses.
        importModel.retry("kmp-imp-1")
        runCurrent()
        assertEquals(server.finalizeBodies[0], server.finalizeBodies[1])
        assertEquals(ImportState.FINALIZE_PENDING, imports.get("kmp-imp-1")!!.state)
        assertEquals(1, garments.getAll().size)

        importModel.retry("kmp-imp-1")
        runCurrent()
        assertEquals(ImportState.COMPLETED, imports.get("kmp-imp-1")!!.state)
        assertEquals(3, server.finalizeBodies.size)
        assertEquals(1, server.finalizeBodies.toSet().size)
    }

    // Corrections

    @Test
    fun choosingAnAlternativeOutlineMakesAndShowsANewRevision() = test {
        val (review, _) = app()
        review.toggle("cnd_j1")
        review.selectAlternative("cnd_j1", faint)
        runCurrent()
        val lineage = review.state.pieces.first()
        assertEquals("cnd_j1r", review.state.shown(lineage).id)
        assertNull(review.state.choice(lineage).correction)
        assertEquals("cnd_j1", server.correctionRequests.single().first)
    }

    @Test
    fun aPointsCorrectionRunsInTheBackgroundAndItsResultBecomesThePiece() = test {
        val (review, _) = app()
        review.openDetail("cnd_j1")
        review.addPoint(40, 60)
        review.setPointMode(false)
        review.addPoint(5, 5)
        review.submitPoints("cnd_j1")
        runCurrent()
        val lineage = { review.state.pieces.first() }
        assertNotNull(review.state.choice(lineage()).correction)
        assertTrue(review.state.correcting)

        // The pending correction is stored, so a relaunched review keeps following it.
        val (reopened, _) = app()
        assertNotNull(reopened.state.choice(reopened.state.pieces.first()).correction)

        server.completeCorrection("cor_1")
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals("cnd_j1r", reopened.state.shown(reopened.state.pieces.first()).id)
        assertNull(reopened.state.choice(reopened.state.pieces.first()).correction)
    }

    @Test
    fun aFailedCorrectionKeepsThePreviousOutlineAndSaysSo() = test {
        val (review, _) = app()
        review.addPoint(40, 60)
        review.submitPoints("cnd_j1")
        runCurrent()
        server.completeCorrection("cor_1", succeed = false)
        advanceTimeBy(2_100)
        runCurrent()
        val lineage = review.state.pieces.first()
        assertEquals("cnd_j1", review.state.shown(lineage).id)
        assertEquals("Wadrova couldn't correct this piece. The previous outline is kept.", review.state.choice(lineage).correctionMessage)
    }

    @Test
    fun pointsNeedOneThatAddsToThePiece() = test {
        val (review, _) = app()
        review.setPointMode(false)
        review.addPoint(5, 5)
        review.submitPoints("cnd_j1")
        runCurrent()
        assertTrue(server.correctionRequests.isEmpty())
        assertNotNull(review.state.saveError)
    }

    private class FailingSaveOnce(private val inner: GarmentImportRepository) : GarmentImportRepository by inner {
        var failed = false

        override suspend fun saveReviewedPieces(importId: String, pieces: List<ReviewedPiece>, manifest: FinalizeManifest): List<String> {
            if (!failed) {
                failed = true
                throw IllegalStateException("disk full")
            }
            return inner.saveReviewedPieces(importId, pieces, manifest)
        }
    }

    /** Commits through [inner], running [before] and [after] around it (test seam for interleavings). */
    private class HookedSave(
        private val inner: GarmentImportRepository,
        private val before: () -> Unit = {},
        private val after: () -> Unit = {},
    ) : GarmentImportRepository by inner {
        var committed = false

        override suspend fun saveReviewedPieces(importId: String, pieces: List<ReviewedPiece>, manifest: FinalizeManifest): List<String> {
            before()
            return inner.saveReviewedPieces(importId, pieces, manifest).also {
                committed = true
                after()
            }
        }
    }

    private class HookedGeneratedStore(private val inner: GeneratedImageStore, private val onSave: () -> Unit) : GeneratedImageStore by inner {
        override suspend fun save(bytes: ByteArray): String {
            onSave()
            return inner.save(bytes)
        }
    }

    private class HookedPhotoStore(private val inner: GarmentPhotoStore, private val onDelete: () -> Unit) : GarmentPhotoStore by inner {
        override suspend fun delete(id: String) {
            onDelete()
            inner.delete(id)
        }
    }
}
