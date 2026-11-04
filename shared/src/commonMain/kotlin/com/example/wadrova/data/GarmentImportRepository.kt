package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface GarmentImportRepository {
    suspend fun getAll(): List<GarmentImport>

    suspend fun get(id: String): GarmentImport?

    /**
     * Records a new import of an already stored photo, or returns the open import of the same bytes instead.
     * The second value is true when [id] was created.
     */
    suspend fun createOrReuse(id: String, photoId: String, sourceSha256: String, sourceByteSize: Long): Pair<GarmentImport, Boolean>

    /**
     * Applies [change] to the current row in one transaction, so a decision made meanwhile (such as a cancel) is
     * never overwritten by a stale copy. Returns null if the import is gone.
     */
    suspend fun update(id: String, change: (GarmentImport, Long) -> GarmentImport): GarmentImport?

    suspend fun delete(id: String)

    /** Run once at launch: no upload can still be in flight from an earlier process. */
    suspend fun resetInterruptedUploads()

    /** The Wadrova API address. Not secret, so it's kept with other settings; the token is in [CredentialStore]. */
    suspend fun apiUrl(): String?

    suspend fun saveApiUrl(url: String)
}

class SqlDelightGarmentImportRepository(
    driver: SqlDriver,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GarmentImportRepository {
    private val database = WadrovaDatabase(driver)
    private val queries = database.garmentImportQueries

    override suspend fun getAll(): List<GarmentImport> = withContext(dispatcher) {
        queries.selectImports().executeAsList().map { it.toDomain() }
    }

    override suspend fun get(id: String): GarmentImport? = withContext(dispatcher) {
        queries.selectImport(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun createOrReuse(
        id: String,
        photoId: String,
        sourceSha256: String,
        sourceByteSize: Long,
    ): Pair<GarmentImport, Boolean> = withContext(dispatcher) {
        val now = nowMillis()
        queries.transactionWithResult {
            val open = queries.selectOpenImportBySha256(sourceSha256).executeAsOneOrNull()
            if (open != null) {
                open.toDomain() to false
            } else {
                queries.insertImport(id, photoId, sourceSha256, sourceByteSize, now, now)
                queries.selectImport(id).executeAsOne().toDomain() to true
            }
        }
    }

    override suspend fun update(id: String, change: (GarmentImport, Long) -> GarmentImport): GarmentImport? = withContext(dispatcher) {
        val now = nowMillis()
        queries.transactionWithResult {
            val current = queries.selectImport(id).executeAsOneOrNull()?.toDomain() ?: return@transactionWithResult null
            val next = change(current, now)
            queries.updateImport(
                next.state.name,
                next.uploadAttempts.toLong(),
                next.problem?.name,
                next.problemMessage,
                next.serverImportId,
                next.serverSessionId,
                next.serverStatus,
                next.candidateCount?.toLong(),
                next.pollAfterSeconds?.toLong(),
                next.updatedAt,
                id,
            )
            next
        }
    }

    override suspend fun delete(id: String) {
        withContext(dispatcher) { queries.deleteImport(id) }
    }

    override suspend fun resetInterruptedUploads() {
        withContext(dispatcher) { queries.resetInterruptedUploads(nowMillis()) }
    }

    override suspend fun apiUrl(): String? = withContext(dispatcher) {
        database.mirrorQueries.selectSetting(KEY_API_URL).executeAsOneOrNull()
    }

    override suspend fun saveApiUrl(url: String) {
        withContext(dispatcher) { database.mirrorQueries.upsertSetting(KEY_API_URL, url) }
    }

    /** A state or problem this version doesn't know (from a newer build) reads as waiting to upload, which is safe to retry. */
    private fun Garment_import.toDomain() = GarmentImport(
        id = id,
        photoId = photo_id,
        sourceSha256 = source_sha256,
        sourceByteSize = source_byte_size,
        state = ImportState.entries.find { it.name == state } ?: ImportState.UPLOAD_PENDING,
        uploadAttempts = upload_attempts.toInt(),
        problem = problem?.let { name -> ImportProblem.entries.find { it.name == name } },
        problemMessage = problem_message,
        serverImportId = server_import_id,
        serverSessionId = server_session_id,
        serverStatus = server_status,
        candidateCount = candidate_count?.toInt(),
        pollAfterSeconds = poll_after_seconds?.toInt(),
        createdAt = created_at,
        updatedAt = updated_at,
    )

    private companion object {
        const val KEY_API_URL = "wadrova_api_url"
    }
}
