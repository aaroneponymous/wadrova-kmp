package com.example.wadrova.ui

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.data.FakeGeneratedImageStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.InvalidLookException
import com.example.wadrova.data.SqlDelightGarmentRepository
import com.example.wadrova.data.SqlDelightSavedLookRepository
import com.example.wadrova.data.WadrovaDatabase
import com.example.wadrova.data.deleteTestDatabase
import com.example.wadrova.data.openTestDatabase
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The app ships no garments or looks. Earlier versions showed sample pieces from code, never stored,
 * so existing data needs no cleanup: looks that used samples keep those ids and show them as unavailable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoSampleWardrobeTest {
    private val databaseName = "no_sample_wardrobe_test"
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var driver: SqlDriver
    private lateinit var photos: FakePhotoStore

    /** The ids the sample pieces used, which saved looks from earlier versions can hold. */
    private val oldSampleIds = listOf("poplin-shirt", "wide-trousers", "court-sneakers", "leather-tote")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        deleteTestDatabase(databaseName)
        driver = openTestDatabase(databaseName)
        photos = FakePhotoStore()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        deleteTestDatabase(databaseName)
        Dispatchers.resetMain()
    }

    private fun garments() = SqlDelightGarmentRepository(driver, photos, FakeGeneratedImageStore(), dispatcher = dispatcher)

    private fun looks() = SqlDelightSavedLookRepository(driver, dispatcher = dispatcher)

    /** A fresh ViewModel over the same database, as on the next app start. */
    private fun launch() = WardrobeViewModel(looks(), garments(), photos)

    /** What an earlier version left behind: the user's garments and photos, and looks mixing in sample ids. */
    private fun seedEarlierInstall() {
        val db = WadrovaDatabase(driver)
        db.garmentQueries.insertGarment("user-jacket", "Field Jacket", "OUTERWEAR", "Green", "Acme", null, 1, 1, "photo-jacket")
        db.garmentQueries.insertGarment("user-jeans", "Black Jeans", "BOTTOM", "Black", null, null, 2, 2, null)
        db.garmentQueries.insertGarment("user-old-tee", "Old Tee", "TOP", "White", null, null, 3, 3, "photo-tee")
        db.garmentQueries.setGarmentArchivedAt(4, 4, "user-old-tee")
        photos.stored += setOf("photo-jacket", "photo-tee")
        insertLook(db, "look-mine", "All mine", listOf("user-jacket", "user-jeans"))
        insertLook(db, "look-mixed", "Mixed", listOf("poplin-shirt", "user-jeans", "court-sneakers"))
        insertLook(db, "look-samples", "Samples only", listOf("wide-trousers", "leather-tote"))
    }

    private fun insertLook(db: WadrovaDatabase, id: String, name: String, garmentIds: List<String>) {
        db.savedLookQueries.insertLook(id, name, 1, 1)
        garmentIds.forEachIndexed { index, garmentId -> db.savedLookQueries.insertLookGarment(id, garmentId, index.toLong()) }
    }

    @Test
    fun aNewInstallHasNoGarmentsLooksOrSelection() {
        val state = launch().state
        assertEquals(LooksStatus.READY, state.looksStatus)
        assertEquals(emptyList(), state.items)
        assertEquals(emptyList(), state.savedLooks)
        assertEquals(emptyList(), state.draftIds)
        assertTrue(GarmentCategory.entries.all { category -> state.itemsIn(category).isEmpty() })
    }

    @Test
    fun startingAgainNeverCreatesContent() {
        launch()
        driver.close()
        driver = openTestDatabase(databaseName)
        val state = launch().state
        assertEquals(emptyList(), state.items)
        assertEquals(emptyList(), state.savedLooks)
        assertEquals(0L, WadrovaDatabase(driver).garmentQueries.selectGarments().executeAsList().size.toLong())
    }

    @Test
    fun anEarlierInstallKeepsEveryUserRecordAndShowsNoSamples() {
        seedEarlierInstall()
        val state = launch().state

        assertEquals(listOf("user-old-tee", "user-jeans", "user-jacket"), state.items.map { it.garment.id })
        assertTrue(state.items.none { it.garment.id in oldSampleIds })
        assertEquals(listOf("user-jeans", "user-jacket"), state.availableItems.map { it.garment.id })
        assertEquals(listOf("user-old-tee"), state.archivedItems.map { it.garment.id })
        assertEquals("photo-jacket", state.items.first { it.garment.id == "user-jacket" }.photoId)
        assertEquals("photo-tee", state.items.first { it.garment.id == "user-old-tee" }.photoId)
        // Counts come only from the user's pieces.
        assertEquals(1, state.itemsIn(GarmentCategory.BOTTOM).size)
        assertEquals(0, state.itemsIn(GarmentCategory.TOP).size)
        // The startup photo sweep keeps every photo a garment uses, archived ones included.
        assertEquals(emptyList(), photos.deleted)
        assertEquals(setOf("photo-jacket", "photo-tee"), photos.stored)
    }

    @Test
    fun looksThatUsedSamplesStayAndShowThosePiecesAsUnavailable() {
        seedEarlierInstall()
        val state = launch().state
        val byId = state.savedLooks.associateBy { it.id }

        assertEquals(setOf("look-mine", "look-mixed", "look-samples"), byId.keys)
        assertEquals(listOf("poplin-shirt", "user-jeans", "court-sneakers"), byId.getValue("look-mixed").garmentIds)
        assertEquals(0, state.missingPieceCount(byId.getValue("look-mine")))
        assertEquals(2, state.missingPieceCount(byId.getValue("look-mixed")))
        assertEquals(2, state.missingPieceCount(byId.getValue("look-samples")))
        assertEquals(listOf("user-jeans"), state.itemsFor(byId.getValue("look-mixed")).map { it.garment.id })
        assertEquals(emptyList(), state.itemsFor(byId.getValue("look-samples")))
    }

    @Test
    fun aLookWithUnavailablePiecesSavesOnceTheyAreRemoved() {
        seedEarlierInstall()
        val viewModel = launch()
        val mixed = viewModel.state.savedLooks.first { it.id == "look-mixed" }
        viewModel.update { it.openLook(mixed) }
        assertEquals(listOf("poplin-shirt", "court-sneakers"), viewModel.state.unknownDraftIds)
        assertEquals("Remove unavailable pieces before saving.", viewModel.state.saveBlocker)

        viewModel.update { it.removeUnknownPieces() }
        viewModel.update { it.openSaveSheet().setSaveMode(SaveMode.UPDATE) }
        viewModel.save()

        val reopened = launch().state.savedLooks.first { it.id == "look-mixed" }
        assertEquals("Mixed", reopened.name)
        assertEquals(listOf("user-jeans"), reopened.garmentIds)
    }

    @Test
    fun loadingTwiceChangesNothing() {
        seedEarlierInstall()
        val first = launch().state
        val second = launch().state
        assertEquals(first.items, second.items)
        assertEquals(first.savedLooks, second.savedLooks)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun theFirstPieceAndLookCanBeCreatedFromAnEmptyWardrobe() {
        val viewModel = launch()
        viewModel.update { it.openAddGarment() }
        viewModel.update { it.updateGarmentForm { form -> form.copy(name = "Linen Shirt", category = GarmentCategory.TOP, color = "White") } }
        viewModel.submitGarmentForm()
        val shirt = viewModel.state.availableItems.single()
        assertEquals("Linen Shirt", shirt.name)

        viewModel.update { it.requestNewOutfit().toggle(shirt).openBuilder() }
        viewModel.update { it.openSaveSheet() }
        viewModel.update { it.setSaveName("First look") }
        viewModel.save()

        val saved = launch().state
        assertEquals(listOf("Linen Shirt"), saved.items.map { it.name })
        assertEquals(listOf(shirt.garment.id), saved.savedLooks.single().garmentIds)
        assertNull(saved.savedLooks.single().garmentIds.firstOrNull { it in oldSampleIds })
    }

    @Test
    fun aLookCanNoLongerBeSavedWithASampleId() = runTest(dispatcher) {
        assertFailsWith<InvalidLookException> { looks().create("Old", listOf("poplin-shirt")) }
        assertEquals(emptyList(), WadrovaDatabase(driver).savedLookQueries.selectLooks().executeAsList())
    }

    @Test
    fun aGarmentAddedElsewhereShowsOnReload() = runTest(dispatcher) {
        val viewModel = launch()
        garments().create(GarmentDetails("Wool Coat", GarmentCategory.OUTERWEAR, "Camel"))
        viewModel.loadRecords()
        assertEquals(listOf("Wool Coat"), viewModel.state.items.map { it.name })
    }
}
