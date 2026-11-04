package com.example.wadrova.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver

fun createLocalRepositories(): LocalRepositories {
    val driver = NativeSqliteDriver(WadrovaDatabase.Schema, "wadrova.db")
    val photos = IosGarmentPhotoStore()
    val credentials = IosCredentialStore()
    val generatedImages = IosGeneratedImageStore()
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver),
        SqlDelightGarmentRepository(driver, photos, generatedImages),
        photos,
        generatedImages,
        SqlDelightMirrorRepository(driver, generatedImages, credentials, DEFAULT_MIRROR_BACKEND_URL),
        KtorMirrorBackend(createMirrorHttpClient()),
        SqlDelightBrandIntroStore(driver),
        credentials,
        SqlDelightGarmentImportRepository(driver),
        KtorWadrovaApi(createWadrovaHttpClient()),
        IosBackgroundGrace,
    )
}
