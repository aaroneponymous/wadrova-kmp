package com.example.wadrova.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.wadrova.ui.sampleWardrobe

fun createLocalRepositories(): LocalRepositories {
    val driver = NativeSqliteDriver(WadrovaDatabase.Schema, "wadrova.db")
    val photos = IosGarmentPhotoStore()
    val generatedImages = IosGeneratedImageStore()
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver, sampleWardrobe.map { it.garment }),
        SqlDelightGarmentRepository(driver, photos, generatedImages),
        photos,
        generatedImages,
        SqlDelightMirrorRepository(driver, generatedImages, DEFAULT_MIRROR_BACKEND_URL),
        KtorMirrorBackend(createMirrorHttpClient()),
    )
}
