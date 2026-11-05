package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.ImportState
import com.example.wadrova.domain.PendingCorrection
import com.example.wadrova.domain.ServerImport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GarmentImportReviewRepositoryTest {
    private val databaseName = "garment_import_review_test"
    private var ids = 0
    private lateinit var driver: SqlDriver
    private lateinit var imports: GarmentImportRepository
    private lateinit var garments: GarmentRepository
    private val photos = FakePhotoStore(mutableSetOf("source", "cut-1", "cut-2", "stray"))

    private fun open() {
        driver = openTestDatabase(databaseName)
        imports = SqlDelightGarmentImportRepository(driver, newGarmentId = { "user-g${++ids}" })
        garments = SqlDelightGarmentRepository(driver, photos, FakeGeneratedImageStore())
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

    private suspend fun readyImport(): String {
        imports.createOrReuse("kmp-imp-1", "source", "ab".repeat(32), 10)
        imports.update("kmp-imp-1") { current, now ->
            current.uploadStarted(now).withServer(ServerImport("imp_1", "kmp-imp-1", "ready", "ses_1", 2, null, null), now)
        }
        return "kmp-imp-1"
    }

    private fun piece(garmentId: String, lineage: String, candidate: String, photo: String, name: String = "Jacket") =
        ReviewedPiece(garmentId, GarmentDetails(" $name ", GarmentCategory.OUTERWEAR, "Grey"), photo, lineage, candidate, 2)

    @Test
    fun eachLineageGetsOneStableGarmentIdAndChoicesSurviveReopening() = runTest {
        val id = readyImport()
        val first = imports.reviewChoices(id, listOf("cnd_a", "cnd_b"))
        val again = imports.reviewChoices(id, listOf("cnd_a", "cnd_b", "cnd_c"))
        assertEquals(first.getValue("cnd_a").garmentId, again.getValue("cnd_a").garmentId)
        assertEquals(3, again.values.map { it.garmentId }.toSet().size)

        val edited = again.getValue("cnd_a").copy(
            selected = true,
            chosenCandidateId = "cnd_a",
            name = "Wool coat",
            category = GarmentCategory.OUTERWEAR,
            color = "Grey",
            correction = PendingCorrection("kmp-cor-1", "cor_1", "points"),
        )
        imports.saveReviewChoice(id, edited)
        driver.close()
        open()
        assertEquals(edited, imports.reviewChoices(id, listOf("cnd_a")).getValue("cnd_a"))
    }

    @Test
    fun savingMakesOrdinaryGarmentsWithProvenanceAndTheManifestInOneStep() = runTest {
        val id = readyImport()
        assertNull(imports.storedManifest(id))
        val manifest = FinalizeManifest(listOf("cnd_a1" to "user-x", "cnd_b2" to "user-y"), listOf("cnd_c1"))
        val unused = imports.saveReviewedPieces(
            id,
            listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1", "Jacket"), piece("user-y", "cnd_b1", "cnd_b2", "cut-2", "Scarf")),
            manifest,
        )
        assertTrue(unused.isEmpty())

        val saved = garments.getAll().associateBy { it.garment.id }
        assertEquals("Jacket", saved.getValue("user-x").name)
        assertEquals("cut-1", saved.getValue("user-x").photoId)
        assertEquals(GarmentCategory.OUTERWEAR, saved.getValue("user-y").garment.category)
        assertEquals(ImportState.FINALIZE_PENDING, imports.get(id)!!.state)
        assertEquals(setOf("user-x", "user-y"), imports.savedGarmentIds(id).toSet())
        driver.close()
        open()
        assertEquals(manifest.copy(accepted = manifest.accepted.sortedBy { it.first }), imports.storedManifest(id))

        // Saved garments behave like any other: they can be edited and archived.
        assertEquals("Wool jacket", garments.update("user-x", GarmentDetails("Wool jacket", GarmentCategory.OUTERWEAR, "Grey")).name)
        assertTrue(garments.setArchived("user-y", true).archived)
    }

    @Test
    fun savingAgainNeverDuplicatesAndHandsBackTheNewPhotos() = runTest {
        val id = readyImport()
        val manifest = FinalizeManifest(listOf("cnd_a1" to "user-x"), emptyList())
        imports.saveReviewedPieces(id, listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1")), manifest)
        val unused = imports.saveReviewedPieces(id, listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-2")), manifest)
        assertEquals(listOf("cut-2"), unused)
        assertEquals(1, garments.getAll().size)
        assertEquals("cut-1", garments.getAll().single().photoId)
    }

    @Test
    fun oneCandidateCantBecomeTwoGarmentsAndAFailedSaveWritesNothing() = runTest {
        val id = readyImport()
        assertFailsWith<Exception> {
            imports.saveReviewedPieces(
                id,
                listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1"), piece("user-y", "cnd_b1", "cnd_a1", "cut-2")),
                FinalizeManifest(listOf("cnd_a1" to "user-x"), emptyList()),
            )
        }
        assertFailsWith<InvalidGarmentException> {
            imports.saveReviewedPieces(id, listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1", name = " ")), FinalizeManifest(emptyList(), emptyList()))
        }
        assertTrue(garments.getAll().isEmpty())
        assertEquals(ImportState.READY, imports.get(id)!!.state)
        assertNull(imports.storedManifest(id))
    }

    @Test
    fun anAllRejectedReviewStoresAnEmptyAcceptedListAndCanGainMissingLineages() = runTest {
        val id = readyImport()
        imports.saveReviewedPieces(id, emptyList(), FinalizeManifest(emptyList(), listOf("cnd_a1")))
        assertEquals(FinalizeManifest(emptyList(), listOf("cnd_a1")), imports.storedManifest(id))
        assertEquals(1, imports.addRejectedDecisions(id, listOf("cnd_z1", "cnd_a1")))
        assertEquals(0, imports.addRejectedDecisions(id, listOf("cnd_z1")))
        assertEquals(listOf("cnd_a1", "cnd_z1"), imports.storedManifest(id)!!.rejected)
    }

    @Test
    fun completingKeepsGarmentsAndProvenanceAndDropsTheReviewRows() = runTest {
        val id = readyImport()
        imports.reviewChoices(id, listOf("cnd_a1"))
        imports.saveReviewedPieces(id, listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1")), FinalizeManifest(listOf("cnd_a1" to "user-x"), emptyList()))
        imports.completeImport(id)
        assertEquals(ImportState.COMPLETED, imports.get(id)!!.state)
        assertEquals(listOf("user-x"), imports.savedGarmentIds(id))
        assertEquals(1, garments.getAll().size)
        // A new review of the same lineage would get a fresh row; the old one is gone.
        assertTrue(imports.reviewChoices(id, emptyList()).isEmpty())
    }

    @Test
    fun savedGarmentPhotosAreKeptByPhotoCleanup() = runTest {
        val id = readyImport()
        imports.saveReviewedPieces(id, listOf(piece("user-x", "cnd_a1", "cnd_a1", "cut-1")), FinalizeManifest(listOf("cnd_a1" to "user-x"), emptyList()))
        garments.removeUnreferencedPhotos()
        assertEquals(setOf("source", "cut-1"), photos.stored)
    }
}
