package cc.fplj.magpiemobile.core

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderModelDiscoveryTest {
    private lateinit var api: ProviderApiFixture
    @BeforeEach fun start() { api = ProviderApiFixture() }
    @AfterEach fun stop() { api.close() }
    private fun request(key: String = "", url: String = "api.example", id: String? = null, revision: String? = null,
        local: Boolean = false) = ProviderModelDiscoveryRequest(url, key, id, revision, local)
    private fun catalog(base: String = "https://api.example/v1", rows: JsonArray = JsonArray(listOf(buildJsonObject {
        put("id", "raw/model-id"); put("name", "Model name")
    }))) = buildJsonObject { put("baseUrl", base); put("models", rows) }.toString()

    @Test fun normalizeMissingSchemeAndPlainRootAddsHttpsAndV1Once() {
        assertEquals("https://api.example/v1", ProviderBaseUrl.normalize("api.example"))
        assertEquals("https://api.example/v1", ProviderBaseUrl.normalize(" https://api.example/ "))
        assertEquals("https://api.example/v1", ProviderBaseUrl.normalize("HTTPS://API.EXAMPLE/v1/"))
    }
    @Test fun normalizeV1AndCustomPrefixesNeverDoubleV1() {
        for ((input, expected) in listOf("https://api.example/v1/" to "https://api.example/v1",
            "https://api.example/custom" to "https://api.example/custom", "https://api.example/custom/v1/" to "https://api.example/custom/v1")) {
            assertEquals(expected, ProviderBaseUrl.normalize(input))
            assertFalse(request(url = input).chatUrl.contains("/v1/v1"))
        }
    }
    @Test fun fullChatResponsesAndModelsSuffixesBecomeCorrectBase() {
        assertEquals("https://api.example/v1", ProviderBaseUrl.normalize("https://api.example/v1/chat/completions"))
        assertEquals("https://api.example/custom", ProviderBaseUrl.normalize("https://api.example/custom/responses"))
        assertEquals("https://api.example/v1", ProviderBaseUrl.normalize("https://api.example/v1/models"))
        assertEquals("https://api.example", ProviderBaseUrl.normalize("https://api.example/chat/completions"))
        assertEquals("https://api.example", ProviderBaseUrl.normalize("https://api.example/responses/"))
        assertEquals("https://api.example/chat/completions", request(url = "https://api.example/chat/completions").chatUrl)
    }
    @Test fun normalizeRejectsCredentialsQueryControlsTraversalAndNonHttpSchemes() {
        for (input in listOf("https://secret@api.example", "https://api.example?key=secret", "https://api.example#secret",
            "https://api.example/\n", "https://api.example/v1/../admin", "https://api.example/%76%31",
            "file:///tmp/data", "ftp://api.example", "//api.example", "")) {
            val error = assertFailsWith<IllegalArgumentException> { ProviderBaseUrl.normalize(input) }
            assertFalse(error.message!!.contains("key=secret"))
        }
    }
    @Test fun localhostIsNotSilentlyRewrittenAndHttpNeedsLiteralPortOptIn() {
        for (input in listOf("http://localhost:9000", "http://127.1:9000", "http://127.0.0.1", "https://127.0.0.1:9000", "localhost:9000")) {
            assertFailsWith<IllegalArgumentException> { ProviderBaseUrl.normalize(input) }
        }
        assertEquals("http://127.0.0.1:9000/v1", ProviderBaseUrl.normalize("http://127.0.0.1:9000"))
        assertFailsWith<IllegalArgumentException> { request(url = "http://127.0.0.1:9000") }
        assertEquals("http://[::1]:9000/v1/chat/completions", request(url = "http://[::1]:9000", local = true).chatUrl)
    }
    @Test fun autoSuggestionsAreSafeSlugsNamesAndEmptyForIncompleteInvalidInput() {
        assertEquals("openai", ProviderBaseUrl.suggestId("api.openai.com"))
        assertEquals("OpenAI", ProviderBaseUrl.suggestName("api.openai.com"))
        assertEquals("deepseek", ProviderBaseUrl.suggestId("https://api.deepseek.com/chat/completions"))
        assertEquals("DeepSeek", ProviderBaseUrl.suggestName("https://api.deepseek.com"))
        assertEquals("local-9000", ProviderBaseUrl.suggestId("http://127.0.0.1:9000"))
        assertTrue(ProviderBaseUrl.suggestName("http://127.0.0.1:9000").contains("本机"))
        assertEquals("", ProviderBaseUrl.suggestId("https://secret@api.example"))
        assertEquals("", ProviderBaseUrl.suggestName(""))
    }
    @Test fun discoveryBodyIsOnlyReadonlyChatOptionalKeyAndPairedEditIdentity() {
        val public = request().requestBody()
        assertEquals(setOf("chat"), public.keys)
        assertEquals(JsonPrimitive("https://api.example/v1/chat/completions"), public["chat"])
        val edit = request(id = "custom-api", revision = "a".repeat(64)).requestBody()
        assertEquals(setOf("chat", "id", "revision"), edit.keys)
        assertFalse(edit.containsKey("key") || edit.containsKey("models") || edit.containsKey("new"))
        assertFailsWith<IllegalArgumentException> { request(id = "custom-api") }
        assertFailsWith<IllegalArgumentException> { request(revision = "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { request(id = "custom-api", revision = "bad") }
    }
    @Test fun requestSecretIsSeparateFromFormValueAndClearIsNotAWriteDraftConsume() {
        val formKey = ProviderApiFixture.API_KEY
        val temporary = request(key = formKey)
        assertTrue(temporary.requestBody().containsKey("key"))
        temporary.clearSecret()
        assertFalse(temporary.requestBody().containsKey("key"))
        assertEquals(ProviderApiFixture.API_KEY, formKey)
        assertFalse(temporary.toString().contains(formKey))
        assertEquals(temporary.requestBody(), temporary.requestBody()) // repeatable metadata body, not write once state
    }
    @Test fun publicDirectoryWithoutKeyReturnsCatalogAndDoesNotWriteOrSelectModels() {
        val before = api.canonical
        val temporary = request()
        val found = api.client().discoverModels(temporary)
        assertEquals(listOf("catalog-model"), found.models.map { it.id })
        assertEquals("https://api.example/v1", found.baseUrl)
        assertEquals(before, api.canonical)
        assertEquals(0, api.createRequests.get())
        assertEquals(0, api.editRequests.get())
        assertEquals(1, api.modelRequests.get())
        val rpc = api.requests.single { it.path.endsWith("/models") }
        assertEquals("POST", rpc.method)
        assertEquals(setOf("chat"), Json.parseToJsonElement(rpc.body).jsonObject.keys)
        assertEquals(api.credential.cookieHeader(), rpc.cookie)
        assertNull(rpc.authorization)
        assertNull(rpc.query)
        assertNull(rpc.origin)
        assertEquals(rpc.body.toByteArray(Charsets.UTF_8).size.toString(), rpc.contentLength)
        assertNull(rpc.transferEncoding)
    }
    @Test fun editReusesCoreStoredKeyWithoutReadingOrPostingItToTheApp() {
        val before = api.canonical
        val temporary = request(id = "custom-api", revision = api.revision)
        val found = api.client().discoverModels(temporary)
        assertEquals(1, found.models.size)
        val body = Json.parseToJsonElement(api.requests.single { it.path.endsWith("/models") }.body).jsonObject
        assertEquals(JsonPrimitive("custom-api"), body["id"])
        assertFalse(body.containsKey("key"))
        assertEquals(before, api.canonical)
        assertFalse(found.toString().contains(ProviderApiFixture.API_KEY))
    }
    @Test fun optionalSubmittedKeyOnlyLivesInTemporaryRequestAndResultNeverContainsIt() {
        val formKey = ProviderApiFixture.API_KEY
        val temporary = request(key = formKey)
        val found = api.client().discoverModels(temporary)
        assertFalse(temporary.requestBody().containsKey("key"))
        assertEquals(formKey, ProviderApiFixture.API_KEY)
        assertFalse(found.toString().contains(formKey))
        assertEquals(0, api.createRequests.get())
    }
    @Test fun missingDiscoveryCapabilityAllowsNoRpcAndPreservesExistingWriteDraft() {
        api.capabilitiesBody = JsonObject(Json.parseToJsonElement(ProviderApiFixture.CAPABILITIES).jsonObject - "providerModelDiscovery").toString()
        val write = ProviderCreateDraft("new-provider", "Name", "api.example", ProviderApiFixture.API_KEY, emptyList())
        val failure = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request(key = ProviderApiFixture.API_KEY)) }
        assertEquals(ProviderModelDiscoveryFailure.UNSUPPORTED, failure.failure)
        assertEquals(0, api.modelRequests.get())
        assertTrue(write.requestBody().containsKey("key"))
        write.submissionBody() // directory attempts cannot consume a separate write object
    }
    @Test fun duplicatesAndOptionalNamesKeepValidRawIdsAndDoNotInventModels() {
        val opaqueId = "a".repeat(64)
        api.modelsOverride = catalog(rows = JsonArray(listOf(buildJsonObject { put("id", "raw/id") },
            buildJsonObject { put("id", "raw/id"); put("name", "duplicate") },
            buildJsonObject { put("id", opaqueId); put("name", "Task-Model") })))
        val found = api.client().discoverModels(request())
        assertEquals(listOf("raw/id", opaqueId), found.models.map { it.id })
        assertEquals("raw/id", found.models.first().name)
        assertEquals("Task-Model", found.models.last().name)
        api.modelsOverride = catalog(rows = JsonArray(emptyList()))
        assertTrue(api.client().discoverModels(request()).models.isEmpty())
    }
    @Test fun nonJsonMissingOrNullModelsAndBadModelIdAreReadFailuresNotWrites() {
        val invalid = listOf("not-json " + ProviderApiFixture.API_KEY, "{}", """{"baseUrl":"https://api.example/v1","models":null}""",
            catalog(rows = JsonArray(listOf(JsonPrimitive("raw/id")))), catalog(rows = JsonArray(listOf(buildJsonObject { put("id", "") }))))
        for (reply in invalid) {
            api.modelsOverride = reply
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
            assertEquals(ProviderModelDiscoveryFailure.INVALID_RESPONSE, error.failure)
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        }
        assertEquals(0, api.editRequests.get())
        assertEquals(0, api.createRequests.get())
    }
    @Test fun responseKeyEchoInIdOrNameIsRejectedWithNoSecretInErrors() {
        for (field in listOf("id", "name")) {
            api.modelsOverride = catalog(rows = JsonArray(listOf(buildJsonObject {
                put("id", "raw/id"); put("name", "Name"); put(field, ProviderApiFixture.API_KEY)
            })))
            val temporary = request(key = ProviderApiFixture.API_KEY)
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(temporary) }
            assertEquals(ProviderModelDiscoveryFailure.INVALID_RESPONSE, error.failure)
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
            assertFalse(temporary.requestBody().containsKey("key"))
        }
    }
    @Test fun returnBaseMustBeSameOriginAndOnlyTheGoRootV1Candidate() {
        for (base in listOf("https://other.example/v1", "http://api.example/v1", "https://api.example:8443/v1", "https://api.example/arbitrary")) {
            api.modelsOverride = catalog(base)
            assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
        }
        api.modelsOverride = catalog("https://api.example")
        assertEquals("https://api.example", api.client().discoverModels(request()).baseUrl)
    }
    @Test fun status401403429404405409504HaveSpecificSafeReadonlyMessages() {
        val cases = listOf(401 to ProviderModelDiscoveryFailure.KEY_OR_PERMISSION, 403 to ProviderModelDiscoveryFailure.KEY_OR_PERMISSION,
            429 to ProviderModelDiscoveryFailure.RATE_LIMITED, 404 to ProviderModelDiscoveryFailure.NO_CATALOG,
            405 to ProviderModelDiscoveryFailure.NO_CATALOG, 409 to ProviderModelDiscoveryFailure.STALE_SNAPSHOT,
            504 to ProviderModelDiscoveryFailure.TIMEOUT)
        for ((status, kind) in cases) {
            api.modelsStatus = status
            val temporary = request(key = ProviderApiFixture.API_KEY)
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(temporary) }
            assertEquals(status, error.httpCode)
            assertEquals(kind, error.failure)
            assertFalse(error.message!!.contains("Exception"))
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
            assertNull(error.cause)
            assertFalse(temporary.requestBody().containsKey("key"))
        }
        assertEquals(cases.size, api.modelRequests.get())
    }
    @Test fun staleEditRevisionDoesNotSilentlyRefreshOrCallWriteEndpoints() {
        val stale = request(id = "custom-api", revision = "a".repeat(64))
        api.revision = "b".repeat(64)
        val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(stale) }
        assertEquals(ProviderModelDiscoveryFailure.STALE_SNAPSHOT, error.failure)
        assertEquals(409, error.httpCode)
        assertEquals(1, api.modelRequests.get())
        assertTrue(api.requests.none { it.path.contains("editable") || it.path.endsWith("/edit") || it.path.endsWith("/create") })
    }
    @Test fun server5xxOrConnectionCloseNeverSurfaceWriteExecutionUnknown() {
        for (status in listOf(500, 502, 503)) {
            api.modelsStatus = status
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
            assertEquals(status, error.httpCode)
        }
        api.modelsHook = { exchange, _ -> exchange.close() }
        val temporary = request(key = ProviderApiFixture.API_KEY)
        val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(temporary) }
        assertEquals(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE, error.failure)
        assertNull(error.httpCode)
        assertFalse(temporary.requestBody().containsKey("key"))
        assertEquals(4, api.modelRequests.get())
    }
    @Test fun actualReadTimeoutClearsOnlyRequestAndDoesNotRetry() {
        val formKey = ProviderApiFixture.API_KEY
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        api.modelsHook = { exchange, _ ->
            try { release.await(3, TimeUnit.SECONDS); api.respond(exchange, 200, catalog()) } finally { done.countDown() }
        }
        val temporary = request(key = formKey)
        try {
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client(Duration.ofMillis(100)).discoverModels(temporary) }
            assertEquals(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE, error.failure)
            assertEquals(1, api.modelRequests.get())
            assertEquals(ProviderApiFixture.API_KEY, formKey)
            assertFalse(temporary.requestBody().containsKey("key"))
        } finally { release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS)) }
    }
    @Test fun readonlyRedirectsNeverFollowOrLeakManagerCookieOrKeyToAnotherPort() {
        ProviderApiFixture().use { target ->
            for (status in listOf(302, 303, 307, 308)) {
                api.modelsHook = { exchange, _ ->
                    exchange.responseHeaders.add("Location", target.baseUrl + "/api/mobile/providers/models")
                    api.respond(exchange, status, "redirect " + ProviderApiFixture.API_KEY)
                }
                val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request(key = ProviderApiFixture.API_KEY)) }
                assertEquals(status, error.httpCode)
            }
            assertTrue(target.requests.isEmpty())
            assertEquals(4, api.modelRequests.get())
        }
    }
    @Test fun boundedOversizeCatalogIsSafeReadonlyFailureAndNeverInventsModels() {
        api.modelsOverride = "x".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1)
        val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
        assertEquals(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE, error.failure)
        assertEquals(1, api.modelRequests.get())
        assertEquals(0, api.createRequests.get())
    }
    @Test fun rawModelCountOverLimitAndBadBaseUrlFailClosed() {
        api.modelsOverride = catalog(rows = JsonArray((1..1001).map { buildJsonObject { put("id", "m" + it) } }))
        assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
        api.modelsOverride = catalog("https://api.example?key=" + ProviderApiFixture.API_KEY)
        val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request(key = ProviderApiFixture.API_KEY)) }
        assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
    }
    @Test fun createAndDirtyUrlSaveUseNormalizedCompleteChatWhileNameOnlyPreservesOldProfile() {
        val created = ProviderCreateDraft("new-provider", "Name", "api.example/custom/v1", ProviderApiFixture.API_KEY, emptyList())
        assertEquals("https://api.example/custom/v1/chat/completions", created.chatUrl)
        val old = ProviderEditSnapshot("custom-api", "a".repeat(64), "Old", "https://api.example/chat/completions",
            emptyList(), false, ProviderKeyMetadata(true, false, true))
        assertFalse(ProviderEditDraft(old, name = "Name", chatUrl = old.chat).requestBody().containsKey("chat"))
        assertEquals(JsonPrimitive("https://api.example/v1/chat/completions"), ProviderEditDraft(old, chatUrl = "api.example/v1").requestBody()["chat"])
    }
    @Test fun fixedDnsTlsAndResponseHeadersProduceSafeSpecificMessages() {
        val cases = listOf(
            "provider_models_dns" to ProviderModelDiscoveryFailure.DNS_FAILURE,
            "provider_models_tls_certificate" to ProviderModelDiscoveryFailure.TLS_CERTIFICATE,
            "provider_models_invalid" to ProviderModelDiscoveryFailure.INVALID_RESPONSE,
            "provider_models_redirect_rejected" to ProviderModelDiscoveryFailure.REDIRECT_REJECTED,
        )
        for ((code, failure) in cases) {
            api.modelsHook = { exchange, _ ->
                exchange.responseHeaders.add("X-Magpie-Mobile-Error-Code", code)
                api.respond(exchange, 502, "private body " + ProviderApiFixture.API_KEY)
            }
            val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
            assertEquals(failure, error.failure)
            assertFalse(error.message!!.contains(ProviderApiFixture.API_KEY))
        }
    }
    @Test fun arbitraryErrorHeaderCannotLeakOrChangeClassification() {
        api.modelsHook = { exchange, _ ->
            exchange.responseHeaders.add("X-Magpie-Mobile-Error-Code", ProviderApiFixture.API_KEY)
            api.respond(exchange, 502, ProviderApiFixture.API_KEY)
        }
        val error = assertFailsWith<ProviderModelDiscoveryException> { api.client().discoverModels(request()) }
        assertEquals(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE, error.failure)
        assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
    }
}
