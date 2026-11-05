package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.ServerImport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** How an imported garment's transparent cutout is linked, kept, replaced and swept. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarmentCutoutLifecycleTest {
    private val databaseName = "garment_cutout_test"
    private val photos = FakePhotoStore(mutableSetOf("source", "photo-1", "photo-2", "new-photo"))
    private val images = FakeGeneratedImageStore()
    private val guard = GeneratedImageGuard()
    private var ids = 0
    private lateinit var driver: SqlDriver
    private lateinit var imports: GarmentImportRepository
    private lateinit var garments: GarmentRepository
    private lateinit var mirror: MirrorRepository

    private fun open() {
        driver = openTestDatabase(databaseName)
        imports = SqlDelightGarmentImportRepository(driver, newGarmentId = { "user-g${++ids}" })
        garments = SqlDelightGarmentRepository(driver, photos, images)
        mirror = SqlDelightMirrorRepository(driver, images, FakeCredentialStore(), "http://default", guard = guard)
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

    private suspend fun savedImportedGarment(cutoutId: String? = "cutout-1", photoId: String = "photo-1"): String {
        imports.createOrReuse("kmp-imp-1", "source", "ab".repeat(32), 10)
        imports.update("kmp-imp-1") { current, now ->
            current.uploadStarted(now).withServer(ServerImport("imp_1", "kmp-imp-1", "ready", "ses_1", 1, null, null), now)
        }
        cutoutId?.let { images.put(it) }
        val garmentId = imports.reviewChoices("kmp-imp-1", listOf("cnd_a")).getValue("cnd_a").garmentId
        imports.saveReviewedPieces(
            "kmp-imp-1",
            listOf(ReviewedPiece(garmentId, GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), photoId, "cnd_a", "cnd_a", 1, cutoutId)),
            FinalizeManifest(listOf("cnd_a" to garmentId), emptyList()),
        )
        return garmentId
    }

    @Test
    fun aSavedPieceIsLinkedToItsCutoutAndAManualGarmentHasNone() = runTest {
        val id = savedImportedGarment()
        val garment = garments.getAll().single { it.garment.id == id }
        assertEquals("cutout-1", garment.cutoutImageId)
        assertEquals("photo-1", garment.photoId)
        assertNull(garment.extractedImageId)
        assertNull(garments.create(GarmentDetails("Tee", GarmentCategory.TOP, "White"), "photo-2").cutoutImageId)
    }

    @Test
    fun savingAgainKeepsTheFirstCutoutLinkAndHandsBackTheNewPhoto() = runTest {
        val id = savedImportedGarment()
        images.put("cutout-2")
        val unused = imports.saveReviewedPieces(
            "kmp-imp-1",
            listOf(ReviewedPiece(id, GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey"), "photo-2", "cnd_a", "cnd_a", 1, "cutout-2")),
            FinalizeManifest(listOf("cnd_a" to id), emptyList()),
        )
        assertEquals(listOf("photo-2"), unused)
        assertEquals(1, garments.getAll().size)
        assertEquals("cutout-1", garments.getAll().single().cutoutImageId)
    }

    @Test
    fun replacingOrRemovingThePhotoClearsAndDeletesTheCutout() = runTest {
        val id = savedImportedGarment()
        val details = GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Grey")
        val replaced = garments.update(id, details, PhotoChange.Set("new-photo"))
        assertEquals("new-photo", replaced.photoId)
        assertNull(replaced.cutoutImageId)
        assertTrue("cutout-1" in images.deleted)
        assertTrue("cutout-1" !in images.stored)

        images.deleted.clear()
        val second = savedImportedGarmentInSecondImport()
        val removed = garments.update(second, details, PhotoChange.Remove)
        assertNull(removed.photoId)
        assertNull(removed.cutoutImageId)
        assertEquals(listOf("cutout-9"), images.deleted)
    }

    private suspend fun savedImportedGarmentInSecondImport(): String {
        imports.createOrReuse("kmp-imp-2", "source", "cd".repeat(32), 10)
        imports.update("kmp-imp-2") { current, now ->
            current.uploadStarted(now).withServer(ServerImport("imp_2", "kmp-imp-2", "ready", "ses_2", 1, null, null), now)
        }
        images.put("cutout-9")
        val garmentId = imports.reviewChoices("kmp-imp-2", listOf("cnd_z")).getValue("cnd_z").garmentId
        imports.saveReviewedPieces(
            "kmp-imp-2",
            listOf(ReviewedPiece(garmentId, GarmentDetails("Coat", GarmentCategory.OUTERWEAR, "Grey"), "photo-2", "cnd_z", "cnd_z", 1, "cutout-9")),
            FinalizeManifest(listOf("cnd_z" to garmentId), emptyList()),
        )
        return garmentId
    }

    @Test
    fun editingDetailsOrTheMirrorExtractionKeepsTheCutout() = runTest {
        val id = savedImportedGarment()
        val renamed = garments.update(id, GarmentDetails("Wool jacket", GarmentCategory.OUTERWEAR, "Navy", "Acme"), PhotoChange.Keep)
        assertEquals("cutout-1", renamed.cutoutImageId)
        assertEquals("photo-1", renamed.photoId)
        assertEquals("cutout-1", garments.setExtractedImage(id, "extracted").cutoutImageId)
        assertEquals("cutout-1", garments.setExtractedImage(id, null).cutoutImageId)
        assertTrue("cutout-1" !in images.deleted)
        assertTrue(images.stored.containsKey("cutout-1"))
    }

    @Test
    fun cutoutsSurviveTheLaunchSweepAndStrayFilesAreRemoved() = runTest {
        savedImportedGarment()
        images.put("left-by-a-crash")
        // A relaunch: a fresh database connection, then the sweep the app runs at startup.
        driver.close()
        open()
        mirror.removeUnreferencedImages()
        assertEquals(setOf("cutout-1"), images.stored.keys)
        assertEquals(listOf("left-by-a-crash"), images.deleted)
    }

    @Test
    fun theSweepWaitsForASaveThatHasWrittenButNotYetLinkedItsCutout() = runTest {
        val written = CompletableDeferred<Unit>()
        val link = CompletableDeferred<Unit>()
        val save = launch {
            guard.exclusive {
                images.put("cutout-1")
                written.complete(Unit)
                link.await()
                savedImportedGarment(cutoutId = "cutout-1")
            }
        }
        written.await()
        val sweep = launch { mirror.removeUnreferencedImages() }
        runCurrent()
        // The file exists and isn't referenced yet, but the sweep hasn't run.
        assertTrue(images.stored.containsKey("cutout-1"))
        assertTrue(sweep.isActive)
        link.complete(Unit)
        save.join()
        sweep.join()
        assertTrue(images.stored.containsKey("cutout-1"))
        assertTrue(images.deleted.isEmpty())
    }
}
