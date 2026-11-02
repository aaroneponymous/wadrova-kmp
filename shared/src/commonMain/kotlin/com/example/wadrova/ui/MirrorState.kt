package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.BackendConfig
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.GarmentRepository
import com.example.wadrova.data.GeneratedImage
import com.example.wadrova.data.GeneratedImageStore
import com.example.wadrova.data.MirrorBackend
import com.example.wadrova.data.MirrorBackendException
import com.example.wadrova.data.MirrorRepository
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.domain.ApprovedModel
import com.example.wadrova.domain.GarmentImageKind
import com.example.wadrova.domain.TryOnResult
import com.example.wadrova.domain.UserGarment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class MirrorRoute { HOME, CREATE_MODEL, TRY_ON, SETTINGS }

/** An operation waiting for explicit permission to send images to OpenAI. */
enum class ConsentRequest { BASE_MODEL, GARMENT_EXTRACTION, TRY_ON }

const val MIN_MODEL_REFERENCES = 3
const val MAX_MODEL_REFERENCES = 5

/** A generated image that hasn't been approved, so nothing in the database refers to it yet. */
data class Candidate(val imageId: String, val generation: GeneratedImage)

data class CreateModelState(
    val referenceIds: List<String> = emptyList(),
    val replacingId: String? = null,
    val addingPhoto: Boolean = false,
    val confirmedFullBody: Boolean = false,
    val generating: Boolean = false,
    val candidate: Candidate? = null,
    val error: String? = null,
) {
    val canAddReference: Boolean
        get() = !addingPhoto && !generating && (replacingId != null || referenceIds.size < MAX_MODEL_REFERENCES)

    val canGenerate: Boolean
        get() = referenceIds.size in MIN_MODEL_REFERENCES..MAX_MODEL_REFERENCES && confirmedFullBody && !generating && !addingPhoto
}

data class ExtractionState(
    val item: WardrobeItem,
    val generating: Boolean = false,
    val candidate: Candidate? = null,
    val error: String? = null,
)

data class TryOnState(
    val item: WardrobeItem? = null,
    val sourceKind: GarmentImageKind = GarmentImageKind.PHOTO,
    val generating: Boolean = false,
    val candidate: Candidate? = null,
    val error: String? = null,
)

data class MirrorUiState(
    val route: MirrorRoute = MirrorRoute.HOME,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val model: ApprovedModel? = null,
    val tryOns: List<TryOnResult> = emptyList(),
    val config: BackendConfig = BackendConfig("", ""),
    val connectionMessage: String? = null,
    val checkingConnection: Boolean = false,
    val create: CreateModelState? = null,
    val extraction: ExtractionState? = null,
    val tryOn: TryOnState? = null,
    val consent: ConsentRequest? = null,
    val confirmingRemoval: Boolean = false,
    val error: String? = null,
)

class MirrorViewModel(
    private val repository: MirrorRepository,
    private val backend: MirrorBackend,
    private val photos: GarmentPhotoStore,
    private val images: GeneratedImageStore,
    private val garments: GarmentRepository,
    private val onGarmentChanged: (UserGarment) -> Unit,
) : ViewModel() {
    val imageLoader = PhotoBitmapLoader(photos, images)

    var state by mutableStateOf(MirrorUiState())
        private set

    private var generation: Job? = null

    /** Tells an import still running that the flow it belonged to has ended. */
    private var createSession = 0

    init {
        load()
        // Only here, before any flow can hold an unapproved result.
        viewModelScope.launch { quietly { repository.removeUnreferencedImages() } }
    }

    fun load() {
        state = state.copy(loading = true, loadFailed = false)
        viewModelScope.launch {
            state = try {
                state.copy(
                    loading = false,
                    model = repository.approvedModel(),
                    tryOns = repository.tryOns(),
                    config = repository.backendConfig(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun openRoute(route: MirrorRoute) {
        state = state.copy(route = route, error = null)
    }

    // Consent

    fun requestConsent(request: ConsentRequest) {
        state = state.copy(consent = request)
    }

    fun declineConsent() {
        state = state.copy(consent = null)
    }

    /** The only way images leave the device. Selecting a photo or opening a screen never sends anything. */
    fun confirmConsent() {
        val request = state.consent ?: return
        state = state.copy(consent = null)
        when (request) {
            ConsentRequest.BASE_MODEL -> generateModel()
            ConsentRequest.GARMENT_EXTRACTION -> extract()
            ConsentRequest.TRY_ON -> generateTryOn()
        }
    }

    // Create Your Model

    fun startCreateModel() {
        createSession++
        state = state.copy(route = MirrorRoute.CREATE_MODEL, create = CreateModelState())
    }

    private fun updateCreate(change: (CreateModelState) -> CreateModelState) {
        state = state.copy(create = state.create?.let(change))
    }

    fun beginReplaceReference(id: String) = updateCreate { it.copy(replacingId = id) }

    fun clearReplaceReference() = updateCreate { it.copy(replacingId = null) }

    fun onReferencePicked(result: PhotoPickResult) {
        when (result) {
            PhotoPickResult.Cancelled -> updateCreate { it.copy(replacingId = null) }
            PhotoPickResult.Failed -> updateCreate { it.copy(replacingId = null, error = PHOTO_FAILED) }
            is PhotoPickResult.Picked -> {
                val replacing = state.create?.replacingId
                if (replacing == null) {
                    onReferencesPicked(PhotoBatch(listOf(result.photo)))
                } else {
                    replaceReference(replacing, result.photo)
                }
            }
        }
    }

    /**
     * Imports one at a time so the references keep the selected order. Every photo goes through the
     * store, even one that's no longer wanted, because that also removes the picker's temporary copy.
     */
    fun onReferencesPicked(batch: PhotoBatch) {
        if (batch.isCancelled || state.create?.addingPhoto != false) return
        val session = createSession
        updateCreate { it.copy(replacingId = null, addingPhoto = true, error = null) }
        viewModelScope.launch {
            var failed = batch.failed
            batch.photos.forEach { photo ->
                try {
                    val imported = photos.import(photo)
                    val create = state.create
                    if (session != createSession || create == null || create.referenceIds.size >= MAX_MODEL_REFERENCES) {
                        quietly { photos.delete(imported) }
                    } else {
                        updateCreate { it.copy(referenceIds = it.referenceIds + imported) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            if (session == createSession) {
                val error = when {
                    failed == 0 -> null
                    batch.photos.size + batch.failed == 1 -> PHOTO_FAILED
                    failed == 1 -> "Couldn't use one of those photos. Try a different one."
                    else -> "Couldn't use $failed of those photos. Try different ones."
                }
                updateCreate { it.copy(addingPhoto = false, error = error) }
            }
        }
    }

    private fun replaceReference(replacing: String, photo: PickedPhoto) {
        val session = createSession
        updateCreate { it.copy(addingPhoto = true, error = null) }
        viewModelScope.launch {
            try {
                val imported = photos.import(photo)
                if (session != createSession || state.create == null) {
                    quietly { photos.delete(imported) }
                    return@launch
                }
                quietly { photos.delete(replacing) }
                updateCreate {
                    val ids = it.referenceIds.map { id -> if (id == replacing) imported else id }
                    it.copy(referenceIds = ids, replacingId = null, addingPhoto = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateCreate { it.copy(replacingId = null, addingPhoto = false, error = PHOTO_FAILED) }
            }
        }
    }

    fun removeReference(id: String) {
        viewModelScope.launch { quietly { photos.delete(id) } }
        updateCreate { it.copy(referenceIds = it.referenceIds - id) }
    }

    /** The first reference is the primary one for body and pose. */
    fun makePrimaryReference(id: String) = updateCreate { it.copy(referenceIds = listOf(id) + (it.referenceIds - id)) }

    fun setConfirmedFullBody(confirmed: Boolean) = updateCreate { it.copy(confirmedFullBody = confirmed) }

    private fun generateModel() {
        val create = state.create ?: return
        if (!create.canGenerate) return
        updateCreate { it.copy(generating = true, error = null) }
        runGeneration(
            onFailure = { message -> updateCreate { it.copy(generating = false, error = message) } },
        ) {
            val references = create.referenceIds.map { id ->
                photos.read(id, PhotoSize.DISPLAY) ?: throw MissingImageException()
            }
            val result = backend.generateBaseModel(state.config, references)
            val imageId = images.save(result.bytes)
            val previous = state.create?.candidate
            updateCreate { it.copy(generating = false, candidate = Candidate(imageId, result)) }
            previous?.let { quietly { images.delete(it.imageId) } }
        }
    }

    /** The result only becomes the model here. Earlier approved versions are untouched. */
    fun approveModel() {
        val create = state.create ?: return
        val candidate = create.candidate ?: return
        viewModelScope.launch {
            try {
                val model = repository.approveModel(candidate.imageId, candidate.generation)
                // Reference photos are personal and no longer needed once the model is approved.
                create.referenceIds.forEach { id -> quietly { photos.delete(id) } }
                state = state.copy(model = model, create = null, route = MirrorRoute.HOME)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateCreate { it.copy(error = SAVE_FAILED) }
            }
        }
    }

    /** Goes back to the reference photos, discarding the unapproved result. */
    fun replaceReferences() {
        val candidate = state.create?.candidate
        updateCreate { it.copy(candidate = null, error = null) }
        candidate?.let { viewModelScope.launch { quietly { images.delete(it.imageId) } } }
    }

    fun cancelCreateModel() {
        createSession++
        generation?.cancel()
        val create = state.create
        state = state.copy(create = null, route = MirrorRoute.HOME, consent = null)
        if (create != null) {
            viewModelScope.launch {
                create.referenceIds.forEach { id -> quietly { photos.delete(id) } }
                create.candidate?.let { quietly { images.delete(it.imageId) } }
            }
        }
    }

    fun cancelGeneration() {
        generation?.cancel()
        state = state.copy(
            create = state.create?.copy(generating = false),
            extraction = state.extraction?.copy(generating = false),
            tryOn = state.tryOn?.copy(generating = false),
        )
    }

    fun requestRemoveModel() {
        state = state.copy(confirmingRemoval = true)
    }

    fun cancelRemoveModel() {
        state = state.copy(confirmingRemoval = false)
    }

    fun removeModelAndPreviews() {
        state = state.copy(confirmingRemoval = false)
        viewModelScope.launch {
            state = try {
                repository.removeModelAndPreviews()
                state.copy(model = null, tryOns = emptyList(), error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.copy(error = SAVE_FAILED)
            }
        }
    }

    // Garment extraction

    fun startExtraction(item: WardrobeItem) {
        if (item.photoId == null) return
        state = state.copy(extraction = ExtractionState(item), consent = ConsentRequest.GARMENT_EXTRACTION)
    }

    private fun extract() {
        val extraction = state.extraction ?: return
        val photoId = extraction.item.photoId ?: return
        state = state.copy(extraction = extraction.copy(generating = true, error = null))
        runGeneration(
            onFailure = { message -> state = state.copy(extraction = state.extraction?.copy(generating = false, error = message)) },
        ) {
            val photo = photos.read(photoId, PhotoSize.DISPLAY) ?: throw MissingImageException()
            val result = backend.extractGarment(state.config, photo)
            val imageId = images.save(result.bytes)
            val previous = state.extraction?.candidate
            state = state.copy(extraction = state.extraction?.copy(generating = false, candidate = Candidate(imageId, result)))
            previous?.let { quietly { images.delete(it.imageId) } }
        }
    }

    fun useExtractedImage() {
        val extraction = state.extraction ?: return
        val candidate = extraction.candidate ?: return
        viewModelScope.launch {
            try {
                val updated = garments.setExtractedImage(extraction.item.garment.id, candidate.imageId)
                onGarmentChanged(updated)
                state = state.copy(extraction = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(extraction = state.extraction?.copy(error = SAVE_FAILED))
            }
        }
    }

    /** Leaves the garment exactly as it was. */
    fun keepOriginal() {
        generation?.cancel()
        val candidate = state.extraction?.candidate
        state = state.copy(extraction = null, consent = null)
        candidate?.let { viewModelScope.launch { quietly { images.delete(it.imageId) } } }
    }

    fun clearExtractedImage(item: WardrobeItem) {
        viewModelScope.launch {
            try {
                onGarmentChanged(garments.setExtractedImage(item.garment.id, null))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(error = SAVE_FAILED)
            }
        }
    }

    // Try-on

    fun startTryOn() {
        state = state.copy(route = MirrorRoute.TRY_ON, tryOn = TryOnState())
    }

    fun selectTryOnGarment(item: WardrobeItem) {
        state = state.copy(
            tryOn = TryOnState(
                item = item,
                sourceKind = if (item.extractedImageId != null) GarmentImageKind.EXTRACTED else GarmentImageKind.PHOTO,
            ),
        )
    }

    fun setTryOnSource(kind: GarmentImageKind) {
        state = state.copy(tryOn = state.tryOn?.copy(sourceKind = kind))
    }

    private fun generateTryOn() {
        val tryOn = state.tryOn ?: return
        val item = tryOn.item ?: return
        val model = state.model ?: return
        state = state.copy(tryOn = tryOn.copy(generating = true, error = null))
        runGeneration(
            onFailure = { message -> state = state.copy(tryOn = state.tryOn?.copy(generating = false, error = message)) },
        ) {
            val garment = when (tryOn.sourceKind) {
                GarmentImageKind.EXTRACTED -> item.extractedImageId?.let { images.read(it) }
                GarmentImageKind.PHOTO -> item.photoId?.let { photos.read(it, PhotoSize.DISPLAY) }
            } ?: throw MissingImageException()
            // Always the approved model, so errors can't build up across generated results.
            val base = images.read(model.imageId) ?: throw MissingImageException()
            val result = backend.tryOn(state.config, base, garment)
            val imageId = images.save(result.bytes)
            val previous = state.tryOn?.candidate
            state = state.copy(tryOn = state.tryOn?.copy(generating = false, candidate = Candidate(imageId, result)))
            previous?.let { quietly { images.delete(it.imageId) } }
        }
    }

    fun acceptTryOn() {
        val tryOn = state.tryOn ?: return
        val item = tryOn.item ?: return
        val candidate = tryOn.candidate ?: return
        val model = state.model ?: return
        val sourceId = when (tryOn.sourceKind) {
            GarmentImageKind.EXTRACTED -> item.extractedImageId
            GarmentImageKind.PHOTO -> item.photoId
        } ?: return
        viewModelScope.launch {
            try {
                val saved = repository.saveTryOn(model, item.garment.id, sourceId, tryOn.sourceKind, candidate.generation, candidate.imageId)
                state = state.copy(tryOns = listOf(saved) + state.tryOns, tryOn = null, route = MirrorRoute.HOME)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(tryOn = state.tryOn?.copy(error = SAVE_FAILED))
            }
        }
    }

    fun discardTryOn() {
        generation?.cancel()
        val candidate = state.tryOn?.candidate
        state = state.copy(tryOn = null, route = MirrorRoute.HOME, consent = null)
        candidate?.let { viewModelScope.launch { quietly { images.delete(it.imageId) } } }
    }

    fun deleteTryOn(id: String) {
        viewModelScope.launch {
            state = try {
                repository.deleteTryOn(id)
                state.copy(tryOns = state.tryOns.filterNot { it.id == id })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.copy(error = SAVE_FAILED)
            }
        }
    }

    // Settings

    fun updateConfig(config: BackendConfig) {
        state = state.copy(config = config, connectionMessage = null)
    }

    /** Saves the settings and checks the backend's configuration. This never contacts OpenAI. */
    fun saveConfigAndCheck() {
        // A pasted address or token often carries stray whitespace, which isn't valid in a header.
        val config = state.config.let { BackendConfig(it.url.trim(), it.token.trim()) }
        state = state.copy(config = config, checkingConnection = true, connectionMessage = null)
        viewModelScope.launch {
            val message = try {
                repository.saveBackendConfig(config)
                val status = backend.status(config)
                when {
                    !status.apiKeyConfigured -> "Connected, but the backend has no OpenAI key."
                    !status.liveInferenceEnabled -> "Connected, but live generation is off on the backend."
                    else -> "Connected and ready. Generation uses ${status.model}."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MirrorBackendException) {
                e.message
            } catch (_: Exception) {
                SAVE_FAILED
            }
            state = state.copy(checkingConnection = false, connectionMessage = message)
        }
    }

    private fun runGeneration(onFailure: (String) -> Unit, work: suspend () -> Unit) {
        generation?.cancel()
        generation = viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MirrorBackendException) {
                onFailure(e.message ?: GENERATION_FAILED)
            } catch (_: MissingImageException) {
                onFailure(IMAGE_MISSING)
            } catch (_: Exception) {
                onFailure(GENERATION_FAILED)
            }
        }
    }

    private class MissingImageException : Exception()

    private suspend fun quietly(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val PHOTO_FAILED = "Couldn't use that photo. Try a different one."
        const val SAVE_FAILED = "Couldn't save that change. Please try again."
        const val IMAGE_MISSING = "An image needed for this is no longer on this device."
        const val GENERATION_FAILED = "Something went wrong. Nothing was changed, so you can try again."
    }
}
