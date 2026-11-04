package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.GarmentImageKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MirrorRepositoryTest {
    private val databaseName = "mirror_test"
    private val images = FakeGeneratedImageStore()
    private val photos = FakePhotoStore(mutableSetOf("photo", "photo-2"))
    private val credentials = FakeCredentialStore()
    private val generation = GeneratedImage(ByteArray(1), "model-x", "high")
    private val jacket = GarmentDetails("Jacket", GarmentCategory.OUTERWEAR, "Green")
    private lateinit var driver: SqlDriver
    private lateinit var mirror: MirrorRepository
    private lateinit var garments: GarmentRepository

    private fun open() {
        driver = openTestDatabase(databaseName)
        mirror = SqlDelightMirrorRepository(driver, images, credentials, "http://default:8787")
        garments = SqlDelightGarmentRepository(driver, photos, images)
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
    fun approvalsKeepOneModelIdAndIncreaseTheVersion() = runTest {
        assertNull(mirror.approvedModel())
        val first = mirror.approveModel("image-1", generation)
        val second = mirror.approveModel("image-2", generation)
        assertEquals(1L, first.version)
        assertEquals(2L, second.version)
        assertEquals(first.modelId, second.modelId)
        assertEquals("image-2", mirror.approvedModel()?.imageId)
        assertEquals("model-x", mirror.approvedModel()?.generationModel)
    }

    @Test
    fun modelAndPreviewsSurviveReopeningTheDatabase() = runTest {
        val model = mirror.approveModel("image-1", generation)
        val preview = mirror.saveTryOn(model, "garment", "photo", GarmentImageKind.PHOTO, generation, "result")
        driver.close()
        open()
        assertEquals(model, mirror.approvedModel())
        assertEquals(listOf(preview), mirror.tryOns())
    }

    @Test
    fun tryOnRecordsItsWholeProvenance() = runTest {
        val model = mirror.approveModel("image-1", generation)
        val saved = mirror.saveTryOn(model, "garment", "extracted", GarmentImageKind.EXTRACTED, generation, "result")
        assertEquals(model.modelId, saved.modelId)
        assertEquals(1L, saved.modelVersion)
        assertEquals("garment", saved.garmentId)
        assertEquals("extracted", saved.sourceImageId)
        assertEquals(GarmentImageKind.EXTRACTED, saved.sourceImageKind)
        assertEquals("model-x", saved.generationModel)
        assertEquals("high", saved.generationQuality)
        assertEquals("result", saved.resultImageId)
    }

    @Test
    fun deletingAPreviewRemovesItsFileOnly() = runTest {
        images.put("image-1")
        images.put("result")
        val model = mirror.approveModel("image-1", generation)
        val saved = mirror.saveTryOn(model, "garment", "photo", GarmentImageKind.PHOTO, generation, "result")
        mirror.deleteTryOn(saved.id)
        assertEquals(emptyList(), mirror.tryOns())
        assertEquals(setOf("image-1"), images.stored.keys)
    }

    @Test
    fun removingTheModelDeletesAllVersionsAndPreviewsButNotGarments() = runTest {
        listOf("image-1", "image-2", "result", "extracted").forEach { images.put(it) }
        val garment = garments.create(jacket, "photo")
        garments.setExtractedImage(garment.garment.id, "extracted")
        mirror.approveModel("image-1", generation)
        val model = mirror.approveModel("image-2", generation)
        mirror.saveTryOn(model, garment.garment.id, "photo", GarmentImageKind.PHOTO, generation, "result")

        mirror.removeModelAndPreviews()

        assertNull(mirror.approvedModel())
        assertEquals(emptyList(), mirror.tryOns())
        assertEquals(setOf("extracted"), images.stored.keys)
        assertEquals("extracted", garments.getAll().single().extractedImageId)
    }

    @Test
    fun onlyUnreferencedGeneratedFilesAreRemoved() = runTest {
        listOf("model", "result", "extracted", "orphan").forEach { images.put(it) }
        val garment = garments.create(jacket, "photo")
        garments.setExtractedImage(garment.garment.id, "extracted")
        val model = mirror.approveModel("model", generation)
        mirror.saveTryOn(model, garment.garment.id, "photo", GarmentImageKind.PHOTO, generation, "result")

        mirror.removeUnreferencedImages()

        assertEquals(setOf("model", "result", "extracted"), images.stored.keys)
    }

    @Test
    fun extractionKeepsThePhotoAndGarmentIdentity() = runTest {
        val saved = garments.create(jacket, "photo")
        val extracted = garments.setExtractedImage(saved.garment.id, "extracted")
        assertEquals(saved.garment, extracted.garment)
        assertEquals("photo", extracted.photoId)
        assertEquals("extracted", extracted.extractedImageId)
        assertEquals(emptyList(), photos.deleted)
    }

    @Test
    fun replacingAnExtractionDeletesTheOldOne() = runTest {
        images.put("first")
        images.put("second")
        val id = garments.create(jacket, "photo").garment.id
        garments.setExtractedImage(id, "first")
        garments.setExtractedImage(id, "second")
        assertEquals(listOf("first"), images.deleted)
    }

    @Test
    fun clearingAnExtractionReturnsToThePhoto() = runTest {
        images.put("first")
        val id = garments.create(jacket, "photo").garment.id
        garments.setExtractedImage(id, "first")
        val cleared = garments.setExtractedImage(id, null)
        assertNull(cleared.extractedImageId)
        assertEquals("photo", cleared.photoId)
    }

    @Test
    fun changingThePhotoDropsItsExtraction() = runTest {
        images.put("first")
        val id = garments.create(jacket, "photo").garment.id
        garments.setExtractedImage(id, "first")
        val replaced = garments.update(id, jacket, PhotoChange.Set("photo-2"))
        assertNull(replaced.extractedImageId)
        assertEquals(listOf("first"), images.deleted)
    }

    @Test
    fun editingDetailsAndArchivingKeepTheExtraction() = runTest {
        val id = garments.create(jacket, "photo").garment.id
        garments.setExtractedImage(id, "first")
        assertEquals("first", garments.update(id, jacket.copy(name = "Coat")).extractedImageId)
        assertEquals("first", garments.setArchived(id, true).extractedImageId)
        assertTrue(images.deleted.isEmpty())
    }

    @Test
    fun backendSettingsDefaultThenPersistTrimmed() = runTest {
        assertEquals(BackendConfig("http://default:8787", ""), mirror.backendConfig())
        mirror.saveBackendConfig(BackendConfig(" http://192.168.1.5:8787 ", " token "))
        driver.close()
        open()
        assertEquals(BackendConfig("http://192.168.1.5:8787", "token"), mirror.backendConfig())
    }

    @Test
    fun theTokenIsKeptInSecureStorageNotTheDatabase() = runTest {
        mirror.saveBackendConfig(BackendConfig("http://mac.local:8787", "secret"))
        assertEquals("secret", credentials.values[Credential.MIRROR_DEV_TOKEN])
        assertNull(setting("mirror_backend_token"))
        assertEquals("http://mac.local:8787", setting("mirror_backend_url"))

        mirror.saveBackendConfig(BackendConfig("http://mac.local:8787", " "))
        assertNull(credentials.values[Credential.MIRROR_DEV_TOKEN])
        assertEquals(BackendConfig("http://mac.local:8787", ""), mirror.backendConfig())
    }

    @Test
    fun aTokenFromAnEarlierVersionMovesToSecureStorage() = runTest {
        val queries = WadrovaDatabase(driver).mirrorQueries
        queries.upsertSetting("mirror_backend_url", "http://mac.local:8787")
        queries.upsertSetting("mirror_backend_token", "legacy")

        assertEquals(BackendConfig("http://mac.local:8787", "legacy"), mirror.backendConfig())
        assertEquals("legacy", credentials.values[Credential.MIRROR_DEV_TOKEN])
        assertNull(setting("mirror_backend_token"))
        driver.close()
        open()
        assertEquals(BackendConfig("http://mac.local:8787", "legacy"), mirror.backendConfig())
    }

    @Test
    fun anEarlierTokenStaysUsableWhenSecureStorageRefusesIt() = runTest {
        WadrovaDatabase(driver).mirrorQueries.upsertSetting("mirror_backend_token", "legacy")
        credentials.failure = CredentialStoreException("refused")

        assertEquals("legacy", mirror.backendConfig().token)
        assertEquals("legacy", setting("mirror_backend_token"))

        credentials.failure = null
        assertEquals("legacy", mirror.backendConfig().token)
        assertNull(setting("mirror_backend_token"))
    }

    @Test
    fun anUnreadableSecureStoreOpensMirrorWithoutAToken() = runTest {
        credentials.failure = CredentialStoreException("locked")
        assertEquals(BackendConfig("http://default:8787", ""), mirror.backendConfig())
    }

    private fun setting(key: String): String? = WadrovaDatabase(driver).mirrorQueries.selectSetting(key).executeAsOneOrNull()
}
