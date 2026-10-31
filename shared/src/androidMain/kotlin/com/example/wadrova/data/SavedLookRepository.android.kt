package com.example.wadrova.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createLocalRepositories(context: Context): LocalRepositories {
    val driver = AndroidSqliteDriver(WadrovaDatabase.Schema, context.applicationContext, "wadrova.db")
    val photos = AndroidGarmentPhotoStore(context)
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver, sampleWardrobe.map { it.garment }),
        SqlDelightGarmentRepository(driver, photos),
        photos,
    )
}
