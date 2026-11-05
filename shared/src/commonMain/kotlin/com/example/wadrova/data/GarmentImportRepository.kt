package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import com.example.wadrova.domain.PendingCorrection
import com.example.wadrova.domain.ReviewChoice
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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

    /** The stored review choices for [lineageIds], creating a row (and its garment id) for any lineage that has none. */
    suspend fun reviewChoices(importId: String, lineageIds: List<String>): Map<String, ReviewChoice>

    suspend fun saveReviewChoice(importId: String, choice: ReviewChoice)

    /**
     * In one transaction: the accepted garments, their provenance, the finalize manifest and the import's move to
     * [ImportState.FINALIZE_PENDING]. The garment photos must already be stored. A garment whose id already exists is
     * left as it is, so a repeated save is harmless. Returns the photo ids that weren't used, for the caller to delete;
     * a piece whose photo wasn't used didn't use its cutout either.
     * Throws [InvalidGarmentException] for invalid details, writing nothing.
     */
    suspend fun saveReviewedPieces(importId: String, pieces: List<ReviewedPiece>, manifest: FinalizeManifest): List<String>

    /** The manifest stored by [saveReviewedPieces]: what every finalize attempt sends. Null before saving. */
    suspend fun storedManifest(importId: String): FinalizeManifest?

    /**
     * Adds never-accepted lineages to the stored manifest as rejected: before the first send (reconciling with the
     * server's current candidates), or after a `missing_decisions` refusal when the user asks. Returns how many were
     * new; zero means the stored manifest already had them all.
     */
    suspend fun addRejectedDecisions(importId: String, candidateIds: List<String>): Int

    /** Garment ids saved from this import, for showing the result. */
    suspend fun savedGarmentIds(importId: String): List<String>

    /** The server recorded the decisions: the import is done. Provenance and decisions stay; the review rows go. */
    suspend fun completeImport(importId: String)

    /** Freezes the stored decisions. Written before the first finalize request leaves the device. */
    suspend fun markFinalizeSent(importId: String)

    /** Which of [imageIds] a garment uses as its photo or cutout; such files must never be deleted by a save's cleanup. */
    suspend fun linkedImageIds(imageIds: Collection<String>): Set<String>
}

/**
 * One accepted lineage, ready to become a garment. [photoId] is the already stored, verified cutout flattened onto
 * white; [cutoutImageId], when there is one, is the same cutout kept transparent in the generated-image store.
 */
data class ReviewedPiece(
    val garmentId: String,
    val details: GarmentDetails,
    val photoId: String,
    val lineageId: String,
    val candidateId: String,
    val revision: Int,
    val cutoutImageId: String? = null,
)

@OptIn(ExperimentalUuidApi::class)
class SqlDelightGarmentImportRepository(
    driver: SqlDriver,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Same form as manually added garments' ids, so imported garments are ordinary garments. */
    private val newGarmentId: () -> String = { "user-${Uuid.random()}" },
) : GarmentImportRepository {
    private val database = WadrovaDatabase(driver)
    private val queries = database.garmentImportQueries
    private val reviews = database.garmentImportReviewQueries

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
            write(next)
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

    override suspend fun reviewChoices(importId: String, lineageIds: List<String>): Map<String, ReviewChoice> = withContext(dispatcher) {
        val now = nowMillis()
        reviews.transactionWithResult {
            lineageIds.forEach { reviews.insertReviewChoice(importId, it, newGarmentId(), now) }
            reviews.selectReviewChoices(importId).executeAsList().associate { it.lineage_id to it.toChoice() }
        }
    }

    override suspend fun saveReviewChoice(importId: String, choice: ReviewChoice) {
        withContext(dispatcher) {
            reviews.updateReviewChoice(
                if (choice.selected) 1 else 0,
                choice.chosenCandidateId,
                choice.name,
                choice.category?.name,
                choice.color,
                choice.correction?.key,
                choice.correction?.id,
                choice.correction?.method,
                choice.correctionMessage,
                nowMillis(),
                importId,
                choice.lineageId,
            )
        }
    }

    override suspend fun saveReviewedPieces(importId: String, pieces: List<ReviewedPiece>, manifest: FinalizeManifest): List<String> =
        withContext(dispatcher) {
            val clean = pieces.map { it.copy(details = it.details.validated()) }
            val now = nowMillis()
            database.transactionWithResult {
                val current = queries.selectImport(importId).executeAsOneOrNull()?.toDomain()
                    ?: throw InvalidGarmentException("This import no longer exists.")
                // Already saved (a repeated tap or a retry after a crash): keep what's stored, use none of the new photos.
                if (current.state == ImportState.FINALIZE_PENDING || current.state == ImportState.COMPLETED) {
                    return@transactionWithResult clean.map { it.photoId }
                }
                val serverImportId = current.serverImportId ?: throw InvalidGarmentException("This import hasn't reached the server.")
                val unused = mutableListOf<String>()
                clean.forEach { piece ->
                    if (database.garmentQueries.selectGarment(piece.garmentId).executeAsOneOrNull() != null) {
                        unused += piece.photoId
                    } else {
                        val d = piece.details
                        database.garmentQueries.insertImportedGarment(
                            piece.garmentId, d.name, d.category.name, d.color, d.brand, d.material, now, now, piece.photoId, piece.cutoutImageId,
                        )
                        reviews.insertSource(piece.garmentId, importId, serverImportId, piece.lineageId, piece.candidateId, piece.revision.toLong(), piece.photoId, now)
                    }
                }
                reviews.deleteDecisions(importId)
                manifest.accepted.forEach { (candidate, garment) -> reviews.insertDecision(importId, candidate, garment) }
                manifest.rejected.forEach { reviews.insertDecision(importId, it, null) }
                write(current.finalizePending(now))
                unused
            }
        }

    override suspend fun storedManifest(importId: String): FinalizeManifest? = withContext(dispatcher) {
        val rows = reviews.selectDecisions(importId).executeAsList()
        if (rows.isEmpty() && queries.selectImport(importId).executeAsOneOrNull()?.state != ImportState.FINALIZE_PENDING.name) {
            null
        } else {
            FinalizeManifest(
                accepted = rows.mapNotNull { row -> row.garment_id?.let { row.candidate_id to it } },
                rejected = rows.filter { it.garment_id == null }.map { it.candidate_id },
            )
        }
    }

    override suspend fun addRejectedDecisions(importId: String, candidateIds: List<String>): Int = withContext(dispatcher) {
        reviews.transactionWithResult {
            val known = reviews.selectDecisions(importId).executeAsList().map { it.candidate_id }.toSet()
            val added = candidateIds.distinct().filter { it !in known }
            added.forEach { reviews.insertDecision(importId, it, null) }
            added.size
        }
    }

    override suspend fun savedGarmentIds(importId: String): List<String> = withContext(dispatcher) {
        reviews.selectSources(importId).executeAsList().map { it.garment_id }
    }

    override suspend fun completeImport(importId: String) {
        withContext(dispatcher) {
            database.transaction {
                queries.selectImport(importId).executeAsOneOrNull()?.toDomain()?.let { write(it.completed(nowMillis())) }
                reviews.deleteReviewChoices(importId)
            }
        }
    }

    override suspend fun linkedImageIds(imageIds: Collection<String>): Set<String> = withContext(dispatcher) {
        if (imageIds.isEmpty()) return@withContext emptySet()
        val ids = imageIds.toSet()
        val queries = database.garmentQueries
        queries.transactionWithResult {
            (queries.selectLinkedPhotoIds(ids).executeAsList() + queries.selectLinkedCutoutIds(ids).executeAsList()).toSet()
        }
    }

    override suspend fun markFinalizeSent(importId: String) {
        withContext(dispatcher) {
            val now = nowMillis()
            queries.markFinalizeSent(now, now, importId)
        }
    }

    private fun write(next: GarmentImport) {
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
            next.id,
        )
    }

    private fun Garment_import_review.toChoice() = ReviewChoice(
        lineageId = lineage_id,
        garmentId = garment_id,
        selected = selected != 0L,
        chosenCandidateId = chosen_candidate_id,
        name = name,
        category = category?.let { c -> GarmentCategory.entries.find { it.name == c } },
        color = color,
        correction = correction_key?.let { PendingCorrection(it, correction_id, correction_method.orEmpty()) },
        correctionMessage = correction_message,
    )

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
        finalizeSentAt = finalize_sent_at,
    )

    private companion object {
        const val KEY_API_URL = "wadrova_api_url"
    }
}
