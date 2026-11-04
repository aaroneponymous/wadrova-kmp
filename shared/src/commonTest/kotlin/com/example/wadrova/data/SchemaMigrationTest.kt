package com.example.wadrova.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The database exactly as version 1 of the app created it. */
private object SchemaV1 : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 1L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
            CREATE TABLE saved_look (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE saved_look_garment (
                look_id TEXT NOT NULL REFERENCES saved_look(id),
                garment_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (look_id, garment_id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO saved_look VALUES ('look-1', 'Quiet Monday', 10, 20)", 0)
        driver.execute(null, "INSERT INTO saved_look_garment VALUES ('look-1', 'shirt', 0)", 0)
        driver.execute(null, "INSERT INTO saved_look_garment VALUES ('look-1', 'retired-piece', 1)", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/** Version 2 added user_garment, before garments had photos. */
private object SchemaV2 : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 2L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaV1.create(driver)
        driver.execute(
            null,
            """
            CREATE TABLE user_garment (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                color TEXT NOT NULL,
                brand TEXT,
                material TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                archived_at INTEGER
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO user_garment VALUES ('user-1', 'Field Jacket', 'OUTERWEAR', 'Olive', 'Acme', NULL, 5, 6, 7)", 0)
        driver.execute(null, "INSERT INTO saved_look_garment VALUES ('look-1', 'user-1', 2)", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/** Version 3 added photos, before extraction and Mirror. */
private object SchemaV3 : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 3L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaV2.create(driver)
        driver.execute(null, "ALTER TABLE user_garment ADD COLUMN photo_id TEXT", 0)
        driver.execute(null, "UPDATE user_garment SET photo_id = 'photo-1'", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/** Version 4 added extraction, Mirror and settings, with the Mirror token still in app_setting. */
private object SchemaV4 : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 4L

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaV3.create(driver)
        driver.execute(null, "ALTER TABLE user_garment ADD COLUMN extracted_image_id TEXT", 0)
        driver.execute(
            null,
            """
            CREATE TABLE mirror_model (
                model_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                image_id TEXT NOT NULL,
                generation_model TEXT NOT NULL,
                generation_quality TEXT NOT NULL,
                approved_at INTEGER NOT NULL,
                PRIMARY KEY (model_id, version)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE try_on_result (
                id TEXT NOT NULL PRIMARY KEY,
                model_id TEXT NOT NULL,
                model_version INTEGER NOT NULL,
                garment_id TEXT NOT NULL,
                source_image_id TEXT NOT NULL,
                source_image_kind TEXT NOT NULL,
                generation_model TEXT NOT NULL,
                generation_quality TEXT NOT NULL,
                result_image_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE TABLE app_setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
        driver.execute(null, "UPDATE user_garment SET extracted_image_id = 'extracted-1'", 0)
        driver.execute(null, "INSERT INTO app_setting VALUES ('mirror_backend_url', 'http://mac.local:8787')", 0)
        driver.execute(null, "INSERT INTO app_setting VALUES ('mirror_backend_token', 'legacy-token')", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

class SchemaMigrationTest {
    private val databaseName = "migration_test"

    @BeforeTest
    fun setUp() = deleteTestDatabase(databaseName)

    @AfterTest
    fun tearDown() = deleteTestDatabase(databaseName)

    @Test
    fun versionOneDatabaseUpgradesWithoutLosingLooks() = runTest {
        // The native driver opens lazily, so read from the old database to make sure it's created.
        val oldDriver = openTestDatabase(databaseName, SchemaV1)
        val oldLookCount = oldDriver.executeQuery(
            null,
            "SELECT COUNT(*) FROM saved_look",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value
        assertEquals(1L, oldLookCount)
        oldDriver.close()

        val driver = openTestDatabase(databaseName)
        try {
            val looks = SqlDelightSavedLookRepository(driver)
            val garments = SqlDelightGarmentRepository(driver, FakePhotoStore(), FakeGeneratedImageStore())

            val look = looks.getAll().single()
            assertEquals("look-1", look.id)
            assertEquals("Quiet Monday", look.name)
            assertEquals(listOf("shirt", "retired-piece"), look.garmentIds)
            assertEquals(10L, look.createdAtMillis)

            val added = garments.create(GarmentDetails("Field Jacket", GarmentCategory.OUTERWEAR, "Green"))
            assertEquals(listOf(added), garments.getAll())
            assertEquals(listOf(added.garment.id), looks.create("New", listOf(added.garment.id)).garmentIds)
            // The old look keeps its reference to a piece that no longer exists, shown as unavailable.
            assertEquals(listOf("shirt", "retired-piece"), looks.getAll().first { it.id == "look-1" }.garmentIds)
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionTwoDatabaseUpgradesWithGarmentsArchivalAndLookReferencesIntact() = runTest {
        // The native driver opens lazily, so read from the old database to make sure it's created.
        val oldDriver = openTestDatabase(databaseName, SchemaV2)
        oldDriver.executeQuery(null, "SELECT COUNT(*) FROM user_garment", { QueryResult.Value(it.next().value) }, 0)
        oldDriver.close()

        val driver = openTestDatabase(databaseName)
        try {
            val garments = SqlDelightGarmentRepository(driver, FakePhotoStore(mutableSetOf("photo")), FakeGeneratedImageStore())
            val looks = SqlDelightSavedLookRepository(driver)

            val garment = garments.getAll().single()
            assertEquals("user-1", garment.garment.id)
            assertEquals("Field Jacket", garment.name)
            assertEquals("Acme", garment.brand)
            assertTrue(garment.archived)
            assertEquals(null, garment.photoId)
            assertEquals(listOf("shirt", "retired-piece", "user-1"), looks.getAll().single().garmentIds)

            val details = GarmentDetails(garment.name, GarmentCategory.OUTERWEAR, garment.color, garment.brand)
            val withPhoto = garments.update("user-1", details, PhotoChange.Set("photo"))
            assertEquals("photo", withPhoto.photoId)
            assertTrue(withPhoto.archived)
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionThreeDatabaseUpgradesWithPhotosAndLooksIntactAndMirrorTablesReady() = runTest {
        val oldDriver = openTestDatabase(databaseName, SchemaV3)
        oldDriver.executeQuery(null, "SELECT COUNT(*) FROM user_garment", { QueryResult.Value(it.next().value) }, 0)
        oldDriver.close()

        val driver = openTestDatabase(databaseName)
        try {
            val images = FakeGeneratedImageStore()
            val garments = SqlDelightGarmentRepository(driver, FakePhotoStore(mutableSetOf("photo-1")), images)
            val looks = SqlDelightSavedLookRepository(driver)
            val mirror = SqlDelightMirrorRepository(driver, images, FakeCredentialStore(), "http://default")

            val garment = garments.getAll().single()
            assertEquals("photo-1", garment.photoId)
            assertEquals(null, garment.extractedImageId)
            assertTrue(garment.archived)
            assertEquals(listOf("shirt", "retired-piece", "user-1"), looks.getAll().single().garmentIds)
            assertEquals(null, mirror.approvedModel())
            assertEquals(emptyList(), mirror.tryOns())
            assertEquals("http://default", mirror.backendConfig().url)
            assertEquals("extracted", garments.setExtractedImage("user-1", "extracted").extractedImageId)
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionFourDatabaseUpgradesWithEverythingIntactAndImportsReady() = runTest {
        val oldDriver = openTestDatabase(databaseName, SchemaV4)
        oldDriver.executeQuery(null, "SELECT COUNT(*) FROM user_garment", { QueryResult.Value(it.next().value) }, 0)
        oldDriver.close()

        val driver = openTestDatabase(databaseName)
        try {
            val photos = FakePhotoStore(mutableSetOf("photo-1", "import-photo", "stray"))
            val credentials = FakeCredentialStore()
            val garments = SqlDelightGarmentRepository(driver, photos, FakeGeneratedImageStore())
            val mirror = SqlDelightMirrorRepository(driver, FakeGeneratedImageStore(), credentials, "http://default")
            val imports = SqlDelightGarmentImportRepository(driver)

            val garment = garments.getAll().single()
            assertEquals("photo-1", garment.photoId)
            assertEquals("extracted-1", garment.extractedImageId)
            assertEquals(listOf("shirt", "retired-piece", "user-1"), SqlDelightSavedLookRepository(driver).getAll().single().garmentIds)
            assertEquals(BackendConfig("http://mac.local:8787", "legacy-token"), mirror.backendConfig())
            assertEquals("legacy-token", credentials.values[Credential.MIRROR_DEV_TOKEN])

            assertEquals(emptyList(), imports.getAll())
            imports.createOrReuse("kmp-imp-1", "import-photo", "ab".repeat(32), 10)
            garments.removeUnreferencedPhotos()
            assertEquals(setOf("photo-1", "import-photo"), photos.stored)
        } finally {
            driver.close()
        }
    }
}
