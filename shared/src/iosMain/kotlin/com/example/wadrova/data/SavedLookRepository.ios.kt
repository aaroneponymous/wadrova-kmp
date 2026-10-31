package com.example.wadrova.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createSavedLookRepository(): SavedLookRepository =
    SqlDelightSavedLookRepository(
        NativeSqliteDriver(WadrovaDatabase.Schema, "wadrova.db"),
        sampleWardrobe.map { it.garment },
    )
