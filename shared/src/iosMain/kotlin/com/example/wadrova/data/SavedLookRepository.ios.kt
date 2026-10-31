package com.example.wadrova.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createLocalRepositories(): LocalRepositories {
    val driver = NativeSqliteDriver(WadrovaDatabase.Schema, "wadrova.db")
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver, sampleWardrobe.map { it.garment }),
        SqlDelightGarmentRepository(driver),
    )
}
