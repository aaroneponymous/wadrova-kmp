package com.example.wadrova.data

import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SavedLookRepositoryTest {
    private val databaseName = "saved_look_test"
    private val garments = listOf(
        Garment("shirt", GarmentCategory.TOP),
        Garment("jeans", GarmentCategory.BOTTOM),
        Garment("bag", GarmentCategory.ACCESSORY),
    )
    private lateinit var repository: SavedLookRepository

    private fun open(): SavedLookRepository = SqlDelightSavedLookRepository(openTestDatabase(databaseName), garments)

    @BeforeTest
    fun setUp() {
        deleteTestDatabase(databaseName)
        repository = open()
    }

    @AfterTest
    fun tearDown() {
        repository.close()
        deleteTestDatabase(databaseName)
    }

    @Test
    fun createdLooksHaveUniqueIdsAndKeepPieceOrder() = runTest {
        val first = repository.create("Monday", listOf("jeans", "shirt"))
        val second = repository.create("Monday", listOf("jeans", "shirt"))
        assertNotEquals(first.id, second.id)
        assertTrue(first.id.isNotBlank())
        assertEquals(setOf(first, second), repository.getAll().toSet())
        assertEquals(listOf("jeans", "shirt"), repository.getAll().first().garmentIds)
    }

    @Test
    fun looksSurviveClosingAndReopeningTheDatabaseFile() = runTest {
        val saved = repository.create("Monday", listOf("shirt", "bag"))
        repository.close()
        repository = open()
        assertEquals(listOf(saved), repository.getAll())
    }

    @Test
    fun createTrimsTheName() = runTest {
        assertEquals("Monday", repository.create("  Monday ", listOf("shirt")).name)
    }

    @Test
    fun invalidLooksAreRejectedAndNothingIsWritten() = runTest {
        assertFailsWith<InvalidLookException> { repository.create("Unknown", listOf("shirt", "gone")) }
        assertFailsWith<InvalidLookException> { repository.create("Duplicate", listOf("shirt", "shirt")) }
        assertFailsWith<InvalidLookException> { repository.create("Empty", emptyList()) }
        assertFailsWith<InvalidLookException> { repository.create("   ", listOf("shirt")) }
        assertEquals(emptyList(), repository.getAll())
    }

    @Test
    fun updateKeepsIdentityAndReplacesNameAndPieces() = runTest {
        val saved = repository.create("Monday", listOf("shirt", "bag"))
        val updated = repository.update(saved.id, "Tuesday", listOf("jeans"))
        assertEquals(saved.id, updated.id)
        assertEquals(saved.createdAtMillis, updated.createdAtMillis)
        assertEquals(listOf(updated), repository.getAll())
        assertEquals("Tuesday", repository.getAll().single().name)
        assertEquals(listOf("jeans"), repository.getAll().single().garmentIds)
    }

    @Test
    fun rejectedUpdateLeavesTheLookUnchanged() = runTest {
        val saved = repository.create("Monday", listOf("shirt"))
        assertFailsWith<InvalidLookException> { repository.update(saved.id, "Tuesday", listOf("gone")) }
        assertEquals(listOf(saved), repository.getAll())
    }

    @Test
    fun updatingAMissingLookFails() = runTest {
        assertFailsWith<InvalidLookException> { repository.update("missing", "Tuesday", listOf("shirt")) }
        assertEquals(emptyList(), repository.getAll())
    }

    @Test
    fun failedGarmentInsertRollsBackTheWholeLook() = runTest {
        repository.close()
        val driver = openTestDatabase(databaseName)
        // An orphan row makes the look row insert succeed and its garment insert fail.
        WadrovaDatabase(driver).savedLookQueries.insertLookGarment("same-id", "shirt", 0)
        repository = SqlDelightSavedLookRepository(driver, garments, newId = { "same-id" })
        assertFails { repository.create("Monday", listOf("shirt")) }
        assertEquals(emptyList(), repository.getAll())
    }
}
