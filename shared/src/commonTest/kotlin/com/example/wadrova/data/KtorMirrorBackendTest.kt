package com.example.wadrova.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray

class KtorMirrorBackendTest {
    private val config = BackendConfig("http://backend.test:8787/", "dev-token")
    private val png = byteArrayOf(1, 2, 3)
    private val requests = mutableListOf<HttpRequestData>()

    private fun backend(handler: suspend MockRequestHandleScope.() -> HttpResponseData): MirrorBackend =
        KtorMirrorBackend(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler()
                },
            ),
        )

    private fun imageBackend() = backend {
        respond(png, HttpStatusCode.OK, headersOf("X-Generation-Model" to listOf("model-x"), "X-Generation-Quality" to listOf("high")))
    }

    private fun failingBackend(code: String, message: String, status: HttpStatusCode = HttpStatusCode.ServiceUnavailable) = backend {
        respond(
            """{"code":"$code","message":"$message"}""",
            status,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private suspend fun body(request: HttpRequestData): String = kotlinx.coroutines.coroutineScope {
        val channel = ByteChannel()
        val content = request.body as OutgoingContent.WriteChannelContent
        launch {
            content.writeTo(channel)
            channel.flushAndClose()
        }
        channel.readRemaining().readByteArray().decodeToString()
    }

    @Test
    fun baseModelSendsReferencesInOrderWithTheToken() = runTest {
        val result = imageBackend().generateBaseModel(config, listOf("first".encodeToByteArray(), "second".encodeToByteArray(), "third".encodeToByteArray()))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://backend.test:8787/v1/images/base-model", request.url.toString())
        assertEquals("dev-token", request.headers["X-Wadrova-Dev-Token"])
        val sent = body(request)
        assertTrue(sent.indexOf("first") < sent.indexOf("second") && sent.indexOf("second") < sent.indexOf("third"))
        assertEquals(3, Regex("""name="?images"?""").findAll(sent).count())
        assertContentEquals(png, result.bytes)
        assertEquals("model-x", result.model)
        assertEquals("high", result.quality)
    }

    @Test
    fun extractionUsesItsEndpointAndField() = runTest {
        imageBackend().extractGarment(config, "photo".encodeToByteArray())
        val request = requests.single()
        assertEquals("/v1/images/garment-extraction", request.url.encodedPath)
        assertTrue(Regex("""name="?image"?""").containsMatchIn(body(request)))
    }

    @Test
    fun tryOnSendsTheModelBeforeTheGarment() = runTest {
        imageBackend().tryOn(config, "model-bytes".encodeToByteArray(), "garment-bytes".encodeToByteArray())
        val request = requests.single()
        assertEquals("/v1/images/try-on", request.url.encodedPath)
        val sent = body(request)
        assertTrue(sent.indexOf("model-bytes") < sent.indexOf("garment-bytes"))
        assertTrue(Regex("""name="?model_image"?""").containsMatchIn(sent))
        assertTrue(Regex("""name="?garment_image"?""").containsMatchIn(sent))
    }

    @Test
    fun missingSettingsFailWithoutAnyRequest() = runTest {
        val backend = imageBackend()
        val noToken = assertFailsWith<MirrorBackendException> { backend.extractGarment(config.copy(token = " "), png) }
        assertEquals(BackendFailure.NOT_CONFIGURED, noToken.failure)
        assertFailsWith<MirrorBackendException> { backend.status(config.copy(url = "")) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun configurationErrorsAreReportedAsNotConfigured() = runTest {
        for (code in listOf("live_inference_disabled", "api_key_not_configured", "token_not_configured", "invalid_token")) {
            val error = assertFailsWith<MirrorBackendException> {
                failingBackend(code, "Backend says $code").extractGarment(config, png)
            }
            assertEquals(BackendFailure.NOT_CONFIGURED, error.failure)
            assertEquals(code, error.code)
            assertEquals("Backend says $code", error.message)
        }
    }

    @Test
    fun openAiErrorsKeepTheBackendsMessage() = runTest {
        val error = assertFailsWith<MirrorBackendException> {
            failingBackend("openai_rate_limited", "Rate limit reached.", HttpStatusCode.TooManyRequests).tryOn(config, png, png)
        }
        assertEquals(BackendFailure.REJECTED, error.failure)
        assertEquals("Rate limit reached.", error.message)
    }

    @Test
    fun nonJsonErrorsAreHandled() = runTest {
        val error = assertFailsWith<MirrorBackendException> {
            backend { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) }.extractGarment(config, png)
        }
        assertEquals(BackendFailure.REJECTED, error.failure)
        assertNull(error.code)
    }

    @Test
    fun connectionFailuresAreReportedAsUnreachable() = runTest {
        val error = assertFailsWith<MirrorBackendException> {
            backend { throw IllegalStateException("connection refused") }.extractGarment(config, png)
        }
        assertEquals(BackendFailure.UNREACHABLE, error.failure)
    }

    @Test
    fun emptyImageIsAnError() = runTest {
        assertFailsWith<MirrorBackendException> {
            backend { respond(ByteArray(0), HttpStatusCode.OK) }.extractGarment(config, png)
        }
    }

    @Test
    fun statusParsesConfiguration() = runTest {
        val status = backend {
            respond(
                """{"api_key_configured":true,"live_inference_enabled":false,"model":"m","quality":"high"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }.status(config)
        assertEquals(BackendStatus(apiKeyConfigured = true, liveInferenceEnabled = false, model = "m"), status)
        assertEquals("/v1/status", requests.single().url.encodedPath)
        assertEquals("dev-token", requests.single().headers["X-Wadrova-Dev-Token"])
    }
}
