package com.example.wadrova.data

import com.example.wadrova.domain.ImportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Whether an existing garment's transparent cutout can be recovered, decided from local data only. */
enum class CutoutRecoveryEligibility {
    ELIGIBLE,

    /** Added manually, not imported. */
    NOT_IMPORTED,

    /** The user replaced or removed the photo since the import, so the cutout is no longer this garment's image. */
    PHOTO_REPLACED,

    /** The import hasn't finished with the server yet ("Saved, not synced"). */
    NOT_SYNCED,

    /** It already has a cutout whose file is present. */
    ALREADY_HAS_CUTOUT,
}

enum class CutoutRecoveryResult {
    RECOVERED,

    /** Nothing to do (not eligible, or another attempt linked a cutout first). */
    NOT_NEEDED,

    /** No connection configured, or the token was refused (401). The user can reconnect and try again. */
    NOT_CONNECTED,

    /** The server couldn't be reached or was unavailable, or the download didn't verify. Worth trying later. */
    UNREACHABLE,

    /**
     * The server doesn't have this garment's cutout for this account: the import or candidate is missing, or the
     * server's finalized record doesn't map this candidate to this garment (another server or account). Permanent.
     */
    NOT_ON_SERVER,

    /** The cutout downloaded but can't be kept (unsupported or invalid PNG, or the wrong size). Permanent. */
    UNUSABLE,

    /** The sanitized file couldn't be written. Worth trying later. */
    STORAGE_FAILED,

    /** The photo changed while recovering; nothing was linked. */
    CHANGED,
}

/**
 * Recovers the transparent cutout of a garment imported before cutouts were kept (or whose cutout file is missing),
 * from the provenance saved with it. Nothing runs by itself: each recovery is one user-requested attempt.
 *
 * The exact accepted candidate is taken from `garment_import_source`, and the server must confirm it: the import is
 * finalized, has the same session, and its recorded decisions map that candidate to this garment id. That also proves
 * the connection is the account and server the garment came from. The cutout is downloaded and checked against its
 * SHA-256 and recorded size, sanitized like a new import's ([sanitizedCutoutPng], profile kept, hidden color cleared),
 * stored, and linked by a conditional update that changes nothing else and requires the photo and cutout state to be
 * unchanged. A file that isn't linked is deleted; the launch sweep catches any left by a process kill.
 */
class CutoutRecovery(
    private val imports: GarmentImportRepository,
    private val api: WadrovaApi,
    private val generated: GeneratedImageStore,
    private val guard: GeneratedImageGuard,
    private val connection: suspend () -> WadrovaConnection?,
    private val imageDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun eligibility(garmentId: String): CutoutRecoveryEligibility = check(garmentId).first

    private suspend fun check(garmentId: String): Pair<CutoutRecoveryEligibility, CutoutRecoverySource?> {
        val source = imports.cutoutRecoverySource(garmentId) ?: return CutoutRecoveryEligibility.NOT_IMPORTED to null
        val result = when {
            source.photoId == null || source.photoId != source.sourcePhotoId -> CutoutRecoveryEligibility.PHOTO_REPLACED
            source.importState != ImportState.COMPLETED || source.serverSessionId == null -> CutoutRecoveryEligibility.NOT_SYNCED
            source.cutoutImageId != null && fileExists(source.cutoutImageId) -> CutoutRecoveryEligibility.ALREADY_HAS_CUTOUT
            else -> CutoutRecoveryEligibility.ELIGIBLE
        }
        return result to source
    }

    private suspend fun fileExists(imageId: String): Boolean = try {
        generated.read(imageId) != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Can't tell: assume it's there rather than replace a cutout that may be fine.
        true
    }

    suspend fun recover(garmentId: String): CutoutRecoveryResult {
        val (eligibility, source) = check(garmentId)
        if (eligibility != CutoutRecoveryEligibility.ELIGIBLE || source == null) return CutoutRecoveryResult.NOT_NEEDED
        val photoId = source.photoId ?: return CutoutRecoveryResult.NOT_NEEDED
        val sessionId = source.serverSessionId ?: return CutoutRecoveryResult.NOT_NEEDED
        val connection = try {
            connection()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return CutoutRecoveryResult.NOT_CONNECTED

        val png: ByteArray
        try {
            val record = api.getImport(connection, source.serverImportId)
            if (record.id != source.serverImportId || record.sessionId != sessionId || record.status != "finalized" ||
                record.acceptedGarments?.get(source.candidateId) != garmentId
            ) {
                return CutoutRecoveryResult.NOT_ON_SERVER
            }
            val candidate = api.candidates(connection, sessionId).find { it.id == source.candidateId && it.revision == source.revision }
                ?: return CutoutRecoveryResult.NOT_ON_SERVER
            val bytes = api.assetContent(connection, candidate.cutout)
            png = try {
                withContext(imageDispatcher) { sanitizedCutoutPng(bytes, candidate.cutout.width, candidate.cutout.height) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return CutoutRecoveryResult.UNUSABLE
            }
        } catch (e: WadrovaApiException) {
            return when (e.failure) {
                ApiFailure.UNAUTHENTICATED -> CutoutRecoveryResult.NOT_CONNECTED
                ApiFailure.NOT_FOUND -> CutoutRecoveryResult.NOT_ON_SERVER
                ApiFailure.UNREACHABLE, ApiFailure.UNAVAILABLE -> CutoutRecoveryResult.UNREACHABLE
                else -> CutoutRecoveryResult.NOT_ON_SERVER
            }
        }

        return guard.exclusive {
            var staged: String? = null
            try {
                val stored = try {
                    generated.save(png)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@exclusive CutoutRecoveryResult.STORAGE_FAILED
                }
                staged = stored
                // A cancel before this point links nothing. The link and its bookkeeping can't be interrupted.
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val linked = imports.linkRecoveredCutout(garmentId, photoId, source.cutoutImageId, stored)
                    if (linked) staged = null
                    if (linked) CutoutRecoveryResult.RECOVERED else alreadyLinkedOrChanged(garmentId)
                }
            } finally {
                staged?.let { id -> withContext(NonCancellable) { discardIfUnlinked(id) } }
            }
        }.also { diagnostic("Cutout recovery: ${it.name}") }
    }

    /** After a refused link: another attempt may have linked a cutout first (nothing needed), or the photo changed. */
    private suspend fun alreadyLinkedOrChanged(garmentId: String): CutoutRecoveryResult =
        if (check(garmentId).first == CutoutRecoveryEligibility.ALREADY_HAS_CUTOUT) CutoutRecoveryResult.NOT_NEEDED else CutoutRecoveryResult.CHANGED

    private suspend fun discardIfUnlinked(imageId: String) {
        try {
            if (imageId !in imports.linkedImageIds(listOf(imageId))) generated.delete(imageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Left for the launch sweep.
        }
    }
}
