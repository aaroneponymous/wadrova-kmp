package com.example.wadrova.ui

import com.example.wadrova.data.BackendConfig
import com.example.wadrova.data.BackendFailure
import com.example.wadrova.data.BackendStatus
import com.example.wadrova.data.FakeGeneratedImageStore
import com.example.wadrova.data.FakePhotoStore
import com.example.wadrova.data.GarmentDetails
import com.example.wadrova.data.GarmentRepository
import com.example.wadrova.data.GeneratedImage
import com.example.wadrova.data.MirrorBackend
import com.example.wadrova.data.MirrorBackendException
import com.example.wadrova.data.MirrorRepository
import com.example.wadrova.data.PhotoChange
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.domain.ApprovedModel
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.GarmentImageKind
import com.example.wadrova.domain.TryOnResult
import com.example.wadrova.domain.UserGarment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorViewModelTest {
    private class FakeBackend : MirrorBackend {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var references: List<ByteArray> = emptyList()
        var tryOnInputs: Pair<ByteArray, ByteArray>? = null
        private val result get() = GeneratedImage(byteArrayOf(calls.size.toByte()), "model-x", "high")

        private suspend fun call(name: String): GeneratedImage {
            calls += name
            gate?.await()
            failure?.let { throw it }
            return result
        }

        override suspend fun status(config: BackendConfig): BackendStatus {
            calls += "status"
            failure?.let { throw it }
            return BackendStatus(apiKeyConfigured = true, liveInferenceEnabled = false, model = "model-x")
        }

        override suspend fun generateBaseModel(config: BackendConfig, references: List<ByteArray>): GeneratedImage {
            this.references = references
            return call("base")
        }

        override suspend fun extractGarment(config: BackendConfig, photo: ByteArray) = call("extract")

        override suspend fun tryOn(config: BackendConfig, model: ByteArray, garment: ByteArray): GeneratedImage {
            tryOnInputs = model to garment
            return call("tryOn")
        }
    }

    private class FakeMirrorRepository : MirrorRepository {
        var model: ApprovedModel? = null
        val results = mutableListOf<TryOnResult>()
        var failure: Exception? = null
        var saved: BackendConfig? = null

        override suspend fun approvedModel() = model

        override suspend fun approveModel(imageId: String, generation: GeneratedImage): ApprovedModel {
            failure?.let { throw it }
            return ApprovedModel("model", (model?.version ?: 0) + 1, imageId, generation.model, generation.quality).also { model = it }
        }

        override suspend fun removeModelAndPreviews() {
            model = null
            results.clear()
        }

        override suspend fun tryOns() = results.toList()

        override suspend fun saveTryOn(
            model: ApprovedModel,
            garmentId: String,
            sourceImageId: String,
            sourceImageKind: GarmentImageKind,
            generation: GeneratedImage,
            resultImageId: String,
        ): TryOnResult {
            failure?.let { throw it }
            return TryOnResult("t${results.size}", model.modelId, model.version, garmentId, sourceImageId, sourceImageKind, generation.model, generation.quality, resultImageId)
                .also { results += it }
        }

        override suspend fun deleteTryOn(id: String) {
            results.removeAll { it.id == id }
        }

        override suspend fun backendConfig() = BackendConfig("http://backend", "token")

        override suspend fun saveBackendConfig(config: BackendConfig) {
            saved = config
        }

        override suspend fun removeUnreferencedImages() = Unit
    }

    private class FakeGarments : GarmentRepository {
        var extracted: Pair<String, String?>? = null
        var failure: Exception? = null

        override suspend fun getAll() = emptyList<UserGarment>()

        override suspend fun create(details: GarmentDetails, photoId: String?) = error("unused")

        override suspend fun update(id: String, details: GarmentDetails, photo: PhotoChange) = error("unused")

        override suspend fun setArchived(id: String, archived: Boolean) = error("unused")

        override suspend fun setExtractedImage(id: String, imageId: String?): UserGarment {
            failure?.let { throw it }
            extracted = id to imageId
            return UserGarment(Garment(id, GarmentCategory.TOP), "Shirt", "White", null, null, false, "photo", imageId)
        }

        override suspend fun removeUnreferencedPhotos() = Unit
    }

    private val backend = FakeBackend()
    private val repository = FakeMirrorRepository()
    private val photos = FakePhotoStore(mutableSetOf("photo"))
    private val images = FakeGeneratedImageStore()
    private val garments = FakeGarments()
    private val changed = mutableListOf<UserGarment>()
    private val picked = PhotoPickResult.Picked(PickedPhoto("picked"))
    private val item = WardrobeItem(
        Garment("garment", GarmentCategory.TOP),
        "Shirt",
        "White",
        GarmentIllustrationType.TEE,
        androidx.compose.ui.graphics.Color.White,
        photoId = "photo",
    )

    private fun viewModel() = MirrorViewModel(repository, backend, photos, images, garments) { changed += it }

    private fun readyToGenerate(): MirrorViewModel = viewModel().apply {
        startCreateModel()
        repeat(3) { onReferencePicked(picked) }
        setConfirmedFullBody(true)
    }

    private fun MirrorViewModel.generateCandidate() {
        requestConsent(ConsentRequest.BASE_MODEL)
        confirmConsent()
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // Consent

    @Test
    fun nothingIsSentWithoutConsent() {
        val viewModel = readyToGenerate()
        viewModel.requestConsent(ConsentRequest.BASE_MODEL)
        assertEquals(ConsentRequest.BASE_MODEL, viewModel.state.consent)
        assertTrue(backend.calls.isEmpty())

        viewModel.declineConsent()
        assertNull(viewModel.state.consent)
        assertTrue(backend.calls.isEmpty())
        assertNull(viewModel.state.create?.candidate)
    }

    @Test
    fun startingExtractionAsksForConsentAndSendsNothing() {
        val viewModel = viewModel()
        viewModel.startExtraction(item)
        assertEquals(ConsentRequest.GARMENT_EXTRACTION, viewModel.state.consent)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun regeneratingAsksForConsentAgain() {
        val viewModel = readyToGenerate()
        viewModel.generateCandidate()
        viewModel.requestConsent(ConsentRequest.BASE_MODEL)
        assertEquals(1, backend.calls.size)
        viewModel.confirmConsent()
        assertEquals(2, backend.calls.size)
    }

    // Create Your Model

    @Test
    fun generatingRequiresThreeToFiveReferencesAndConfirmation() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        repeat(2) { viewModel.onReferencePicked(picked) }
        viewModel.setConfirmedFullBody(true)
        assertFalse(viewModel.state.create!!.canGenerate)
        viewModel.requestConsent(ConsentRequest.BASE_MODEL)
        viewModel.confirmConsent()
        assertTrue(backend.calls.isEmpty())

        viewModel.onReferencePicked(picked)
        assertTrue(viewModel.state.create!!.canGenerate)
        viewModel.setConfirmedFullBody(false)
        assertFalse(viewModel.state.create!!.canGenerate)
    }

    @Test
    fun atMostFiveReferencesAreAccepted() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        repeat(7) { viewModel.onReferencePicked(picked) }
        assertEquals(MAX_MODEL_REFERENCES, viewModel.state.create?.referenceIds?.size)
    }

    @Test
    fun removingAndReplacingReferencesDeletesTheOldFiles() {
        val viewModel = readyToGenerate()
        val (first, second) = viewModel.state.create!!.referenceIds
        viewModel.removeReference(first)
        assertTrue(first in photos.deleted)
        viewModel.beginReplaceReference(second)
        viewModel.onReferencePicked(picked)
        assertTrue(second in photos.deleted)
        assertFalse(second in viewModel.state.create!!.referenceIds)
        assertEquals(2, viewModel.state.create!!.referenceIds.size)
    }

    @Test
    fun cancelledPickDoesNotReplace() {
        val viewModel = readyToGenerate()
        val before = viewModel.state.create!!.referenceIds
        viewModel.beginReplaceReference(before.first())
        viewModel.onReferencePicked(PhotoPickResult.Cancelled)
        assertEquals(before, viewModel.state.create!!.referenceIds)
        assertNull(viewModel.state.create!!.replacingId)
    }

    private fun batch(vararg locations: String, failed: Int = 0) = PhotoBatch(locations.map(::PickedPhoto), failed)

    private fun MirrorViewModel.selectedLocations() = state.create!!.referenceIds.map { photos.importedFrom[it] }

    @Test
    fun aBatchKeepsTheSelectedOrderAndTheFirstIsTheMainReference() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.onReferencesPicked(batch("e", "a", "d", "b", "c"))
        assertEquals(listOf("e", "a", "d", "b", "c"), viewModel.selectedLocations())
    }

    @Test
    fun aLaterBatchAppendsToExistingReferences() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.onReferencesPicked(batch("a", "b", "c"))
        viewModel.onReferencesPicked(batch("d", "e"))
        assertEquals(listOf("a", "b", "c", "d", "e"), viewModel.selectedLocations())
    }

    @Test
    fun extraPhotosBeyondFiveAreDiscarded() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.onReferencesPicked(batch("a", "b", "c", "d"))
        viewModel.onReferencesPicked(batch("e", "f"))
        assertEquals(listOf("a", "b", "c", "d", "e"), viewModel.selectedLocations())
        assertEquals(1, photos.deleted.size)
        assertEquals(setOf("f"), photos.deleted.map { photos.importedFrom[it] }.toSet())
    }

    @Test
    fun replacingKeepsTheSlotWhileAppendingAddsToTheEnd() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.onReferencesPicked(batch("a", "b", "c"))
        viewModel.beginReplaceReference(viewModel.state.create!!.referenceIds[1])
        viewModel.onReferencePicked(PhotoPickResult.Picked(PickedPhoto("new")))
        assertEquals(listOf("a", "new", "c"), viewModel.selectedLocations())
        viewModel.onReferencesPicked(batch("d"))
        assertEquals(listOf("a", "new", "c", "d"), viewModel.selectedLocations())
    }

    @Test
    fun aCancelledBatchChangesNothing() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.onReferencesPicked(batch("a", "b"))
        val before = viewModel.state.create
        viewModel.onReferencesPicked(batch())
        assertEquals(before, viewModel.state.create)
    }

    @Test
    fun aPartialFailureKeepsTheRestInOrderThenRecovers() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        photos.failingLocations += "b"
        viewModel.onReferencesPicked(batch("a", "b", "c", failed = 1))
        assertEquals(listOf("a", "c"), viewModel.selectedLocations())
        assertEquals("Couldn't use 2 of those photos. Try different ones.", viewModel.state.create!!.error)
        assertFalse(viewModel.state.create!!.addingPhoto)

        viewModel.onReferencesPicked(batch("d"))
        assertEquals(listOf("a", "c", "d"), viewModel.selectedLocations())
        assertNull(viewModel.state.create!!.error)
    }

    @Test
    fun createModelStaysDisabledUntilEveryImportFinishes() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        viewModel.setConfirmedFullBody(true)
        val gate = CompletableDeferred<Unit>()
        photos.importGate = gate
        viewModel.onReferencesPicked(batch("a", "b", "c"))
        assertTrue(viewModel.state.create!!.addingPhoto)
        assertFalse(viewModel.state.create!!.canGenerate)
        assertFalse(viewModel.state.create!!.canAddReference)

        gate.complete(Unit)
        assertFalse(viewModel.state.create!!.addingPhoto)
        assertTrue(viewModel.state.create!!.canGenerate)
    }

    @Test
    fun aBatchFinishingAfterCancelDoesNotJoinALaterFlow() {
        val viewModel = viewModel()
        viewModel.startCreateModel()
        val gate = CompletableDeferred<Unit>()
        photos.importGate = gate
        viewModel.onReferencesPicked(batch("a", "b"))
        viewModel.cancelCreateModel()
        viewModel.startCreateModel()
        gate.complete(Unit)
        assertTrue(viewModel.state.create!!.referenceIds.isEmpty())
        assertFalse(viewModel.state.create!!.addingPhoto)
        assertEquals(2, photos.deleted.size)
    }

    @Test
    fun generationRunningPreventsASecondSubmission() {
        val viewModel = readyToGenerate()
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        viewModel.generateCandidate()
        assertTrue(viewModel.state.create!!.generating)
        assertFalse(viewModel.state.create!!.canGenerate)
        viewModel.generateCandidate()
        assertEquals(1, backend.calls.size)
        gate.complete(Unit)
    }

    @Test
    fun buttonColorsAreExplicitInEveryState() {
        assertEquals(WadrovaColors.Charcoal to WadrovaColors.Warm, actionButtonColors(dark = true, enabled = true))
        assertEquals(WadrovaColors.Studio to WadrovaColors.Ink2, actionButtonColors(dark = true, enabled = false))
        assertEquals(WadrovaColors.Studio to WadrovaColors.Ink2, actionButtonColors(dark = false, enabled = false))
        assertEquals(WadrovaColors.CobaltSoft to WadrovaColors.Cobalt, actionButtonColors(dark = false, enabled = true))
    }

    @Test
    fun theChosenMainReferenceIsSentFirst() {
        val viewModel = readyToGenerate()
        val ids = viewModel.state.create!!.referenceIds
        viewModel.makePrimaryReference(ids.last())
        assertEquals(listOf(ids.last(), ids[0], ids[1]), viewModel.state.create!!.referenceIds)
    }

    @Test
    fun aGeneratedModelIsNotApprovedUntilTheUserApprovesIt() {
        val viewModel = readyToGenerate()
        viewModel.generateCandidate()
        val candidate = assertNotNull(viewModel.state.create?.candidate)
        assertNull(repository.model)
        assertNull(viewModel.state.model)

        viewModel.approveModel()
        assertEquals(candidate.imageId, viewModel.state.model?.imageId)
        assertEquals(MirrorRoute.HOME, viewModel.state.route)
        assertNull(viewModel.state.create)
    }

    @Test
    fun approvingDeletesReferencePhotosAndKeepsTheModel() {
        val viewModel = readyToGenerate()
        val references = viewModel.state.create!!.referenceIds
        viewModel.generateCandidate()
        val candidate = viewModel.state.create!!.candidate!!
        viewModel.approveModel()
        assertTrue(photos.deleted.containsAll(references))
        assertTrue(candidate.imageId in images.stored)
    }

    @Test
    fun regeneratingNeverTouchesAnApprovedModel() {
        val first = readyToGenerate()
        first.generateCandidate()
        first.approveModel()
        val approved = first.state.model!!

        first.startCreateModel()
        repeat(3) { first.onReferencePicked(picked) }
        first.setConfirmedFullBody(true)
        first.generateCandidate()
        val candidate = first.state.create!!.candidate!!
        first.generateCandidate()
        assertTrue(candidate.imageId in images.deleted)
        assertFalse(approved.imageId in images.deleted)
        assertEquals(approved, first.state.model)

        first.cancelCreateModel()
        assertEquals(approved, first.state.model)
        assertTrue(approved.imageId in images.stored)
    }

    @Test
    fun cancellingCleansUpEverythingFromTheFlow() {
        val viewModel = readyToGenerate()
        val references = viewModel.state.create!!.referenceIds
        viewModel.generateCandidate()
        val candidate = viewModel.state.create!!.candidate!!
        viewModel.cancelCreateModel()
        assertNull(viewModel.state.create)
        assertNull(repository.model)
        assertTrue(photos.deleted.containsAll(references))
        assertTrue(candidate.imageId in images.deleted)
    }

    @Test
    fun replaceReferencesDiscardsTheResultButKeepsThePhotos() {
        val viewModel = readyToGenerate()
        val references = viewModel.state.create!!.referenceIds
        viewModel.generateCandidate()
        val candidate = viewModel.state.create!!.candidate!!
        viewModel.replaceReferences()
        assertNull(viewModel.state.create?.candidate)
        assertEquals(references, viewModel.state.create?.referenceIds)
        assertTrue(candidate.imageId in images.deleted)
    }

    @Test
    fun failedGenerationShowsTheBackendsMessageAndNoResult() {
        val viewModel = readyToGenerate()
        backend.failure = MirrorBackendException(BackendFailure.NOT_CONFIGURED, "Set OPENAI_API_KEY on the backend.")
        viewModel.generateCandidate()
        val create = viewModel.state.create!!
        assertEquals("Set OPENAI_API_KEY on the backend.", create.error)
        assertFalse(create.generating)
        assertNull(create.candidate)
        assertEquals(3, create.referenceIds.size)
        assertTrue(images.stored.isEmpty())
    }

    @Test
    fun cancellingGenerationStoresNothing() {
        val viewModel = readyToGenerate()
        backend.gate = CompletableDeferred()
        viewModel.generateCandidate()
        assertTrue(viewModel.state.create!!.generating)
        viewModel.cancelGeneration()
        assertFalse(viewModel.state.create!!.generating)
        backend.gate!!.complete(Unit)
        assertNull(viewModel.state.create!!.candidate)
        assertTrue(images.stored.isEmpty())
    }

    // Extraction

    private fun extractedViewModel() = viewModel().apply {
        startExtraction(item)
        confirmConsent()
    }

    @Test
    fun extractionIsReviewedBeforeTheGarmentChanges() {
        val viewModel = extractedViewModel()
        assertNotNull(viewModel.state.extraction?.candidate)
        assertNull(garments.extracted)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun usingTheExtractionStoresItAgainstTheSameGarment() {
        val viewModel = extractedViewModel()
        val candidate = viewModel.state.extraction!!.candidate!!
        viewModel.useExtractedImage()
        assertEquals("garment" to candidate.imageId, garments.extracted)
        assertEquals(candidate.imageId, changed.single().extractedImageId)
        assertEquals("photo", changed.single().photoId)
        assertNull(viewModel.state.extraction)
        assertTrue("photo" in photos.stored)
    }

    @Test
    fun keepingTheOriginalChangesNothingAndDeletesTheResult() {
        val viewModel = extractedViewModel()
        val candidate = viewModel.state.extraction!!.candidate!!
        viewModel.keepOriginal()
        assertNull(garments.extracted)
        assertTrue(changed.isEmpty())
        assertTrue(candidate.imageId in images.deleted)
        assertTrue("photo" in photos.stored)
    }

    @Test
    fun failedExtractionKeepsTheGarmentUntouched() {
        val viewModel = viewModel()
        backend.failure = MirrorBackendException(BackendFailure.REJECTED, "OpenAI rejected this request.")
        viewModel.startExtraction(item)
        viewModel.confirmConsent()
        assertEquals("OpenAI rejected this request.", viewModel.state.extraction?.error)
        assertNull(garments.extracted)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun extractionNeedsAPhoto() {
        val viewModel = viewModel()
        viewModel.startExtraction(item.copy(photoId = null))
        assertNull(viewModel.state.extraction)
        assertNull(viewModel.state.consent)
    }

    // Try-on

    private fun approvedViewModel(): MirrorViewModel {
        images.put("approved", byteArrayOf(42))
        repository.model = ApprovedModel("model", 3, "approved", "model-x", "high")
        return viewModel()
    }

    @Test
    fun tryOnAlwaysUsesTheApprovedModelNeverAnEarlierResult() {
        val viewModel = approvedViewModel()
        viewModel.startTryOn()
        viewModel.selectTryOnGarment(item)
        repeat(2) {
            viewModel.requestConsent(ConsentRequest.TRY_ON)
            viewModel.confirmConsent()
            assertContentEquals(byteArrayOf(42), backend.tryOnInputs?.first)
        }
        assertEquals(2, backend.calls.size)
    }

    @Test
    fun tryOnSendsTheChosenGarmentImage() {
        images.put("extracted", byteArrayOf(7))
        val viewModel = approvedViewModel()
        viewModel.startTryOn()
        viewModel.selectTryOnGarment(item.copy(extractedImageId = "extracted"))
        assertEquals(GarmentImageKind.EXTRACTED, viewModel.state.tryOn?.sourceKind)
        viewModel.requestConsent(ConsentRequest.TRY_ON)
        viewModel.confirmConsent()
        assertContentEquals(byteArrayOf(7), backend.tryOnInputs?.second)
    }

    @Test
    fun acceptingSavesFullProvenance() {
        val viewModel = approvedViewModel()
        viewModel.startTryOn()
        viewModel.selectTryOnGarment(item)
        viewModel.requestConsent(ConsentRequest.TRY_ON)
        viewModel.confirmConsent()
        val candidate = viewModel.state.tryOn!!.candidate!!
        assertTrue(repository.results.isEmpty())

        viewModel.acceptTryOn()
        val saved = repository.results.single()
        assertEquals(3L, saved.modelVersion)
        assertEquals("garment", saved.garmentId)
        assertEquals("photo", saved.sourceImageId)
        assertEquals(GarmentImageKind.PHOTO, saved.sourceImageKind)
        assertEquals("model-x", saved.generationModel)
        assertEquals(candidate.imageId, saved.resultImageId)
        assertEquals(listOf(saved), viewModel.state.tryOns)
    }

    @Test
    fun discardingDeletesTheResultAndSavesNothing() {
        val viewModel = approvedViewModel()
        viewModel.startTryOn()
        viewModel.selectTryOnGarment(item)
        viewModel.requestConsent(ConsentRequest.TRY_ON)
        viewModel.confirmConsent()
        val candidate = viewModel.state.tryOn!!.candidate!!
        viewModel.discardTryOn()
        assertTrue(candidate.imageId in images.deleted)
        assertTrue(repository.results.isEmpty())
        assertFalse("approved" in images.deleted)
    }

    @Test
    fun missingModelFileFailsWithoutSending() {
        repository.model = ApprovedModel("model", 1, "gone", "model-x", "high")
        val viewModel = viewModel()
        viewModel.startTryOn()
        viewModel.selectTryOnGarment(item)
        viewModel.requestConsent(ConsentRequest.TRY_ON)
        viewModel.confirmConsent()
        assertNotNull(viewModel.state.tryOn?.error)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun removingTheModelClearsPreviews() {
        val viewModel = approvedViewModel()
        viewModel.requestRemoveModel()
        assertTrue(viewModel.state.confirmingRemoval)
        assertNotNull(repository.model)
        viewModel.removeModelAndPreviews()
        assertNull(viewModel.state.model)
        assertNull(repository.model)
    }

    // Settings

    @Test
    fun checkingTheConnectionSavesSettingsAndReportsDisabledGeneration() {
        val viewModel = viewModel()
        viewModel.updateConfig(BackendConfig("http://new", "new-token"))
        viewModel.saveConfigAndCheck()
        assertEquals(BackendConfig("http://new", "new-token"), repository.saved)
        assertEquals(listOf("status"), backend.calls)
        assertEquals("Connected, but live generation is off on the backend.", viewModel.state.connectionMessage)
    }

    @Test
    fun checkingTheConnectionTrimsPastedSettings() {
        val viewModel = viewModel()
        viewModel.updateConfig(BackendConfig(" http://192.168.1.5:8787 ", "pasted-token\n"))
        viewModel.saveConfigAndCheck()
        assertEquals(BackendConfig("http://192.168.1.5:8787", "pasted-token"), viewModel.state.config)
        assertEquals(BackendConfig("http://192.168.1.5:8787", "pasted-token"), repository.saved)
    }
}
