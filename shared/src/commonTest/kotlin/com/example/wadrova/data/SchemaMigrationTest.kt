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
            val looks = SqlDelightSavedLookRepository(driver, listOf(Garment("shirt", GarmentCategory.TOP)))
            val garments = SqlDelightGarmentRepository(driver)

            val look = looks.getAll().single()
            assertEquals("look-1", look.id)
            assertEquals("Quiet Monday", look.name)
            assertEquals(listOf("shirt", "retired-piece"), look.garmentIds)
            assertEquals(10L, look.createdAtMillis)

            val added = garments.create(GarmentDetails("Field Jacket", GarmentCategory.OUTERWEAR, "Green"))
            assertEquals(listOf(added), garments.getAll())
            assertEquals(2, looks.create("New", listOf("shirt", added.garment.id)).garmentIds.size)
        } finally {
            driver.close()
        }
    }
}
