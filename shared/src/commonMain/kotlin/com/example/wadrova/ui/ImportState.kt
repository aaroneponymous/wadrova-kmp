package com.example.wadrova.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.ApiFailure
import com.example.wadrova.data.BackgroundGrace
import com.example.wadrova.data.Credential
import com.example.wadrova.data.CredentialStore
import com.example.wadrova.data.CredentialStoreException
import com.example.wadrova.data.GarmentImportRepository
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.NoBackgroundGrace
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.data.PickedPhoto
import com.example.wadrova.data.WadrovaApi
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.WadrovaConnection
import com.example.wadrova.data.normalizedApiUrl
import com.example.wadrova.data.sha256Hex
import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportProblem
import com.example.wadrova.domain.ImportState
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which import page is open over the Wardrobe tab. Null shows the Wardrobe itself. */
sealed interface ImportRoute {
    /** A photo was just picked and is being saved on the device. */
    data object Preparing : ImportRoute
    data class Progress(val importId: String) : ImportRoute
    data class Review(val importId: String) : ImportRoute

    /** The development connection settings; Back returns to [returnTo]. */
    data class Connection(val returnTo: ImportRoute?) : ImportRoute
}

/** The development connection form. The saved token is never read back into it; [hasSavedToken] says one exists. */
data class ConnectionForm(
    val url: String = "",
    val token: String = "",
    val hasSavedToken: Boolean = false,
    val checking: Boolean = false,
    val message: String? = null,
)

/** What a pending import shows: a plain-language title, an optional detail, and whether it's still moving by itself. */
data class ImportStatus(val title: String, val detail: String?, val working: Boolean)

data class ImportUiState(
    val imports: List<GarmentImport> = emptyList(),
    val route: ImportRoute? = null,
    val preparing: Boolean = false,
    val prepareError: String? = null,
    /** An import waiting for the user to confirm cancelling or removing it. */
    val pendingRemoval: String? = null,
    val connection: ConnectionForm = ConnectionForm(),
) {
    fun import(id: String): GarmentImport? = imports.find { it.id == id }
}

/** Plain language for every state. No percentages: the server reports none, so none are shown. */
fun GarmentImport.status(): ImportStatus = when (state) {
    ImportState.UPLOAD_PENDING, ImportState.UPLOADING -> when (problem) {
        ImportProblem.NOT_CONNECTED -> ImportStatus("Connect to continue", "Your photo is saved on this device. Connect to Wadrova to upload it.", false)
        ImportProblem.ACCESS_REJECTED -> ImportStatus("Reconnect to continue", "Wadrova didn't accept this device's access. Your photo is saved here.", false)
        ImportProblem.OFFLINE -> ImportStatus("Waiting for connection", "Your photo will upload when you're back online.", true)
        ImportProblem.SERVER_UNAVAILABLE -> ImportStatus("Waiting for Wadrova", "Wadrova is temporarily unavailable. We'll try again.", true)
        null -> ImportStatus("Uploading", "Sending your photo to Wadrova.", true)
    }
    ImportState.PROCESSING -> when (problem) {
        ImportProblem.NOT_CONNECTED, ImportProblem.ACCESS_REJECTED ->
            ImportStatus("Reconnect to check progress", "Wadrova has your photo. Reconnect to see when your pieces are ready.", false)
        ImportProblem.OFFLINE -> ImportStatus("Finding your pieces", "We'll check on progress when you're back online.", true)
        else -> if (serverStatus == "queued") {
            ImportStatus("Finding your pieces", "Your photo is in line to be processed.", true)
        } else {
            ImportStatus("Finding your pieces", "Wadrova is looking for the pieces in your photo.", true)
        }
    }
    ImportState.READY -> when (val count = candidateCount ?: 0) {
        0 -> ImportStatus("No pieces found", "Wadrova didn't find any clothing in this photo.", false)
        1 -> ImportStatus("Ready to review", "1 piece found", false)
        else -> ImportStatus("Ready to review", "$count pieces found", false)
    }
    ImportState.FAILED -> ImportStatus("Couldn't process photo", problemMessage ?: GarmentImport.PROCESSING_FAILED, false)
    ImportState.REJECTED -> ImportStatus("Couldn't upload photo", problemMessage, false)
    ImportState.CANCEL_PENDING -> when (problem) {
        ImportProblem.NOT_CONNECTED, ImportProblem.ACCESS_REJECTED -> ImportStatus("Cancelling", "Reconnect to Wadrova to finish cancelling.", false)
        ImportProblem.OFFLINE -> ImportStatus("Cancelling", "This will finish when you're back online.", true)
        else -> ImportStatus("Cancelling", null, true)
    }
    ImportState.CLOSED -> ImportStatus("No longer available", problemMessage, false)
}

/** Whether "Try again" can help: a stopped upload, check or cancel that's waiting on the network or the server. */
val GarmentImport.canRetry: Boolean
    get() = problem != null && state in setOf(ImportState.UPLOAD_PENDING, ImportState.PROCESSING, ImportState.CANCEL_PENDING)

val GarmentImport.needsConnection: Boolean
    get() = problem == ImportProblem.NOT_CONNECTED || problem == ImportProblem.ACCESS_REJECTED

/**
 * Runs garment imports: saves a picked photo, uploads it, follows processing and sends cancels. Each import has at
 * most one driver coroutine, so its steps never overlap. All state is in [imports], so after a relaunch the drivers
 * simply pick up from the stored rows.
 *
 * Polling happens only while the app is in the foreground. An upload that's already running is allowed to finish
 * in the background through [backgroundGrace]; anything interrupted is resent with the same key on the next launch.
 */
@OptIn(ExperimentalUuidApi::class)
class GarmentImportViewModel(
    private val imports: GarmentImportRepository,
    private val api: WadrovaApi,
    private val credentials: CredentialStore,
    private val photos: GarmentPhotoStore,
    private val backgroundGrace: BackgroundGrace = NoBackgroundGrace,
    private val newId: () -> String = { "kmp-imp-${Uuid.random()}" },
    private val random: Random = Random.Default,
) : ViewModel() {
    var state by mutableStateOf(ImportUiState())
        private set

    private val drivers = mutableMapOf<String, Job>()
    private val uploading = mutableSetOf<String>()
    private var foreground = true

    init {
        viewModelScope.launch {
            try {
                imports.resetInterruptedUploads()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            resumeAll()
        }
    }

    // Opening and closing pages

    fun open(id: String) {
        state = state.copy(route = ImportRoute.Progress(id), prepareError = null)
    }

    fun openReview(id: String) {
        state = state.copy(route = ImportRoute.Review(id))
    }

    /** Leaves the import pages. Work carries on. */
    fun close() {
        state = when (val route = state.route) {
            is ImportRoute.Connection -> state.copy(route = route.returnTo)
            is ImportRoute.Review -> state.copy(route = ImportRoute.Progress(route.importId))
            else -> state.copy(route = null, prepareError = null)
        }
    }

    // Picking a photo

    /** Picking starts the import straight away. Cancelling the picker creates nothing. */
    fun onPhotoPicked(result: PhotoPickResult) {
        when (result) {
            PhotoPickResult.Cancelled -> Unit
            PhotoPickResult.Failed -> state = state.copy(route = ImportRoute.Preparing, preparing = false, prepareError = PHOTO_FAILED)
            is PhotoPickResult.Picked -> prepare(result.photo)
        }
    }

    /**
     * Stores the photo privately, hashes the exact bytes that will be uploaded and records the import, all before any
     * network work. Picking the same photo again while its import is still open reopens that import instead.
     */
    private fun prepare(photo: PickedPhoto) {
        if (state.preparing) return
        state = state.copy(route = ImportRoute.Preparing, preparing = true, prepareError = null)
        viewModelScope.launch {
            try {
                val photoId = photos.import(photo)
                val bytes = photos.read(photoId, PhotoSize.DISPLAY)
                if (bytes == null || bytes.isEmpty()) {
                    deletePhotoQuietly(photoId)
                    throw IllegalStateException("The stored photo couldn't be reopened.")
                }
                val (import, created) = imports.createOrReuse(newId(), photoId, sha256Hex(bytes), bytes.size.toLong())
                if (!created) deletePhotoQuietly(photoId)
                reload()
                state = state.copy(
                    preparing = false,
                    // The user may have gone back while the photo was being saved; the import still shows in Wardrobe.
                    route = if (state.route == ImportRoute.Preparing) ImportRoute.Progress(import.id) else state.route,
                )
                drive(import.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(preparing = false, prepareError = PHOTO_FAILED)
            }
        }
    }

    // User actions on an import

    /** Clears a stopped import's problem and drives it again now. */
    fun retry(id: String) {
        restart(id)
    }

    fun requestRemoval(id: String) {
        state = state.copy(pendingRemoval = id)
    }

    fun keepImport() {
        state = state.copy(pendingRemoval = null)
    }

    /**
     * Cancelling is recorded first and then sent, so it survives the app closing. An import the server can't have
     * yet is removed straight away; one it may have (even from an upload whose response was lost) is cancelled there.
     */
    fun confirmRemoval() {
        val id = state.pendingRemoval ?: return
        state = state.copy(pendingRemoval = null, route = if (state.route.importId == id) null else state.route)
        viewModelScope.launch {
            val item = imports.get(id) ?: return@launch reload()
            // A failed import is cancelled too: on the server, cancel is how a failed import is dismissed.
            val onServer = item.state != ImportState.CLOSED && item.state != ImportState.REJECTED && item.mayExistOnServer
            if (onServer) {
                imports.update(id) { current, now -> current.cancelRequested(now) }
                reload()
                // If an upload is still running, its driver picks up the cancel as soon as the upload returns.
                restart(id)
            } else {
                removeLocally(item)
            }
        }
    }

    // Foreground

    fun onForeground() {
        if (foreground) return
        foreground = true
        viewModelScope.launch { resumeAll() }
    }

    /** Stops polling. A running upload is left to finish. */
    fun onBackground() {
        foreground = false
        drivers.filterKeys { it !in uploading }.forEach { (id, job) ->
            job.cancel()
            drivers.remove(id)
        }
    }

    // Development connection

    fun openConnection() {
        val returnTo = state.route.takeUnless { it is ImportRoute.Connection }
        state = state.copy(route = ImportRoute.Connection(returnTo), connection = ConnectionForm(message = null))
        viewModelScope.launch {
            val url = runCatchingQuietly { imports.apiUrl() }.orEmpty()
            val saved = runCatchingQuietly { credentials.read(Credential.WADROVA_ACCESS_TOKEN) } != null
            state = state.copy(connection = state.connection.copy(url = state.connection.url.ifEmpty { url }, hasSavedToken = saved))
        }
    }

    fun updateConnection(form: ConnectionForm) {
        state = state.copy(connection = form.copy(message = null))
    }

    /** Saves the address and, if one was typed, the token, then asks the server who the token belongs to. */
    fun saveConnection() {
        val form = state.connection
        if (form.checking) return
        val url = normalizedApiUrl(form.url)
        if (url == null) {
            state = state.copy(connection = form.copy(message = "Enter an address that starts with https://."))
            return
        }
        val typed = form.token.trim()
        if (typed.isEmpty() && !form.hasSavedToken) {
            state = state.copy(connection = form.copy(message = "Enter your access token."))
            return
        }
        state = state.copy(connection = form.copy(checking = true, message = null))
        viewModelScope.launch {
            val message = try {
                imports.saveApiUrl(url)
                if (typed.isNotEmpty()) credentials.write(Credential.WADROVA_ACCESS_TOKEN, typed)
                val token = credentials.read(Credential.WADROVA_ACCESS_TOKEN)
                    ?: throw CredentialStoreException("The saved token couldn't be read.")
                val name = api.me(WadrovaConnection(url, token))
                resumeAll(clearProblems = true)
                if (name.isBlank()) "Connected." else "Connected as $name."
            } catch (e: CancellationException) {
                throw e
            } catch (_: CredentialStoreException) {
                "Couldn't save the token in this device's secure storage."
            } catch (e: WadrovaApiException) {
                e.message
            } catch (_: Exception) {
                "Couldn't save the connection. Please try again."
            }
            state = state.copy(connection = state.connection.copy(token = "", hasSavedToken = hasToken(), checking = false, message = message))
        }
    }

    fun forgetToken() {
        viewModelScope.launch {
            val message = try {
                credentials.delete(Credential.WADROVA_ACCESS_TOKEN)
                "The access token was removed from this device."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Couldn't remove the token. Please try again."
            }
            state = state.copy(connection = state.connection.copy(token = "", hasSavedToken = hasToken(), message = message))
        }
    }

    // Driving imports

    private suspend fun resumeAll(clearProblems: Boolean = false) {
        reload()
        state.imports.forEach { item -> if (clearProblems && item.problem != null) restart(item.id) else drive(item.id) }
    }

    /** Starts the import's next step now instead of after a wait. A running upload is never interrupted. */
    private fun restart(id: String) {
        if (id !in uploading) drivers.remove(id)?.cancel()
        drive(id)
    }

    private fun drive(id: String) {
        if (drivers[id]?.isActive == true) return
        val job = viewModelScope.launch { runDriver(id) }
        drivers[id] = job
        job.invokeOnCompletion { if (drivers[id] === job) drivers.remove(id) }
    }

    /**
     * Steps one import until it needs nothing more, a person has to act, or it has failed [MAX_CONSECUTIVE_FAILURES]
     * times in a row. Retries back off exponentially with jitter and resume on the next foreground, launch, saved
     * connection or "Try again".
     */
    private suspend fun runDriver(id: String) {
        var failures = 0
        while (true) {
            val item = imports.get(id) ?: return reload()
            val step = when (item.state) {
                ImportState.UPLOAD_PENDING, ImportState.UPLOADING -> upload(item)
                ImportState.PROCESSING -> if (foreground) refresh(item) else Step.Stop
                ImportState.CANCEL_PENDING -> cancel(item)
                ImportState.READY, ImportState.FAILED, ImportState.REJECTED, ImportState.CLOSED -> Step.Stop
            }
            reload()
            when (step) {
                Step.Next -> failures = 0
                is Step.Wait -> {
                    failures = 0
                    delay(step.millis)
                }
                Step.Failed -> {
                    failures++
                    if (failures >= MAX_CONSECUTIVE_FAILURES || !foreground) return
                    delay(backoffMillis(failures))
                }
                Step.Stop -> return
            }
        }
    }

    private sealed interface Step {
        data object Next : Step
        data class Wait(val millis: Long) : Step
        data object Failed : Step
        data object Stop : Step
    }

    private suspend fun upload(item: GarmentImport): Step {
        val connection = connection() ?: return problem(item, ImportProblem.NOT_CONNECTED)
        val bytes = photos.read(item.photoId, PhotoSize.DISPLAY)
        // The exact bytes that were hashed, or nothing: a different file under the same key would be refused anyway.
        if (bytes == null || sha256Hex(bytes) != item.sourceSha256) {
            imports.update(item.id) { current, now -> current.rejected(PHOTO_MISSING, now) }
            return Step.Next
        }
        imports.update(item.id) { current, now -> current.uploadStarted(now) } ?: return Step.Stop
        reload()
        uploading += item.id
        return try {
            val remote = backgroundGrace.run { api.createImport(connection, item.id, item.sourceSha256, bytes) }
            imports.update(item.id) { current, now -> current.withServer(remote, now) }
            Step.Next
        } catch (e: WadrovaApiException) {
            when (e.failure) {
                ApiFailure.UNREACHABLE -> interrupted(item, ImportProblem.OFFLINE, Step.Failed)
                ApiFailure.UNAVAILABLE -> interrupted(item, ImportProblem.SERVER_UNAVAILABLE, Step.Failed)
                ApiFailure.UNAUTHENTICATED -> {
                    imports.update(item.id) { current, now -> current.uploadInterrupted(ImportProblem.ACCESS_REJECTED, now).uploadRefused() }
                    Step.Stop
                }
                ApiFailure.NOT_FOUND, ApiFailure.REJECTED -> {
                    imports.update(item.id) { current, now -> current.rejected(uploadRejection(e.code), now).uploadRefused() }
                    Step.Next
                }
            }
        } finally {
            uploading -= item.id
        }
    }

    private suspend fun refresh(item: GarmentImport): Step {
        val serverId = item.serverImportId ?: return Step.Stop
        val connection = connection() ?: return problem(item, ImportProblem.NOT_CONNECTED)
        return try {
            val remote = api.getImport(connection, serverId)
            val updated = imports.update(item.id) { current, now -> current.withServer(remote, now) }
            if (updated?.state == ImportState.PROCESSING) Step.Wait(pollMillis(remote.pollAfterSeconds)) else Step.Next
        } catch (e: WadrovaApiException) {
            when (e.failure) {
                ApiFailure.UNREACHABLE -> problem(item, ImportProblem.OFFLINE, Step.Failed)
                ApiFailure.UNAVAILABLE -> problem(item, ImportProblem.SERVER_UNAVAILABLE, Step.Failed)
                ApiFailure.UNAUTHENTICATED -> problem(item, ImportProblem.ACCESS_REJECTED)
                ApiFailure.NOT_FOUND -> {
                    imports.update(item.id) { current, now -> current.closed(GarmentImport.GONE_FROM_SERVER, now) }
                    Step.Next
                }
                ApiFailure.REJECTED -> problem(item, ImportProblem.SERVER_UNAVAILABLE, Step.Failed)
            }
        }
    }

    private suspend fun cancel(item: GarmentImport): Step {
        if (!item.mayExistOnServer) {
            removeLocally(item)
            return Step.Stop
        }
        val connection = connection() ?: return problem(item, ImportProblem.NOT_CONNECTED)
        return try {
            // Without a stored server id the upload's response was lost; the server's list says whether it arrived.
            val serverId = item.serverImportId ?: findOnServer(connection, item.id)
            if (serverId != null) api.cancelImport(connection, serverId)
            removeLocally(item)
            Step.Stop
        } catch (e: WadrovaApiException) {
            when (e.failure) {
                ApiFailure.UNREACHABLE -> problem(item, ImportProblem.OFFLINE, Step.Failed)
                ApiFailure.UNAVAILABLE -> problem(item, ImportProblem.SERVER_UNAVAILABLE, Step.Failed)
                ApiFailure.UNAUTHENTICATED -> problem(item, ImportProblem.ACCESS_REJECTED)
                // Gone, or already finalized (409): nothing is left to cancel, so the local record goes.
                ApiFailure.NOT_FOUND, ApiFailure.REJECTED -> {
                    removeLocally(item)
                    Step.Stop
                }
            }
        }
    }

    private suspend fun findOnServer(connection: WadrovaConnection, localId: String): String? {
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val (page, next) = api.activeImports(connection, cursor)
            page.find { it.clientRef == localId }?.let { return it.id }
            cursor = next ?: return null
        }
        return null
    }

    private suspend fun interrupted(item: GarmentImport, problem: ImportProblem, step: Step): Step {
        imports.update(item.id) { current, now -> current.uploadInterrupted(problem, now) }
        return step
    }

    private suspend fun problem(item: GarmentImport, problem: ImportProblem, step: Step = Step.Stop): Step {
        imports.update(item.id) { current, now -> current.withProblem(problem, now) }
        return step
    }

    private suspend fun removeLocally(item: GarmentImport) {
        imports.delete(item.id)
        deletePhotoQuietly(item.photoId)
        reload()
    }

    /** Null when either half is missing or the secure store can't be read; nothing is sent without a token. */
    private suspend fun connection(): WadrovaConnection? {
        val url = runCatchingQuietly { imports.apiUrl() }?.let(::normalizedApiUrl) ?: return null
        val token = runCatchingQuietly { credentials.read(Credential.WADROVA_ACCESS_TOKEN) }?.takeIf { it.isNotBlank() } ?: return null
        return WadrovaConnection(url, token)
    }

    private suspend fun hasToken(): Boolean = runCatchingQuietly { credentials.read(Credential.WADROVA_ACCESS_TOKEN) } != null

    private suspend fun reload() {
        val all = runCatchingQuietly { imports.getAll() } ?: return
        state = state.copy(imports = all)
    }

    private suspend fun deletePhotoQuietly(photoId: String) {
        runCatchingQuietly { photos.delete(photoId) }
    }

    private suspend fun <T> runCatchingQuietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun backoffMillis(failures: Int): Long {
        val base = (BASE_BACKOFF_MILLIS shl (failures - 1).coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MILLIS)
        return base + random.nextLong(base / 4 + 1)
    }

    /** Follows the server's suggested interval, kept within sensible bounds. */
    private fun pollMillis(seconds: Int?): Long = (seconds ?: DEFAULT_POLL_SECONDS).coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS) * 1000L

    private val ImportRoute?.importId: String?
        get() = when (this) {
            is ImportRoute.Progress -> importId
            is ImportRoute.Review -> importId
            else -> null
        }

    private fun uploadRejection(code: String?): String = when (code) {
        "unsupported_image" -> "Wadrova couldn't read this photo. Try a different one."
        "payload_too_large" -> "This photo is too large to upload."
        else -> "This photo couldn't be uploaded. Try a different one."
    }

    companion object {
        const val PHOTO_FAILED = "Couldn't use that photo. Try a different one."
        const val PHOTO_MISSING = "The saved copy of this photo is no longer on this device."
        const val MAX_CONSECUTIVE_FAILURES = 6
        const val BASE_BACKOFF_MILLIS = 2_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val DEFAULT_POLL_SECONDS = 5
        const val MIN_POLL_SECONDS = 2
        const val MAX_POLL_SECONDS = 30
        private const val MAX_LIST_PAGES = 10
    }
}
