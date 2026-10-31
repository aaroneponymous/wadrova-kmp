package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GarmentRepositoryTest {
    private val databaseName = "garment_test"
    private val samples = listOf(Garment("sample-shirt", GarmentCategory.TOP))
    private val jacket = GarmentDetails("  Field Jacket ", GarmentCategory.OUTERWEAR, " Green ", "  ", " Cotton ")
    private lateinit var driver: SqlDriver
    private lateinit var garments: GarmentRepository
    private lateinit var looks: SavedLookRepository

    private fun open() {
        driver = openTestDatabase(databaseName)
        garments = SqlDelightGarmentRepository(driver, FakePhotoStore())
        looks = SqlDelightSavedLookRepository(driver, samples)
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
    fun createCleansDetailsAndAssignsUniqueIds() = runTest {
        val first = garments.create(jacket)
        val second = garments.create(jacket)
        assertNotEquals(first.garment.id, second.garment.id)
        assertEquals("Field Jacket", first.name)
        assertEquals("Green", first.color)
        assertEquals(null, first.brand)
        assertEquals("Cotton", first.material)
        assertFalse(first.archived)
    }

    @Test
    fun garmentsSurviveClosingAndReopeningTheDatabaseFile() = runTest {
        val saved = garments.create(jacket)
        driver.close()
        open()
        assertEquals(listOf(saved), garments.getAll())
    }

    @Test
    fun invalidDetailsAreRejectedAndNothingIsWritten() = runTest {
        assertFailsWith<InvalidGarmentException> { garments.create(jacket.copy(name = "  ")) }
        assertFailsWith<InvalidGarmentException> { garments.create(jacket.copy(color = "")) }
        assertEquals(emptyList(), garments.getAll())
    }

    @Test
    fun updateKeepsIdentity() = runTest {
        val saved = garments.create(jacket)
        val updated = garments.update(saved.garment.id, jacket.copy(name = "Chore Coat", brand = "Acme"))
        assertEquals(saved.garment, updated.garment)
        assertEquals("Chore Coat", updated.name)
        assertEquals("Acme", updated.brand)
        assertEquals(listOf(updated), garments.getAll())
    }

    @Test
    fun updatingAMissingGarmentFails() = runTest {
        assertFailsWith<InvalidGarmentException> { garments.update("missing", jacket) }
    }

    @Test
    fun categoryCanChangeOnlyWhileNoSavedLookUsesTheGarment() = runTest {
        val saved = garments.create(jacket)
        val id = saved.garment.id
        val retagged = garments.update(id, jacket.copy(category = GarmentCategory.TOP))
        assertEquals(GarmentCategory.TOP, retagged.garment.category)

        looks.create("Monday", listOf(id))
        assertFailsWith<InvalidGarmentException> { garments.update(id, jacket.copy(category = GarmentCategory.DRESS)) }
        assertEquals(retagged, garments.getAll().single())
        assertEquals("New name", garments.update(id, jacket.copy(name = "New name", category = GarmentCategory.TOP)).name)
    }

    @Test
    fun looksCanReferenceUserGarmentsAndSamples() = runTest {
        val id = garments.create(jacket).garment.id
        val look = looks.create("Monday", listOf("sample-shirt", id))
        assertEquals(listOf(look), looks.getAll())
    }

    @Test
    fun archivedGarmentRemainsValidInItsLookAndCanBeRestored() = runTest {
        val id = garments.create(jacket).garment.id
        val look = looks.create("Monday", listOf(id))

        assertTrue(garments.setArchived(id, true).archived)
        assertEquals(listOf(look), looks.getAll())
        assertTrue(garments.getAll().single().archived)
        assertEquals(id, looks.update(look.id, "Tuesday", listOf(id)).garmentIds.single())

        assertFalse(garments.setArchived(id, false).archived)
    }

    @Test
    fun archivingAMissingGarmentFails() = runTest {
        assertFailsWith<InvalidGarmentException> { garments.setArchived("missing", true) }
    }

    @Test
    fun unknownCategoryRowsAreSkippedSafely() = runTest {
        WadrovaDatabase(driver).garmentQueries.insertGarment("future", "Cape", "CAPE", "Red", null, null, 1, 1, null)
        assertEquals(emptyList(), garments.getAll())
        assertFailsWith<InvalidLookException> { looks.create("Monday", listOf("future")) }
    }
}
