package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GarmentPhotoRepositoryTest {
    private val databaseName = "garment_photo_test"
    private val jacket = GarmentDetails("Field Jacket", GarmentCategory.OUTERWEAR, "Green")
    private val photos = FakePhotoStore(mutableSetOf("old", "new"))
    private lateinit var driver: SqlDriver
    private lateinit var garments: GarmentRepository

    @BeforeTest
    fun setUp() {
        deleteTestDatabase(databaseName)
        driver = openTestDatabase(databaseName)
        garments = SqlDelightGarmentRepository(driver, photos, FakeGeneratedImageStore())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        deleteTestDatabase(databaseName)
    }

    @Test
    fun attachedPhotoSurvivesReopeningTheDatabase() = runTest {
        val saved = garments.create(jacket, "old")
        driver.close()
        driver = openTestDatabase(databaseName)
        garments = SqlDelightGarmentRepository(driver, photos, FakeGeneratedImageStore())
        assertEquals("old", garments.getAll().single().photoId)
        assertEquals(saved, garments.getAll().single())
    }

    @Test
    fun replacingPointsAtTheNewPhotoAndDeletesTheOldFiles() = runTest {
        val id = garments.create(jacket, "old").garment.id
        val updated = garments.update(id, jacket, PhotoChange.Set("new"))
        assertEquals(id, updated.garment.id)
        assertEquals("new", updated.photoId)
        assertEquals(listOf("old"), photos.deleted)
    }

    @Test
    fun removingClearsTheReferenceAndKeepsTheGarment() = runTest {
        val id = garments.create(jacket, "old").garment.id
        val updated = garments.update(id, jacket, PhotoChange.Remove)
        assertEquals(null, updated.photoId)
        assertEquals(id, garments.getAll().single().garment.id)
        assertEquals(listOf("old"), photos.deleted)
    }

    @Test
    fun keepingLeavesThePhotoAlone() = runTest {
        val id = garments.create(jacket, "old").garment.id
        assertEquals("old", garments.update(id, jacket.copy(name = "Coat")).photoId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun rejectedUpdateKeepsTheOldPhotoAndItsFiles() = runTest {
        val id = garments.create(jacket, "old").garment.id
        assertFailsWith<InvalidGarmentException> { garments.update(id, jacket.copy(name = " "), PhotoChange.Set("new")) }
        assertEquals("old", garments.getAll().single().photoId)
        assertEquals(emptyList(), photos.deleted)
        assertTrue("old" in photos.stored)
    }

    @Test
    fun failedCategoryChangeKeepsTheOldPhoto() = runTest {
        val id = garments.create(jacket, "old").garment.id
        WadrovaDatabase(driver).savedLookQueries.insertLook("look", "Monday", 1, 1)
        WadrovaDatabase(driver).savedLookQueries.insertLookGarment("look", id, 0)
        assertFailsWith<InvalidGarmentException> {
            garments.update(id, jacket.copy(category = GarmentCategory.TOP), PhotoChange.Set("new"))
        }
        assertEquals("old", garments.getAll().single().photoId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun fileDeleteFailureDoesNotFailTheCommittedChange() = runTest {
        val id = garments.create(jacket, "old").garment.id
        photos.deleteFailure = IllegalStateException()
        assertEquals("new", garments.update(id, jacket, PhotoChange.Set("new")).photoId)
        assertEquals("new", garments.getAll().single().photoId)
    }

    @Test
    fun archivingKeepsThePhoto() = runTest {
        val id = garments.create(jacket, "old").garment.id
        assertEquals("old", garments.setArchived(id, true).photoId)
        assertEquals("old", garments.setArchived(id, false).photoId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun unreferencedFilesAreRemovedAndReferencedOnesKept() = runTest {
        val archived = garments.create(jacket, "old").garment.id
        garments.setArchived(archived, true)
        garments.removeUnreferencedPhotos()
        assertEquals(setOf("old"), photos.stored)
        assertEquals(listOf("new"), photos.deleted)
    }

    @Test
    fun recordsWithMissingFilesStillLoad() = runTest {
        garments.create(jacket, "restored-without-file")
        assertEquals("restored-without-file", garments.getAll().single().photoId)
        assertEquals(null, photos.read("restored-without-file", PhotoSize.DISPLAY))
    }

    @Test
    fun onlyGeneratedStylePhotoIdsAreAccepted() {
        assertTrue(isValidPhotoId("0b7a-11ee-be56"))
        assertFalse(isValidPhotoId("../secrets"))
        assertFalse(isValidPhotoId("a/b"))
        assertFalse(isValidPhotoId(""))
    }

    @Test
    fun sampleSizeSubsamplesWithoutGoingBelowTheTarget() {
        assertEquals(1, photoSampleSize(1000, 800, 1600))
        assertEquals(1, photoSampleSize(3199, 100, 1600))
        assertEquals(2, photoSampleSize(3200, 100, 1600))
        assertEquals(8, photoSampleSize(6000, 13000, 1600))
    }
}
