package com.example.wadrova.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

fun createLocalRepositories(context: Context): LocalRepositories {
    val driver = AndroidSqliteDriver(WadrovaDatabase.Schema, context.applicationContext, "wadrova.db")
    val photos = AndroidGarmentPhotoStore(context)
    val credentials = AndroidCredentialStore(context)
    val generatedImages = AndroidGeneratedImageStore(context)
    val generatedImageGuard = GeneratedImageGuard()
    return LocalRepositories(
        SqlDelightSavedLookRepository(driver),
        SqlDelightGarmentRepository(driver, photos, generatedImages),
        photos,
        generatedImages,
        SqlDelightMirrorRepository(driver, generatedImages, credentials, DEFAULT_MIRROR_BACKEND_URL, guard = generatedImageGuard),
        KtorMirrorBackend(createMirrorHttpClient()),
        SqlDelightBrandIntroStore(driver),
        credentials,
        SqlDelightGarmentImportRepository(driver),
        KtorWadrovaApi(createWadrovaHttpClient()),
        NoBackgroundGrace,
        generatedImageGuard,
    )
}
