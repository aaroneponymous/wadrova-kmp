package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.ApprovedModel
import com.example.wadrova.domain.GarmentImageKind
import com.example.wadrova.domain.TryOnResult
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MirrorRepository {
    /** The newest approved version, which every new try-on uses. */
    suspend fun approvedModel(): ApprovedModel?

    /** Records [imageId] as a new version. Earlier versions stay, since accepted previews refer to them. */
    suspend fun approveModel(imageId: String, generation: GeneratedImage): ApprovedModel

    /** Deletes every model version and accepted preview, with their image files. */
    suspend fun removeModelAndPreviews()

    suspend fun tryOns(): List<TryOnResult>

    suspend fun saveTryOn(
        model: ApprovedModel,
        garmentId: String,
        sourceImageId: String,
        sourceImageKind: GarmentImageKind,
        generation: GeneratedImage,
        resultImageId: String,
    ): TryOnResult

    suspend fun deleteTryOn(id: String)

    suspend fun backendConfig(): BackendConfig

    suspend fun saveBackendConfig(config: BackendConfig)

    /** Deletes generated files nothing refers to, such as unapproved results left by a crash. */
    suspend fun removeUnreferencedImages()
}

@OptIn(ExperimentalUuidApi::class)
class SqlDelightMirrorRepository(
    driver: SqlDriver,
    private val images: GeneratedImageStore,
    private val credentials: CredentialStore,
    private val defaultBackendUrl: String,
    private val newId: () -> String = { Uuid.random().toString() },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MirrorRepository {
    private val database = WadrovaDatabase(driver)
    private val queries = database.mirrorQueries

    override suspend fun approvedModel(): ApprovedModel? = withContext(dispatcher) { latestModel() }

    private fun latestModel(): ApprovedModel? = queries.selectLatestModel().executeAsOneOrNull()?.let {
        ApprovedModel(it.model_id, it.version, it.image_id, it.generation_model, it.generation_quality)
    }

    override suspend fun approveModel(imageId: String, generation: GeneratedImage): ApprovedModel = withContext(dispatcher) {
        queries.transactionWithResult {
            val latest = latestModel()
            val model = ApprovedModel(
                modelId = latest?.modelId ?: newId(),
                version = (latest?.version ?: 0) + 1,
                imageId = imageId,
                generationModel = generation.model,
                generationQuality = generation.quality,
            )
            queries.insertModel(model.modelId, model.version, imageId, generation.model, generation.quality, nowMillis())
            model
        }
    }

    override suspend fun removeModelAndPreviews() = withContext(dispatcher) {
        val imageIds = queries.transactionWithResult {
            val ids = queries.selectModelImageIds().executeAsList() + queries.selectTryOnImageIds().executeAsList()
            queries.deleteAllTryOns()
            queries.deleteAllModels()
            ids
        }
        imageIds.forEach { deleteQuietly(it) }
    }

    override suspend fun tryOns(): List<TryOnResult> = withContext(dispatcher) {
        queries.selectTryOns().executeAsList().mapNotNull { row ->
            val kind = GarmentImageKind.entries.find { it.name == row.source_image_kind } ?: return@mapNotNull null
            TryOnResult(
                row.id, row.model_id, row.model_version, row.garment_id, row.source_image_id, kind,
                row.generation_model, row.generation_quality, row.result_image_id,
            )
        }
    }

    override suspend fun saveTryOn(
        model: ApprovedModel,
        garmentId: String,
        sourceImageId: String,
        sourceImageKind: GarmentImageKind,
        generation: GeneratedImage,
        resultImageId: String,
    ): TryOnResult = withContext(dispatcher) {
        val result = TryOnResult(
            newId(), model.modelId, model.version, garmentId, sourceImageId, sourceImageKind,
            generation.model, generation.quality, resultImageId,
        )
        queries.insertTryOn(
            result.id, result.modelId, result.modelVersion, garmentId, sourceImageId, sourceImageKind.name,
            generation.model, generation.quality, resultImageId, nowMillis(),
        )
        result
    }

    override suspend fun deleteTryOn(id: String) = withContext(dispatcher) {
        val imageId = queries.transactionWithResult {
            val row = queries.selectTryOn(id).executeAsOneOrNull()
            queries.deleteTryOn(id)
            row?.result_image_id
        }
        imageId?.let { deleteQuietly(it) } ?: Unit
    }

    /**
     * The token lives in [credentials]. Earlier versions kept it in `app_setting`, which backups can include, so a
     * token found there is moved over and the row deleted once the secure copy is written.
     */
    override suspend fun backendConfig(): BackendConfig = withContext(dispatcher) {
        val url = queries.selectSetting(KEY_BACKEND_URL).executeAsOneOrNull() ?: defaultBackendUrl
        val legacy = queries.selectSetting(LEGACY_KEY_BACKEND_TOKEN).executeAsOneOrNull()
        if (legacy != null) {
            try {
                if (legacy.isNotEmpty()) credentials.write(Credential.MIRROR_DEV_TOKEN, legacy)
                queries.deleteSetting(LEGACY_KEY_BACKEND_TOKEN)
            } catch (_: CredentialStoreException) {
                // Mirror keeps working from the old row; the move is tried again on the next read.
                return@withContext BackendConfig(url, legacy)
            }
            if (legacy.isNotEmpty()) return@withContext BackendConfig(url, legacy)
        }
        // An unreadable store reads as "no token": Mirror then asks for one rather than failing to open.
        val token = try {
            credentials.read(Credential.MIRROR_DEV_TOKEN).orEmpty()
        } catch (_: CredentialStoreException) {
            ""
        }
        BackendConfig(url, token)
    }

    override suspend fun saveBackendConfig(config: BackendConfig) = withContext(dispatcher) {
        val token = config.token.trim()
        if (token.isEmpty()) credentials.delete(Credential.MIRROR_DEV_TOKEN) else credentials.write(Credential.MIRROR_DEV_TOKEN, token)
        queries.transaction {
            queries.upsertSetting(KEY_BACKEND_URL, config.url.trim())
            queries.deleteSetting(LEGACY_KEY_BACKEND_TOKEN)
        }
    }

    override suspend fun removeUnreferencedImages() = withContext(dispatcher) {
        val referenced = queries.transactionWithResult {
            queries.selectModelImageIds().executeAsList() +
                queries.selectTryOnImageIds().executeAsList() +
                database.garmentQueries.selectExtractedImageIds().executeAsList()
        }.toSet()
        (images.storedIds() - referenced).forEach { deleteQuietly(it) }
    }

    /** A file that can't be deleted now is cleared by the next [removeUnreferencedImages]. */
    private suspend fun deleteQuietly(imageId: String) {
        try {
            images.delete(imageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEY_BACKEND_URL = "mirror_backend_url"
        const val LEGACY_KEY_BACKEND_TOKEN = "mirror_backend_token"
    }
}
