package com.example.wadrova.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createLocalRepositories(): LocalRepositories {
    val driver = NativeSqliteDriver(WadrovaDatabase.Schema, "wadrova.db")
    val photos = IosGarmentPhotoStore()
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver, sampleWardrobe.map { it.garment }),
        SqlDelightGarmentRepository(driver, photos),
        photos,
    )
}
