package com.example.wadrova.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createSavedLookRepository(context: Context): SavedLookRepository =
    SqlDelightSavedLookRepository(
        AndroidSqliteDriver(WadrovaDatabase.Schema, context.applicationContext, "wadrova.db"),
        sampleWardrobe.map { it.garment },
    )
