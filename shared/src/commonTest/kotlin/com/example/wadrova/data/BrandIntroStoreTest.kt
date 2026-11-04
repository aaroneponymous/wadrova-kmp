package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BrandIntroStoreTest {
    private val databaseName = "brand_intro_test"
    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setUp() {
        deleteTestDatabase(databaseName)
        driver = openTestDatabase(databaseName)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        deleteTestDatabase(databaseName)
    }

    @Test
    fun aNewInstallHasNotSeenTheIntro() = runTest {
        assertFalse(SqlDelightBrandIntroStore(driver).hasSeenIntro())
    }

    @Test
    fun theFlagSurvivesReopeningTheDatabase() = runTest {
        SqlDelightBrandIntroStore(driver).markIntroSeen()
        driver.close()
        driver = openTestDatabase(databaseName)
        assertTrue(SqlDelightBrandIntroStore(driver).hasSeenIntro())
    }

    @Test
    fun theFlagLeavesMirrorSettingsAlone() = runTest {
        val mirror = SqlDelightMirrorRepository(driver, FakeGeneratedImageStore(), FakeCredentialStore(), "http://default:8787")
        mirror.saveBackendConfig(BackendConfig("http://mac.local:8787", "token"))
        SqlDelightBrandIntroStore(driver).markIntroSeen()
        assertEquals(BackendConfig("http://mac.local:8787", "token"), mirror.backendConfig())
    }
}
