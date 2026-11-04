package com.example.wadrova.data

import com.example.wadrova.domain.ServerImport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** The Wadrova API (`wadrova-backend`, `/v1`) and the bearer token for it. The token is never part of a URL. */
data class WadrovaConnection(val baseUrl: String, val token: String)

enum class ApiFailure {
    /** No response: offline, DNS, TLS, refused or timed out. Safe to retry with the same request. */
    UNREACHABLE,

    /** 503 or another server error marked retryable. */
    UNAVAILABLE,

    /** 401: the token is missing, unknown, revoked or expired. */
    UNAUTHENTICATED,

    /** 404: unknown, or not this user's. */
    NOT_FOUND,

    /** Any other refusal. [WadrovaApiException.code] says which. */
    REJECTED,
}

/** [message] is written by this app or is the server's user-safe text; it never holds the token or request details. */
class WadrovaApiException(val failure: ApiFailure, message: String, val code: String? = null) : Exception(message)

interface WadrovaApi {
    /** The display name of the token's user. */
    suspend fun me(connection: WadrovaConnection): String

    /**
     * `POST /v1/imports`: the raw image bytes with their SHA-256. [key] is the local import id. Resending the same key
     * and bytes returns the same import (a replay), never a second one.
     */
    suspend fun createImport(connection: WadrovaConnection, key: String, sha256: String, jpeg: ByteArray): ServerImport

    suspend fun getImport(connection: WadrovaConnection, id: String): ServerImport

    /** One page of the user's active imports (not cancelled or finalized), newest first, and the next cursor. */
    suspend fun activeImports(connection: WadrovaConnection, cursor: String? = null): Pair<List<ServerImport>, String?>

    /** Idempotent: cancelling a cancelled import returns it unchanged. */
    suspend fun cancelImport(connection: WadrovaConnection, id: String): ServerImport
}

/** Short timeouts: requests carry one sub-MB photo or a small JSON body, and a stalled one is simply retried. */
fun createWadrovaHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 30_000
    }
}

/**
 * Returns the address without a trailing slash, or null if it isn't an https URL. Plain http is refused so the
 * bearer token never travels unencrypted.
 */
fun normalizedApiUrl(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val host = trimmed.substring("https://".length).substringBefore('/')
    if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' || it == '?' || it == '#' }) return null
    return trimmed
}

class KtorWadrovaApi(private val client: HttpClient) : WadrovaApi {

    override suspend fun me(connection: WadrovaConnection): String {
        val body = json(request(connection) { client.get("$it/v1/me") { auth(connection) } })
        return body.string("display_name") ?: ""
    }

    override suspend fun createImport(connection: WadrovaConnection, key: String, sha256: String, jpeg: ByteArray): ServerImport {
        val response = request(connection) { base ->
            client.post("$base/v1/imports") {
                auth(connection)
                header("Idempotency-Key", key)
                header("X-Wadrova-SHA256", sha256)
                header("X-Client-Import-Ref", key)
                setBody(ByteArrayContent(jpeg, ContentType.Image.JPEG))
            }
        }
        return json(response).toServerImport()
    }

    override suspend fun getImport(connection: WadrovaConnection, id: String): ServerImport =
        json(request(connection) { client.get("$it/v1/imports/${pathId(id)}") { auth(connection) } }).toServerImport()

    override suspend fun activeImports(connection: WadrovaConnection, cursor: String?): Pair<List<ServerImport>, String?> {
        val body = json(
            request(connection) { base ->
                client.get("$base/v1/imports") {
                    auth(connection)
                    parameter("state", "active")
                    parameter("limit", 100)
                    cursor?.let { parameter("cursor", it) }
                }
            },
        )
        val imports = (body["imports"] as? JsonArray).orEmpty().map { it.jsonObject.toServerImport() }
        return imports to body.string("next_cursor")
    }

    override suspend fun cancelImport(connection: WadrovaConnection, id: String): ServerImport =
        json(request(connection) { client.post("$it/v1/imports/${pathId(id)}/cancel") { auth(connection) } }).toServerImport()

    private fun HttpRequestBuilder.auth(connection: WadrovaConnection) {
        header(HttpHeaders.Authorization, "Bearer ${connection.token}")
    }

    /** Server ids are type-prefixed and alphanumeric; anything else must not be spliced into a path. */
    private fun pathId(id: String): String {
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            throw WadrovaApiException(ApiFailure.REJECTED, "The server returned an unexpected import id.")
        }
        return id
    }

    private suspend fun request(connection: WadrovaConnection, send: suspend (String) -> HttpResponse): HttpResponse {
        val base = normalizedApiUrl(connection.baseUrl)
            ?: throw WadrovaApiException(ApiFailure.REJECTED, "The Wadrova address must start with https://.", "invalid_address")
        if (connection.token.isBlank()) throw WadrovaApiException(ApiFailure.UNAUTHENTICATED, "No access token is saved.")
        val response = try {
            send(base)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Transport errors can name the host or URL; the cause is dropped so nothing about the request leaks.
            throw WadrovaApiException(ApiFailure.UNREACHABLE, "Couldn't reach Wadrova.")
        }
        if (response.status.isSuccess()) return response
        throw failureOf(response)
    }

    private suspend fun failureOf(response: HttpResponse): WadrovaApiException {
        val error = try {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"] as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val code = error?.string("code")
        val retryable = (error?.get("retryable") as? JsonPrimitive)?.booleanOrNull ?: false
        val status = response.status.value
        val failure = when {
            status == 401 -> ApiFailure.UNAUTHENTICATED
            status == 404 -> ApiFailure.NOT_FOUND
            // A server-side fault isn't the request's fault, so it's retried (a bounded number of times) like an outage.
            status >= 500 || retryable -> ApiFailure.UNAVAILABLE
            else -> ApiFailure.REJECTED
        }
        val message = when (failure) {
            ApiFailure.UNAUTHENTICATED -> "Wadrova didn't accept the access token."
            ApiFailure.NOT_FOUND -> "Wadrova doesn't have this import."
            ApiFailure.UNAVAILABLE -> "Wadrova is temporarily unavailable."
            else -> "Wadrova refused the request."
        }
        return WadrovaApiException(failure, message, code)
    }

    private suspend fun json(response: HttpResponse): JsonObject = try {
        Json.parseToJsonElement(response.bodyAsText()).jsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw WadrovaApiException(ApiFailure.UNAVAILABLE, "Wadrova sent a response this app couldn't read.")
    }

    private fun JsonObject.toServerImport(): ServerImport {
        val id = string("id")
        val status = string("status")
        val sessionId = string("session_id")
        if (id == null || status == null || sessionId == null) {
            throw WadrovaApiException(ApiFailure.UNAVAILABLE, "Wadrova sent a response this app couldn't read.")
        }
        val failure = this["failure"] as? JsonObject
        return ServerImport(
            id = id,
            clientRef = string("client_ref"),
            status = status,
            sessionId = sessionId,
            candidateCount = int("candidate_count"),
            failureMessage = failure?.string("message"),
            pollAfterSeconds = int("poll_after_seconds"),
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
}
