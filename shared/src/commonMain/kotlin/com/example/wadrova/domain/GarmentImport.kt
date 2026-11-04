package com.example.wadrova.domain

/**
 * Where a photo is on its way to garment candidates. The server owns processing and results; the app owns this
 * record, which is enough to resend, follow or cancel the import after the app is closed.
 */
enum class ImportState {
    /** Saved on the device and waiting to upload: new, after a failed attempt, or after the app stopped mid-upload. */
    UPLOAD_PENDING,
    UPLOADING,

    /** The server has the photo and it's queued or being processed. */
    PROCESSING,
    READY,

    /** The server couldn't process the photo. */
    FAILED,

    /** The upload was refused for good, or the saved photo is gone, so resending can't help. */
    REJECTED,

    /** The user cancelled. Kept until the server confirms, then the record is removed. */
    CANCEL_PENDING,

    /** Cancelled or finalized somewhere other than this app, or no longer on the server. */
    CLOSED,
}

/** Why an import isn't moving right now. Cleared by the next successful step. */
enum class ImportProblem { NOT_CONNECTED, ACCESS_REJECTED, OFFLINE, SERVER_UNAVAILABLE }

/** What the server reports about one import. Only the fields the app uses. */
data class ServerImport(
    val id: String,
    val clientRef: String?,
    val status: String,
    val sessionId: String,
    val candidateCount: Int?,
    val failureMessage: String?,
    val pollAfterSeconds: Int?,
)

data class GarmentImport(
    val id: String,
    val photoId: String,
    val sourceSha256: String,
    val sourceByteSize: Long,
    val state: ImportState,
    /**
     * Upload attempts the server may have accepted: every attempt counts until a definite refusal (401 or another
     * 4xx, which stores nothing) takes it back. A lost response, a timeout, a 5xx or a killed process leave it counted.
     */
    val uploadAttempts: Int = 0,
    val problem: ImportProblem? = null,
    /** User-facing text for [ImportState.FAILED], [ImportState.REJECTED] and [ImportState.CLOSED]. */
    val problemMessage: String? = null,
    val serverImportId: String? = null,
    val serverSessionId: String? = null,
    val serverStatus: String? = null,
    val candidateCount: Int? = null,
    val pollAfterSeconds: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * Whether the server might already hold this import. A request that was sent may have been committed even if its
     * response was lost, so this is true from the first attempt on.
     */
    val mayExistOnServer: Boolean get() = serverImportId != null || uploadAttempts > 0

    fun uploadStarted(now: Long): GarmentImport =
        copy(state = ImportState.UPLOADING, uploadAttempts = uploadAttempts + 1, problem = null, updatedAt = now)

    /** A transient failure: the same bytes go again later with the same key. A requested cancel stays requested. */
    fun uploadInterrupted(problem: ImportProblem, now: Long): GarmentImport =
        copy(state = if (state == ImportState.CANCEL_PENDING) state else ImportState.UPLOAD_PENDING, problem = problem, updatedAt = now)

    /** The server definitely refused the last attempt, so that attempt can't have created anything. */
    fun uploadRefused(): GarmentImport = copy(uploadAttempts = (uploadAttempts - 1).coerceAtLeast(0))

    fun withProblem(problem: ImportProblem, now: Long): GarmentImport = copy(problem = problem, updatedAt = now)

    fun rejected(message: String, now: Long): GarmentImport =
        if (state == ImportState.CANCEL_PENDING) this else copy(state = ImportState.REJECTED, problem = null, problemMessage = message, updatedAt = now)

    fun closed(message: String, now: Long): GarmentImport =
        copy(state = ImportState.CLOSED, problem = null, problemMessage = message, updatedAt = now)

    fun cancelRequested(now: Long): GarmentImport = copy(state = ImportState.CANCEL_PENDING, problem = null, updatedAt = now)

    /** Applies the server's view. A cancel the user asked for is kept, so it's still sent. */
    fun withServer(remote: ServerImport, now: Long): GarmentImport {
        val next = when (remote.status) {
            "ready" -> ImportState.READY
            "failed" -> ImportState.FAILED
            "cancelled", "finalized" -> ImportState.CLOSED
            // queued, processing, and any status this version doesn't know, which the API says means "not ready".
            else -> ImportState.PROCESSING
        }
        val message = when (next) {
            ImportState.FAILED -> remote.failureMessage ?: PROCESSING_FAILED
            ImportState.CLOSED -> if (remote.status == "finalized") FINALIZED_ELSEWHERE else CANCELLED_ELSEWHERE
            else -> null
        }
        return copy(
            state = if (state == ImportState.CANCEL_PENDING) state else next,
            problem = null,
            problemMessage = if (state == ImportState.CANCEL_PENDING) problemMessage else message,
            serverImportId = remote.id,
            serverSessionId = remote.sessionId,
            serverStatus = remote.status,
            candidateCount = remote.candidateCount,
            pollAfterSeconds = remote.pollAfterSeconds,
            updatedAt = now,
        )
    }

    companion object {
        const val PROCESSING_FAILED = "Something went wrong while finding the pieces in this photo."
        const val CANCELLED_ELSEWHERE = "This import was cancelled outside this app."
        const val FINALIZED_ELSEWHERE = "This import was already completed outside this app."
        const val GONE_FROM_SERVER = "This import is no longer on the Wadrova server."
    }
}
