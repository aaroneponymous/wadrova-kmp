package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Remembers whether the full brand splash has played on this install. */
interface BrandIntroStore {
    suspend fun hasSeenIntro(): Boolean

    suspend fun markIntroSeen()
}

/** One row in the existing `app_setting` table, so no schema change is needed. */
class SqlDelightBrandIntroStore(
    driver: SqlDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BrandIntroStore {
    private val queries = WadrovaDatabase(driver).mirrorQueries

    override suspend fun hasSeenIntro(): Boolean = withContext(dispatcher) {
        queries.selectSetting(KEY_INTRO_SEEN).executeAsOneOrNull() == "1"
    }

    override suspend fun markIntroSeen() {
        withContext(dispatcher) { queries.upsertSetting(KEY_INTRO_SEEN, "1") }
    }

    private companion object {
        const val KEY_INTRO_SEEN = "brand_intro_seen"
    }
}
