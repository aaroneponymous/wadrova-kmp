package com.example.wadrova.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The request and response shapes of wadrova-backend `docs/API.md` (Mobile imports, M4.1-M4.3). */
class KtorWadrovaApiTest {
    private val connection = WadrovaConnection("https://api.test/", "wdr_secret")
    private val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
    private val sha = "ab".repeat(32)
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): WadrovaApi =
        KtorWadrovaApi(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ),
        )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>) =
        respond(body, status, headersOf(*(listOf(HttpHeaders.ContentType to "application/json") + extra).map { it.first to listOf(it.second) }.toTypedArray()))

    private fun importJson(status: String = "queued", extra: String = "") = """
        {"id":"imp_1","status":"$status","client_ref":"kmp-imp-1",
         "photo":{"id":"pho_1","upload_id":"upl_1","width":10,"height":10,"oriented_width":10,"oriented_height":10,
                  "exif_orientation":null,"coordinate_frame":"exif_oriented","client_photo_ref":"kmp-imp-1",
                  "original":{"id":"ast_1","kind":"source_original","sha256":"$sha","byte_size":6,"media_type":"image/jpeg",
                              "width":10,"height":10,"content_url":"/v1/assets/ast_1/content"},"created_at":"2026-11-03T00:00:00Z"},
         "session_id":"ses_1","processing":{"job_status":"queued","attempts":0,"max_attempts":3,"retrying":false},
         "failure":null,"candidate_count":null,"candidates_url":"/v1/extraction-sessions/ses_1/candidates",
         "created_at":"2026-11-03T00:00:00Z","cancelled_at":null,"finalized_at":null,"poll_after_seconds":3,
         "finalization":null,"unknown_future_field":1$extra}
    """.trimIndent()

    private fun error(code: String, retryable: Boolean = false) =
        """{"error":{"code":"$code","message":"server text","retryable":$retryable,"request_id":"req_1","details":{}}}"""

    @Test
    fun createSendsTheRawBytesWithKeyHashAndBearerToken() = runTest {
        val created = api { json(importJson(), HttpStatusCode.Created) }.createImport(connection, "kmp-imp-1", sha, jpeg)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.test/v1/imports", request.url.toString())
        assertEquals("Bearer wdr_secret", request.headers[HttpHeaders.Authorization])
        assertEquals("kmp-imp-1", request.headers["Idempotency-Key"])
        assertEquals(sha, request.headers["X-Wadrova-SHA256"])
        assertEquals("kmp-imp-1", request.headers["X-Client-Import-Ref"])
        val body = request.body as OutgoingContent.ByteArrayContent
        assertEquals(ContentType.Image.JPEG, body.contentType)
        assertContentEquals(jpeg, body.bytes())
        assertFalse("wdr_secret" in request.url.toString())

        assertEquals("imp_1", created.id)
        assertEquals("queued", created.status)
        assertEquals("ses_1", created.sessionId)
        assertEquals("kmp-imp-1", created.clientRef)
        assertEquals(3, created.pollAfterSeconds)
        assertNull(created.candidateCount)
    }

    @Test
    fun aReplayReturnsTheSameImportWithItsCurrentState() = runTest {
        val replay = api { json(importJson("ready").replace("\"candidate_count\":null", "\"candidate_count\":8"), HttpStatusCode.OK, "Idempotent-Replay" to "true") }
            .createImport(connection, "kmp-imp-1", sha, jpeg)
        assertEquals("imp_1", replay.id)
        assertEquals("ready", replay.status)
        assertEquals(8, replay.candidateCount)
    }

    @Test
    fun aFailedImportCarriesTheServersSafeMessage() = runTest {
        val body = importJson("failed").replace(
            "\"failure\":null",
            "\"failure\":{\"code\":\"photo_unreadable\",\"message\":\"We couldn't read this photo.\",\"retryable_by_user\":false}",
        )
        val failed = api { json(body) }.getImport(connection, "imp_1")
        assertEquals("failed", failed.status)
        assertEquals("We couldn't read this photo.", failed.failureMessage)
        assertEquals("https://api.test/v1/imports/imp_1", requests.single().url.toString())
    }

    @Test
    fun cancelPostsWithoutABodyOrKey() = runTest {
        val cancelled = api { json(importJson("cancelled")) }.cancelImport(connection, "imp_1")
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.test/v1/imports/imp_1/cancel", request.url.toString())
        assertNull(request.headers["Idempotency-Key"])
        assertEquals("cancelled", cancelled.status)
    }

    @Test
    fun listingActiveImportsPagesWithACursor() = runTest {
        val api = api { request ->
            if (request.url.parameters["cursor"] == null) {
                json("""{"imports":[${importJson()}],"next_cursor":"c2"}""")
            } else {
                json("""{"imports":[],"next_cursor":null}""")
            }
        }
        val (first, next) = api.activeImports(connection)
        assertEquals(listOf("imp_1"), first.map { it.id })
        assertEquals("c2", next)
        assertEquals("active", requests[0].url.parameters["state"])
        assertEquals("100", requests[0].url.parameters["limit"])
        val (second, end) = api.activeImports(connection, "c2")
        assertTrue(second.isEmpty())
        assertNull(end)
        assertEquals("c2", requests[1].url.parameters["cursor"])
    }

    @Test
    fun meReturnsTheDisplayName() = runTest {
        assertEquals("Drill", api { json("""{"user_id":"usr_1","display_name":"Drill"}""") }.me(connection))
        assertEquals("https://api.test/v1/me", requests.single().url.toString())
    }

    @Test
    fun errorsMapToFailuresWithoutLeakingTheToken() = runTest {
        suspend fun failure(status: HttpStatusCode, body: String): WadrovaApiException =
            assertFailsWith { api { json(body, status) }.getImport(connection, "imp_1") }

        val unauthenticated = failure(HttpStatusCode.Unauthorized, error("unauthenticated"))
        assertEquals(ApiFailure.UNAUTHENTICATED, unauthenticated.failure)
        assertEquals(ApiFailure.NOT_FOUND, failure(HttpStatusCode.NotFound, error("not_found")).failure)
        assertEquals(ApiFailure.UNAVAILABLE, failure(HttpStatusCode.ServiceUnavailable, error("storage_unavailable", retryable = true)).failure)
        assertEquals(ApiFailure.UNAVAILABLE, failure(HttpStatusCode.InternalServerError, "not json").failure)
        val unsupported = failure(HttpStatusCode.BadRequest, error("unsupported_image"))
        assertEquals(ApiFailure.REJECTED, unsupported.failure)
        assertEquals("unsupported_image", unsupported.code)
        val conflict = failure(HttpStatusCode.Conflict, error("invalid_state"))
        assertEquals(ApiFailure.REJECTED, conflict.failure)
        assertEquals("invalid_state", conflict.code)
        listOf(unauthenticated, unsupported, conflict).forEach { assertFalse("wdr_secret" in (it.message ?: "")) }
    }

    @Test
    fun transportFailuresAndTimeoutsAreUnreachableAndCarryNoDetails() = runTest {
        val timeout = assertFailsWith<WadrovaApiException> {
            api { throw HttpRequestTimeoutException("https://api.test/v1/imports?token=wdr_secret", 60_000) }
                .createImport(connection, "kmp-imp-1", sha, jpeg)
        }
        assertEquals(ApiFailure.UNREACHABLE, timeout.failure)
        assertNull(timeout.cause)
        assertFalse("api.test" in (timeout.message ?: ""))

        val refused = assertFailsWith<WadrovaApiException> { api { throw IllegalStateException("refused") }.getImport(connection, "imp_1") }
        assertEquals(ApiFailure.UNREACHABLE, refused.failure)
    }

    @Test
    fun nothingIsSentWithoutATokenOrOverPlainHttp() = runTest {
        val api = api { json(importJson()) }
        assertEquals(
            ApiFailure.UNAUTHENTICATED,
            assertFailsWith<WadrovaApiException> { api.getImport(connection.copy(token = " "), "imp_1") }.failure,
        )
        assertEquals(
            ApiFailure.REJECTED,
            assertFailsWith<WadrovaApiException> { api.getImport(connection.copy(baseUrl = "http://api.test"), "imp_1") }.failure,
        )
        assertFailsWith<WadrovaApiException> { api.getImport(connection, "../me") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun anUnreadableResponseIsNotTakenAsAnImport() = runTest {
        val e = assertFailsWith<WadrovaApiException> { api { json("""{"id":"imp_1"}""") }.getImport(connection, "imp_1") }
        assertEquals(ApiFailure.UNAVAILABLE, e.failure)
    }

    @Test
    fun addressesAreNormalizedAndMustBeHttps() {
        assertEquals("https://host.ts.net", normalizedApiUrl(" https://host.ts.net/ "))
        assertEquals("https://host:8443/base", normalizedApiUrl("https://host:8443/base"))
        assertNull(normalizedApiUrl("http://host.ts.net"))
        assertNull(normalizedApiUrl("host.ts.net"))
        assertNull(normalizedApiUrl("https://"))
        assertNull(normalizedApiUrl("https://user@host"))
    }
}
