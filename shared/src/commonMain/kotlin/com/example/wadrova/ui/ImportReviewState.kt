package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.CorrectionRequest
import com.example.wadrova.data.CredentialStore
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentImportRepository
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.InvalidGarmentException
import com.example.wadrova.data.ReviewedPiece
import com.example.wadrova.data.WadrovaApi
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.WadrovaConnection
import com.example.wadrova.domain.Candidate
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.ImportState
import com.example.wadrova.domain.Lineage
import com.example.wadrova.domain.ManifestException
import com.example.wadrova.domain.PendingCorrection
import com.example.wadrova.domain.ReviewChoice
import com.example.wadrova.domain.buildManifest
import com.example.wadrova.domain.groupLineages
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A point the user placed for a points correction, in source-photo pixels. */
data class CorrectionPoint(val x: Int, val y: Int, val positive: Boolean)

data class ReviewUiState(
    val importId: String? = null,
    val photoId: String? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    /** Every lineage of the session, including below-threshold ones (offered only as alternatives). */
    val lineages: List<Lineage> = emptyList(),
    val choices: Map<String, ReviewChoice> = emptyMap(),
    /** Verified cutout bytes by asset id. Images are fetched each time the review opens; the server sends no-store. */
    val images: Map<String, ByteArray> = emptyMap(),
    val failedImages: Set<String> = emptySet(),
    val detailLineageId: String? = null,
    val points: List<CorrectionPoint> = emptyList(),
    val addingPositivePoints: Boolean = true,
    val reprompt: String = "",
    val correcting: Boolean = false,
    val saving: Boolean = false,
    val saveProgress: String? = null,
    val saveError: String? = null,
    val confirmingFinishEmpty: Boolean = false,
    /** Set once the pieces are saved locally; the screen then offers to go to the wardrobe. */
    val savedCount: Int? = null,
) {
    /** The garments to review: lineages whose current revision is a detection. */
    val pieces: List<Lineage> get() = lineages.filter { it.mustBeDecided }

    fun choice(lineage: Lineage): ReviewChoice = choices[lineage.id] ?: ReviewChoice(lineage.id, "")

    fun shown(lineage: Lineage): Candidate = choice(lineage).revisionIn(lineage)

    /** The model's phrase as a starting name. It's a suggestion the user can change, not a recognised garment. */
    fun name(lineage: Lineage): String = choice(lineage).name ?: suggestedName(shown(lineage).phrase)

    /** Only a resolved suggestion pre-fills the category; otherwise the user chooses. */
    fun category(lineage: Lineage): GarmentCategory? = choice(lineage).category ?: shown(lineage).suggestedCategory

    fun color(lineage: Lineage): String? = choice(lineage).color

    fun isComplete(lineage: Lineage): Boolean = name(lineage).isNotBlank() && category(lineage) != null && !color(lineage).isNullOrBlank()

    val selected: List<Lineage> get() = pieces.filter { choice(it).selected }

    val incomplete: List<Lineage> get() = selected.filterNot(::isComplete)

    val detail: Lineage? get() = lineages.find { it.id == detailLineageId }

    /** Below-threshold masks overlapping the piece: the other outlines the model considered for that region. */
    fun alternatives(lineage: Lineage): List<Candidate> {
        val box = shown(lineage).box
        return lineages.filter { !it.mustBeDecided && it.id != lineage.id }
            .map { it.current }
            .filter { it.box.overlap(box) >= 0.3 }
            .sortedByDescending { it.box.overlap(box) }
            .take(6)
    }
}

internal fun suggestedName(phrase: String): String =
    phrase.trim().replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Piece" }

/**
 * Reviews one ready import: shows its real candidates, keeps the user's choices on the device, runs corrections and
 * saves the selected pieces as ordinary garments. Finishing with the server is left to [onSaved], which hands the
 * import to [GarmentImportViewModel]'s durable driver.
 */
@OptIn(ExperimentalUuidApi::class)
class GarmentReviewViewModel(
    private val imports: GarmentImportRepository,
    private val api: WadrovaApi,
    private val credentials: CredentialStore,
    private val photos: GarmentPhotoStore,
    private val onSaved: (importId: String) -> Unit,
    private val newKey: () -> String = { "kmp-cor-${Uuid.random()}" },
) : ViewModel() {
    var state by mutableStateOf(ReviewUiState())
        private set

    private var correctionPoller: Job? = null

    fun open(importId: String) {
        if (state.importId == importId && state.lineages.isNotEmpty() && state.savedCount == null) return
        correctionPoller?.cancel()
        state = ReviewUiState(importId = importId, loading = true)
        viewModelScope.launch { load(importId) }
    }

    fun retryLoad() {
        val id = state.importId ?: return
        state = state.copy(loading = true, loadError = null)
        viewModelScope.launch { load(id) }
    }

    private suspend fun load(importId: String, keepImages: Boolean = false) {
        try {
            val item = imports.get(importId) ?: throw ReviewException("This import is no longer on this device.")
            if (item.state == ImportState.FINALIZE_PENDING || item.state == ImportState.COMPLETED) {
                state = state.copy(loading = false, savedCount = imports.savedGarmentIds(importId).size)
                return
            }
            val sessionId = item.serverSessionId ?: throw ReviewException("This import isn't ready to review.")
            val connection = connection() ?: throw ReviewException("Connect to Wadrova to review these pieces.")
            val lineages = groupLineages(api.candidates(connection, sessionId))
            val choices = imports.reviewChoices(importId, lineages.filter { it.mustBeDecided }.map { it.id })
            if (state.importId != importId) return
            state = state.copy(
                photoId = item.photoId,
                loading = false,
                loadError = null,
                lineages = lineages,
                choices = choices,
                images = if (keepImages) state.images else emptyMap(),
                failedImages = emptySet(),
            )
            loadImages(connection)
            resumeCorrections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReviewException) {
            state = state.copy(loading = false, loadError = e.message)
        } catch (e: WadrovaApiException) {
            state = state.copy(loading = false, loadError = e.message ?: LOAD_FAILED)
        } catch (_: Exception) {
            state = state.copy(loading = false, loadError = LOAD_FAILED)
        }
    }

    /** Fetches every cutout shown, verified against its SHA-256 by the API client. A failure is shown, never faked. */
    private suspend fun loadImages(connection: WadrovaConnection) {
        val wanted = (state.pieces.map { state.shown(it) } + state.pieces.flatMap { state.alternatives(it) } +
            state.pieces.flatMap { it.revisions }).map { it.cutout }.distinctBy { it.id }
        wanted.filter { it.id !in state.images }.forEach { asset ->
            try {
                val bytes = api.assetContent(connection, asset)
                state = state.copy(images = state.images + (asset.id to bytes), failedImages = state.failedImages - asset.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(failedImages = state.failedImages + asset.id)
            }
        }
    }

    fun retryImages() {
        viewModelScope.launch {
            state = state.copy(failedImages = emptySet())
            connection()?.let { loadImages(it) }
        }
    }

    // Choosing and describing pieces

    /** Selecting pins the revision on screen, so a correction finishing later can't swap what the user picked. */
    fun toggle(lineageId: String) {
        val lineage = state.lineages.find { it.id == lineageId } ?: return
        if (state.saving) return
        val choice = state.choice(lineage)
        val selecting = !choice.selected
        update(choice.copy(selected = selecting, chosenCandidateId = if (selecting) state.shown(lineage).id else choice.chosenCandidateId))
    }

    fun openDetail(lineageId: String) {
        state = state.copy(detailLineageId = lineageId, points = emptyList(), reprompt = "", saveError = null)
    }

    fun closeDetail() {
        state = state.copy(detailLineageId = null, points = emptyList(), reprompt = "")
    }

    fun rename(lineageId: String, name: String) = edit(lineageId) { it.copy(name = name) }

    fun categorize(lineageId: String, category: GarmentCategory) = edit(lineageId) { it.copy(category = category) }

    fun color(lineageId: String, color: String) = edit(lineageId) { it.copy(color = color) }

    /** An explicit choice of an earlier (or the newest) revision. */
    fun chooseRevision(lineageId: String, candidateId: String) = edit(lineageId) { it.copy(chosenCandidateId = candidateId) }

    private fun edit(lineageId: String, change: (ReviewChoice) -> ReviewChoice) {
        val lineage = state.lineages.find { it.id == lineageId } ?: return
        if (state.saving) return
        update(change(state.choice(lineage)))
    }

    private fun update(choice: ReviewChoice) {
        val importId = state.importId ?: return
        state = state.copy(choices = state.choices + (choice.lineageId to choice), saveError = null)
        viewModelScope.launch { quietly { imports.saveReviewChoice(importId, choice) } }
    }

    // Corrections

    fun setPointMode(positive: Boolean) {
        state = state.copy(addingPositivePoints = positive)
    }

    fun addPoint(x: Int, y: Int) {
        if (state.points.size >= MAX_POINTS) return
        state = state.copy(points = state.points + CorrectionPoint(x, y, state.addingPositivePoints))
    }

    fun clearPoints() {
        state = state.copy(points = emptyList())
    }

    fun updateReprompt(text: String) {
        state = state.copy(reprompt = text)
    }

    fun selectAlternative(lineageId: String, alternative: Candidate) =
        correct(lineageId, "select_alternative", CorrectionRequest.SelectAlternative(alternative.id))

    /** Needs at least one point that adds to the piece; the backend rejects a correction without one. */
    fun submitPoints(lineageId: String) {
        if (state.points.none { it.positive }) {
            state = state.copy(saveError = "Add at least one point on the piece.")
            return
        }
        correct(lineageId, "points", CorrectionRequest.Points(state.points.map { Triple(it.x, it.y, it.positive) }))
    }

    fun submitReprompt(lineageId: String) {
        val phrase = state.reprompt.trim()
        if (phrase.length < 2 || !phrase.first().isLetter() || phrase.any { !(it.isLetter() || it == ' ' || it == '\'' || it == '-') }) {
            state = state.copy(saveError = "Describe the piece in a few words, for example \"denim jacket\".")
            return
        }
        correct(lineageId, "reprompt", CorrectionRequest.Reprompt(phrase))
    }

    /**
     * Corrections always target the newest revision (the backend refuses others). The key is stored before sending,
     * so a resend after a lost response returns the same correction instead of starting a second one.
     */
    private fun correct(lineageId: String, method: String, request: CorrectionRequest) {
        val lineage = state.lineages.find { it.id == lineageId } ?: return
        val choice = state.choice(lineage)
        if (state.correcting || choice.correction != null || state.saving) return
        val pending = PendingCorrection(newKey(), null, method)
        update(choice.copy(correction = pending, correctionMessage = null))
        state = state.copy(correcting = true, saveError = null)
        viewModelScope.launch { send(lineage, pending, request) }
    }

    private suspend fun send(lineage: Lineage, pending: PendingCorrection, request: CorrectionRequest) {
        try {
            val connection = connection() ?: throw ReviewException("Connect to Wadrova to correct this piece.")
            val created = api.createCorrection(connection, lineage.current.id, pending.key, request)
            val withId = pending.copy(id = created.id)
            update(state.choice(lineage).copy(correction = withId))
            state = state.copy(points = emptyList(), reprompt = "")
            finishCorrection(lineage.id, created.status, created.resultCandidateId)
            if (created.status !in TERMINAL) resumeCorrections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: WadrovaApiException) {
            // 409: a newer revision exists or another correction is running, perhaps one whose response was lost
            // earlier. Reloading shows the piece as it now is, and the user can correct that.
            val changed = e.code == "invalid_state"
            correctionEnded(lineage.id, if (changed) "This piece changed meanwhile, so it has been reloaded." else (e.message ?: CORRECTION_FAILED))
            if (changed) state.importId?.let { load(it, keepImages = true) }
        } catch (e: ReviewException) {
            correctionEnded(lineage.id, e.message)
        } catch (_: Exception) {
            correctionEnded(lineage.id, CORRECTION_FAILED)
        }
    }

    /** Follows corrections in flight, including ones started before a relaunch, until each finishes. */
    private fun resumeCorrections() {
        if (correctionPoller?.isActive == true) return
        correctionPoller = viewModelScope.launch {
            while (true) {
                val running = state.choices.values.filter { it.correction != null }
                if (running.isEmpty()) return@launch
                state = state.copy(correcting = true)
                val connection = connection() ?: return@launch
                running.forEach { choice ->
                    val pending = choice.correction ?: return@forEach
                    val lineage = state.lineages.find { it.id == choice.lineageId } ?: return@forEach
                    try {
                        if (pending.id == null) {
                            // Sent before the app stopped, with no answer recorded. Its body isn't stored, so it can't be
                            // replayed; if it did reach Wadrova, its result shows up as a newer revision on reload.
                            correctionEnded(choice.lineageId, "A correction may not have reached Wadrova. Check the piece, then try again if needed.")
                            return@forEach
                        }
                        val now = api.getCorrection(connection, pending.id)
                        finishCorrection(lineage.id, now.status, now.resultCandidateId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                delay(CORRECTION_POLL_MILLIS)
            }
        }
    }

    /** A finished correction becomes the piece's revision, since the user asked for it; a failed one says so. */
    private suspend fun finishCorrection(lineageId: String, status: String, resultCandidateId: String?) {
        when (status) {
            "succeeded" -> {
                val importId = state.importId ?: return
                load(importId, keepImages = true)
                val lineage = state.lineages.find { it.id == lineageId } ?: return
                val choice = state.choice(lineage)
                update(choice.copy(chosenCandidateId = resultCandidateId ?: lineage.current.id, correction = null, correctionMessage = null))
                state = state.copy(correcting = state.choices.values.any { it.correction != null })
            }
            "failed" -> correctionEnded(lineageId, "Wadrova couldn't correct this piece. The previous outline is kept.")
            "cancelled" -> correctionEnded(lineageId, "This correction was cancelled.")
        }
    }

    private fun correctionEnded(lineageId: String, message: String?) {
        val lineage = state.lineages.find { it.id == lineageId } ?: return
        update(state.choice(lineage).copy(correction = null, correctionMessage = message))
        state = state.copy(correcting = state.choices.values.any { it.correction != null })
    }

    // Saving

    fun requestFinishEmpty() {
        state = state.copy(confirmingFinishEmpty = true)
    }

    fun keepReviewing() {
        state = state.copy(confirmingFinishEmpty = false)
    }

    /**
     * Saves the selected pieces. Images are downloaded, verified and stored first; then one transaction writes the
     * garments, their provenance and the decisions, and the import is handed over for finishing with the server.
     * Photos stored by an attempt that doesn't reach the transaction are deleted. The flag set before suspending
     * blocks a second tap.
     */
    fun save() {
        val importId = state.importId ?: return
        if (state.saving || state.correcting) return
        val incomplete = state.incomplete
        if (incomplete.isNotEmpty()) {
            state = state.copy(
                saveError = if (incomplete.size == 1) "Add a name, category and color to the highlighted piece." else "Add a name, category and color to ${incomplete.size} highlighted pieces.",
                detailLineageId = incomplete.first().id,
            )
            return
        }
        state = state.copy(saving = true, confirmingFinishEmpty = false, saveError = null, saveProgress = "Checking your pieces")
        viewModelScope.launch {
            val staged = mutableListOf<String>()
            try {
                val connection = connection() ?: throw ReviewException("Connect to Wadrova to save these pieces.")
                val item = imports.get(importId) ?: throw ReviewException("This import is no longer on this device.")
                // Fresh candidates, so the decisions cover every lineage the server currently has.
                val lineages = groupLineages(api.candidates(connection, item.serverSessionId ?: throw ReviewException(SAVE_FAILED)))
                val current = state.copy(lineages = lineages)
                val manifest = buildManifest(lineages, current.choices)
                val selected = current.selected
                val pieces = selected.mapIndexed { index, lineage ->
                    state = state.copy(saveProgress = "Saving piece ${index + 1} of ${selected.size}")
                    val candidate = current.shown(lineage)
                    val bytes = state.images[candidate.cutout.id] ?: api.assetContent(connection, candidate.cutout)
                    val photoId = photos.importBytes(bytes).also { staged += it }
                    ReviewedPiece(
                        garmentId = current.choice(lineage).garmentId,
                        details = GarmentDetails(current.name(lineage), current.category(lineage)!!, current.color(lineage)!!),
                        photoId = photoId,
                        lineageId = lineage.id,
                        candidateId = candidate.id,
                        revision = candidate.revision,
                    )
                }
                val unused = imports.saveReviewedPieces(importId, pieces, manifest)
                staged.clear()
                unused.forEach { quietly { photos.delete(it) } }
                state = state.copy(saving = false, saveProgress = null, savedCount = pieces.size - unused.size, detailLineageId = null)
                onSaved(importId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is ReviewException -> e.message
                    is ManifestException -> e.message
                    is InvalidGarmentException -> e.message
                    is WadrovaApiException -> e.message
                    else -> null
                } ?: SAVE_FAILED
                state = state.copy(saving = false, saveProgress = null, saveError = "$message Nothing was saved, so you can try again.")
            } finally {
                staged.forEach { quietly { photos.delete(it) } }
            }
        }
    }

    private suspend fun connection(): WadrovaConnection? = quietly { loadConnection(imports, credentials) }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private class ReviewException(message: String) : Exception(message)

    companion object {
        const val LOAD_FAILED = "Couldn't load your pieces. Check your connection and try again."
        const val SAVE_FAILED = "Couldn't save your pieces."
        const val CORRECTION_FAILED = "Couldn't reach Wadrova to correct this piece."
        const val MAX_POINTS = 20
        const val CORRECTION_POLL_MILLIS = 2_000L
        private val TERMINAL = setOf("succeeded", "failed", "cancelled")
    }
}
