package com.example.wadrova.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the development backend is. The OpenAI key stays on the backend and is never part of this. */
data class BackendConfig(val url: String, val token: String)

data class BackendStatus(
    val apiKeyConfigured: Boolean,
    val liveInferenceEnabled: Boolean,
    val model: String,
)

/** A PNG from the backend and how it was generated. */
data class GeneratedImage(val bytes: ByteArray, val model: String, val quality: String)

enum class BackendFailure { NOT_CONFIGURED, UNREACHABLE, REJECTED }

class MirrorBackendException(val failure: BackendFailure, message: String, val code: String? = null) : Exception(message)

interface MirrorBackend {
    /** Checks the backend's local configuration. This doesn't contact OpenAI or cost anything. */
    suspend fun status(config: BackendConfig): BackendStatus

    /** [references] holds 3 to 5 photos of one person, primary first. */
    suspend fun generateBaseModel(config: BackendConfig, references: List<ByteArray>): GeneratedImage

    suspend fun extractGarment(config: BackendConfig, photo: ByteArray): GeneratedImage

    /** Always pass the approved model, never an earlier try-on. */
    suspend fun tryOn(config: BackendConfig, model: ByteArray, garment: ByteArray): GeneratedImage
}

/** Reaches a local backend from a simulator, or from Android through `adb reverse`. Other setups use Mirror settings. */
const val DEFAULT_MIRROR_BACKEND_URL = "http://127.0.0.1:8787"

private const val TOKEN_HEADER = "X-Wadrova-Dev-Token"
private const val GENERATION_TIMEOUT_MILLIS = 300_000L

fun createMirrorHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = GENERATION_TIMEOUT_MILLIS
        socketTimeoutMillis = GENERATION_TIMEOUT_MILLIS
    }
}

class KtorMirrorBackend(private val client: HttpClient) : MirrorBackend {

    override suspend fun status(config: BackendConfig): BackendStatus {
        val response = request(config) { base ->
            client.get("$base/v1/status") { header(TOKEN_HEADER, config.token) }
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return BackendStatus(
            apiKeyConfigured = body.getValue("api_key_configured").jsonPrimitive.boolean,
            liveInferenceEnabled = body.getValue("live_inference_enabled").jsonPrimitive.boolean,
            model = body.getValue("model").jsonPrimitive.content,
        )
    }

    override suspend fun generateBaseModel(config: BackendConfig, references: List<ByteArray>): GeneratedImage =
        generate(config, "base-model", references.mapIndexed { index, bytes -> Upload("images", "reference-$index", bytes) })

    override suspend fun extractGarment(config: BackendConfig, photo: ByteArray): GeneratedImage =
        generate(config, "garment-extraction", listOf(Upload("image", "garment", photo)))

    override suspend fun tryOn(config: BackendConfig, model: ByteArray, garment: ByteArray): GeneratedImage =
        generate(
            config,
            "try-on",
            listOf(Upload("model_image", "model", model), Upload("garment_image", "garment", garment)),
        )

    private class Upload(val field: String, val fileName: String, val bytes: ByteArray)

    private suspend fun generate(config: BackendConfig, operation: String, uploads: List<Upload>): GeneratedImage {
        val response = request(config) { base ->
            client.post("$base/v1/images/$operation") {
                header(TOKEN_HEADER, config.token)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            uploads.forEach { upload ->
                                append(
                                    upload.field,
                                    upload.bytes,
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"${upload.fileName}\"")
                                    },
                                )
                            }
                        },
                    ),
                )
            }
        }
        val bytes = response.bodyAsBytes()
        if (bytes.isEmpty()) throw MirrorBackendException(BackendFailure.REJECTED, "The backend returned no image.")
        return GeneratedImage(
            bytes = bytes,
            model = response.headers["X-Generation-Model"].orEmpty(),
            quality = response.headers["X-Generation-Quality"].orEmpty(),
        )
    }

    private suspend fun request(config: BackendConfig, send: suspend (String) -> HttpResponse): HttpResponse {
        val base = config.url.trim().trimEnd('/')
        if (base.isEmpty() || config.token.isBlank()) {
            throw MirrorBackendException(
                BackendFailure.NOT_CONFIGURED,
                "The Mirror backend isn't set up. Add its address and development token in Mirror settings.",
            )
        }
        val response = try {
            send(base)
        } catch (e: CancellationException) {
            // Some engines report a timeout as cancellation, which only the caller's own cancel should be.
            if (!currentCoroutineContext().isActive) throw e
            throw unreachable()
        } catch (_: Exception) {
            throw unreachable()
        }
        if (response.status.isSuccess()) return response
        throw errorFrom(response)
    }

    private fun unreachable() = MirrorBackendException(
        BackendFailure.UNREACHABLE,
        "Couldn't reach the Mirror backend. Check that it's running and that the address in Mirror settings is right.",
    )

    private suspend fun errorFrom(response: HttpResponse): MirrorBackendException {
        val body = try {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val code = body?.get("code")?.jsonPrimitive?.content
        val message = body?.get("message")?.jsonPrimitive?.content ?: "The Mirror backend returned an error."
        val failure = if (code in NOT_CONFIGURED_CODES) BackendFailure.NOT_CONFIGURED else BackendFailure.REJECTED
        return MirrorBackendException(failure, message, code)
    }

    private companion object {
        val NOT_CONFIGURED_CODES = setOf(
            "token_not_configured",
            "invalid_token",
            "api_key_not_configured",
            "live_inference_disabled",
        )
    }
}
