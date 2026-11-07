package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderManagementClientTest {
    private lateinit var api: ProviderApiFixture
    @BeforeEach fun start() { api = ProviderApiFixture() }
    @AfterEach fun stop() { api.close() }
    private fun draft(id: String = "new-provider", url: String = "https://api.example/v1/chat/completions", local: Boolean = false) =
        ProviderCreateDraft(id, "New provider", url, ProviderApiFixture.API_KEY, listOf("vendor/raw-id"), local)

    @Test fun listParsesRootObjectKeyMetadataModelObjectsAndRawChosenIds() {
        val list = api.client().list()
        val provider = list.providers.single()
        assertEquals("custom-api", provider.id)
        assertEquals("Custom API", provider.name)
        assertEquals("https://api.example/v1/chat/completions", provider.chat)
        assertTrue(provider.keySet && provider.key.known && provider.editable)
        assertEquals("••••••", provider.key.masked)
        assertEquals(listOf("vendor/raw-id"), provider.selectedModels)
        assertTrue(provider.models.any { it.id == "vendor/raw-id" && it.on })
        assertTrue(provider.models.any { it.id == "catalog-only" && !it.on })
        assertFalse(list.toString().contains(ProviderApiFixture.API_KEY))
        assertEquals("GET", api.requests.single().method)
        assertEquals(api.credential.cookieHeader(), api.requests.single().cookie)
        assertNull(api.requests.single().authorization)
        assertNull(api.requests.single().origin)
        assertNull(api.requests.single().query)
    }

    @Test fun listMissingKeyAndUnexpectedStringKeyDoNotExposeOrInventKnownMetadata() {
        for (key in listOf<JsonPrimitive?>(null, JsonPrimitive(ProviderApiFixture.API_KEY))) {
            val original = api.providerRow()
            val fields = original.toMutableMap().apply { if (key == null) remove("key") else put("key", key) }
            api.listOverride = JsonObject(mapOf("providers" to JsonArray(listOf(JsonObject(fields))))).toString()
            val provider = api.client().list().providers.single()
            assertFalse(provider.key.known || provider.keySet || provider.editable)
            assertEquals("", provider.key.masked)
            assertFalse(provider.toString().contains(ProviderApiFixture.API_KEY))
        }
    }

    @Test fun unknownSecretFieldsUnsafeUrlAndMetadataEchoAreNeverReturned() {
        val fields = api.providerRow().toMutableMap().apply {
            put("name", JsonPrimitive(ProviderApiFixture.API_KEY))
            put("chat", JsonPrimitive("https://api.example/chat?key=" + ProviderApiFixture.API_KEY))
            put("headers", JsonObject(mapOf("Authorization" to JsonPrimitive("Bearer " + ProviderApiFixture.API_KEY))))
            put("futureSecret", JsonPrimitive(ProviderApiFixture.API_KEY))
            put("anonKey", JsonPrimitive(ProviderApiFixture.API_KEY))
            put("models", JsonArray(listOf(buildJsonObject {
                put("id", "vendor/raw-id"); put("name", "Bearer " + ProviderApiFixture.API_KEY); put("on", true)
            })))
        }
        api.listOverride = buildJsonObject {
            put("providers", JsonArray(listOf(JsonObject(fields))))
            put("fileError", "untrusted " + ProviderApiFixture.API_KEY)
        }.toString()
        val list = api.client().list()
        val provider = list.providers.single()
        assertEquals("[已隐藏]", provider.name)
        assertEquals("", provider.chat)
        assertEquals("[已隐藏]", provider.models.single().name)
        assertTrue(list.hasFileError)
        assertFalse(list.toString().contains(ProviderApiFixture.API_KEY))
        assertFalse(mapOf("provider" to provider, "model" to provider.models.single(), "key" to provider.key).toString().contains(ProviderApiFixture.API_KEY))
    }

    @Test fun nullProviderSliceIsEmptyButMissingObjectOrStringModelIsProtocolFailure() {
        api.listOverride = """{"providers":null}"""
        assertTrue(api.client().getProviders().providers.isEmpty())
        for (bad in listOf("[]", "{}", """{"providers":[{"id":"custom-api","models":["raw-id"]}]}""")) {
            api.listOverride = bad
            assertFailsWith<ManagementProtocolException> { api.client().list() }
        }
    }

    @Test fun customMissingPresetIsCompatibleButUnknownAccountTypeRemainsNonEditable() {
        val withoutPreset = JsonObject(api.providerRow() - "preset")
        api.listOverride = JsonObject(mapOf("providers" to JsonArray(listOf(withoutPreset)))).toString()
        assertTrue(api.client().list().providers.single().editable)
        val unknownAccount = JsonObject(withoutPreset + ("account" to JsonArray(emptyList())))
        api.listOverride = JsonObject(mapOf("providers" to JsonArray(listOf(unknownAccount)))).toString()
        assertFalse(api.client().list().providers.single().editable)
    }

    @Test fun missingOrMismatchProviderCapabilitiesCannotPostOrLeakAndKeepPreflightKey() {
        val original = Json.parseToJsonElement(ProviderApiFixture.CAPABILITIES).jsonObject
        val variants = listOf(
            JsonObject(original - "providerSchemaVersion"), JsonObject(original - "providerManagement"),
            JsonObject(original + ("providerSchemaVersion" to JsonPrimitive(2))),
            JsonObject(original + ("providerManagement" to JsonPrimitive(false))),
            JsonObject(original + ("loopbackKeys" to JsonPrimitive(false))),
            JsonObject(original + ("debugEndpoints" to JsonPrimitive(true))),
            JsonObject(original + ("schemaVersion" to JsonPrimitive("1"))),
        )
        for (variant in variants) {
            api.capabilitiesBody = variant.toString()
            val draft = draft()
            val error = assertFailsWith<ManagementCapabilitiesMismatch> { api.client().create(draft) }
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
            assertEquals(JsonPrimitive(ProviderApiFixture.API_KEY), draft.requestBody()["key"])
        }
        assertEquals(0, api.createRequests.get())
        assertEquals(0, api.editRequests.get())
        assertTrue(api.requests.all { it.path == "/api/mobile/capabilities" })
    }

    @Test fun providerCapabilitiesAreReadOnlyAndCachedOncePerOwnerBoundClient() {
        val client = api.client()
        client.create(draft())
        val snapshot = client.editable("new-provider")
        client.edit(ProviderEditDraft(snapshot, name = "Renamed"))
        assertEquals(1, api.requests.count { it.path == "/api/mobile/capabilities" })
        assertEquals(1, api.createRequests.get())
        assertEquals(1, api.editRequests.get())
    }

    @Test fun observedFileErrorOrMalformedRefreshPreventsWriteUntilExplicitHealthyReload() {
        val client = api.client()
        api.listOverride = buildJsonObject { put("providers", JsonArray(emptyList())); put("fileError", "untrusted " + ProviderApiFixture.API_KEY) }.toString()
        assertTrue(client.list().hasFileError)
        assertFailsWith<ProviderConfigurationUnreadableException> { client.create(draft()) }
        api.listOverride = "broken " + ProviderApiFixture.API_KEY
        assertFailsWith<ManagementProtocolException> { client.list() }
        assertFailsWith<ProviderConfigurationUnreadableException> { client.create(draft()) }
        assertEquals(0, api.createRequests.get())
        api.listOverride = null
        assertFalse(client.list().hasFileError)
        assertEquals("new-provider", client.create(draft()).id)
    }

    @Test fun postServer5xxIsConservativelyUnknownWithoutReadingFreeErrorBody() {
        for (status in listOf(500, 502, 503)) {
            api.createHook = { exchange, _ -> api.respond(exchange, status, "provider_write_unknown " + ProviderApiFixture.API_KEY) }
            val error = assertFailsWith<ManagementExecutionUnknown> { api.client().create(draft()) }
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        }
        assertEquals(3, api.createRequests.get())
    }

    @Test fun oversizedUtf8RequestIsRejectedBeforeAnyNetworkAndPreflightKeyIsKept() {
        val models = (0..999).map { "m" + it + "😀".repeat(252) }
        val draft = ProviderCreateDraft("bulk", "Bulk", "https://api.example/chat", ProviderApiFixture.API_KEY, models)
        assertFailsWith<IllegalArgumentException> { api.client().create(draft) }
        assertTrue(api.requests.isEmpty())
        assertEquals(JsonPrimitive(ProviderApiFixture.API_KEY), draft.requestBody()["key"])
    }

    @Test fun builtinPresetOrAccountIsNotAdvertisedEditableAnd422IsFixedUnsupported() {
        for ((field, value) in listOf("preset" to JsonPrimitive("openai"), "account" to JsonObject(mapOf("token" to JsonPrimitive(ProviderApiFixture.API_KEY))),
            "responses" to JsonPrimitive("https://api.example/v1/responses"))) {
            val row = JsonObject(api.providerRow() + (field to value))
            api.listOverride = JsonObject(mapOf("providers" to JsonArray(listOf(row)))).toString()
            assertFalse(api.client().list().providers.single().editable)
        }
        api.editableStatus = 422
        val error = assertFailsWith<ProviderUnsupportedException> { api.client().editable("custom-api") }
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        assertEquals(0, api.editRequests.get())
    }

    @Test fun editableReturnsOnlyVersionedBasicSnapshotAndNeverRawKeyOrUnknownFields() {
        val snapshot = api.client().editable("custom-api")
        assertEquals("a".repeat(64), snapshot.revision)
        assertEquals(listOf("vendor/raw-id"), snapshot.models)
        assertTrue(snapshot.keySet)
        assertEquals("••••••", snapshot.key.masked)
        assertFalse(snapshot.toString().contains(ProviderApiFixture.API_KEY))
        assertEquals("/api/mobile/providers/editable/custom-api", api.requests.single().path)
    }

    @Test fun missingRevisionWrongIdKeyStringAndObjectModelsCannotBecomeEditableSnapshot() {
        val original = api.safeSnapshot()
        val variants = listOf(
            JsonObject(original - "revision"), JsonObject(original + ("revision" to JsonPrimitive(""))),
            JsonObject(original + ("id" to JsonPrimitive("different-id"))),
            JsonObject(original + ("key" to JsonPrimitive(ProviderApiFixture.API_KEY))),
            JsonObject(original + ("models" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("raw/id"))))))),
            JsonObject(original + ("models" to JsonNull)),
        )
        for (variant in variants) {
            api.snapshotOverride = variant.toString()
            val error = assertFailsWith<ManagementProtocolException> { api.client().editable("custom-api") }
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        }
        assertEquals(0, api.editRequests.get())
    }

    @Test fun createUsesOnlyNewTrueSevenAllowedFieldsCookieNoBearerAndClearsKey() {
        val draft = draft()
        val created = api.client().create(draft)
        assertEquals("new-provider", created.id)
        assertTrue(created.keySet)
        val post = api.requests.single { it.method == "POST" }
        val body = Json.parseToJsonElement(post.body).jsonObject
        assertEquals(setOf("new", "id", "name", "chat", "key", "models", "noModels"), body.keys)
        assertEquals(JsonPrimitive(true), body["new"])
        assertEquals(JsonArray(listOf(JsonPrimitive("vendor/raw-id"))), body["models"])
        assertEquals("/api/mobile/providers/create", post.path)
        assertEquals(post.body.toByteArray(Charsets.UTF_8).size.toString(), post.contentLength)
        assertNull(post.transferEncoding)
        assertNull(post.authorization)
        assertEquals(api.credential.cookieHeader(), post.cookie)
        assertFailsWith<IllegalArgumentException> { draft.requestBody() }
    }

    @Test fun postMetadataEchoOfSubmittedNonstandardKeyIsHiddenEvenWithoutKnownPrefix() {
        val secret = "unprefixed-credential-for-test"
        api.createHook = { exchange, request ->
            api.canonical = JsonObject(request - "new")
            val response = JsonObject(api.safeSnapshot() + ("name" to request["key"]!!))
            api.respond(exchange, 200, response.toString())
        }
        val draft = ProviderCreateDraft("new-provider", "Name", "https://api.example/chat", secret, emptyList())
        val snapshot = api.client().create(draft)
        assertEquals("[已隐藏]", snapshot.name)
        assertFalse(snapshot.toString().contains(secret))
        assertFailsWith<IllegalArgumentException> { draft.requestBody() }
    }

    @Test fun nameOnlyEditSendsDirtyFieldsAndRevisionNotWholeProviderJson() {
        val client = api.client()
        val snapshot = client.editable("custom-api")
        val edited = client.edit(ProviderEditDraft(snapshot, name = "Renamed", apiKey = "  "))
        assertEquals("Renamed", edited.name)
        assertEquals("b".repeat(64), edited.revision)
        val body = Json.parseToJsonElement(api.requests.last().body).jsonObject
        assertEquals(setOf("id", "revision", "name"), body.keys)
        assertFalse(body.containsKey("key") || body.containsKey("models") || body.containsKey("new"))
        assertEquals(JsonPrimitive("keep advanced settings"), api.canonical["futureSetting"])
        assertEquals(JsonPrimitive(ProviderApiFixture.API_KEY), api.canonical["key"])
        assertEquals(1, api.editRequests.get())
    }

    @Test fun explicitEditedEmptyModelsDisableWhileUntouchedModelsStayAutomaticAll() {
        api.canonical = JsonObject(api.canonical + mapOf("models" to JsonArray(emptyList()), "noModels" to JsonPrimitive(false)))
        val client = api.client()
        val snapshot = client.editable("custom-api")
        client.edit(ProviderEditDraft(snapshot, name = "Renamed", modelIds = null))
        assertEquals(JsonPrimitive(false), api.canonical["noModels"])
        assertFalse(Json.parseToJsonElement(api.requests.last().body).jsonObject.containsKey("models"))
        val fresh = client.editable("custom-api")
        client.edit(ProviderEditDraft(fresh, modelIds = emptyList()))
        assertEquals(JsonPrimitive(true), api.canonical["noModels"])
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(api.requests.last().body).jsonObject["models"])
    }

    @Test fun absentRevisionOrNoDirtyFieldsRefuseBeforeAnyPost() {
        val snapshot = api.client().editable("custom-api")
        assertFailsWith<ProviderEditPreconditionException> { api.client().edit(ProviderEditDraft(snapshot)) }
        val bad = ProviderEditSnapshot(snapshot.id, "", snapshot.name, snapshot.chat, snapshot.models, snapshot.noModels, snapshot.key)
        assertFailsWith<ProviderEditPreconditionException> { api.client().edit(ProviderEditDraft(bad, name = "Renamed")) }
        assertEquals(0, api.editRequests.get())
    }

    @Test fun create409ReportsAlreadyExistsNotRevisionConflictAndNeverOverwrites() {
        api.createHook = { exchange, _ -> api.respond(exchange, 409, "provider already exists " + ProviderApiFixture.API_KEY) }
        val before = api.canonical
        val draft = draft("custom-api")
        val error = assertFailsWith<ProviderAlreadyExistsException> { api.client().create(draft) }
        assertTrue(error.message!!.contains("已存在"))
        assertEquals(before, api.canonical)
        assertEquals(1, api.createRequests.get())
        assertFailsWith<IllegalArgumentException> { draft.requestBody() }
    }

    @Test fun stale409RequiresExplicitReloadAndNeverSilentOverwriteOrReplay() {
        val client = api.client()
        val snapshot = client.editable("custom-api")
        api.revision = "c".repeat(64)
        val draft = ProviderEditDraft(snapshot, name = "Renamed", apiKey = ProviderApiFixture.API_KEY)
        val error = assertFailsWith<ProviderRevisionConflictException> { client.edit(draft) }
        assertEquals(1, api.editRequests.get())
        assertEquals(JsonPrimitive("Custom API"), api.canonical["name"])
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        assertFalse(draft.requestBody().containsKey("key"))
        assertEquals("c".repeat(64), client.editable("custom-api").revision)
        assertEquals(1, api.editRequests.get())
    }

    @Test fun redirect302303307308DoesNotFollowReplayOrLeakCookieToAnotherPort() {
        ProviderApiFixture().use { destination ->
            for (status in listOf(302, 303, 307, 308)) {
                api.createHook = { exchange, _ ->
                    exchange.responseHeaders.add("Location", destination.baseUrl + "/api/mobile/providers/create")
                    api.respond(exchange, status, "untrusted " + ProviderApiFixture.API_KEY)
                }
                val error = assertFailsWith<ManagementHttpException> { api.client().create(draft("new-$status")) }
                assertEquals(status, error.httpCode)
            }
            assertEquals(4, api.createRequests.get())
            assertTrue(destination.requests.isEmpty())
        }
    }

    @Test fun networkCloseAfterPostBodyIsUnknownAndSameDraftCannotReplay() {
        api.createHook = { exchange, _ -> exchange.close() }
        val draft = draft()
        val client = api.client()
        val error = assertFailsWith<ManagementExecutionUnknown> { client.create(draft) }
        assertNull(error.cause)
        assertFailsWith<IllegalStateException> { client.create(draft) }
        assertEquals(1, api.createRequests.get())
    }

    @Test fun editTimeoutAfterCommitIsUnknownClearsKeyAndNeedsReadonlyReload() {
        val client = api.client(Duration.ofMillis(100))
        val snapshot = client.editable("custom-api")
        val draft = ProviderEditDraft(snapshot, name = "Committed", apiKey = ProviderApiFixture.API_KEY)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        api.editHook = { exchange, request ->
            api.commitEdit(request)
            try { release.await(3, TimeUnit.SECONDS); api.respond(exchange, 200, api.safeSnapshot().toString()) }
            finally { finished.countDown() }
        }
        try {
            assertFailsWith<ManagementExecutionUnknown> { client.edit(draft) }
            assertEquals(1, api.editRequests.get())
            assertFailsWith<ProviderEditPreconditionException> { client.edit(draft) }
            assertFalse(draft.requestBody().containsKey("key"))
        } finally { release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)) }
        assertEquals("Committed", api.client().editable("custom-api").name)
        assertEquals(1, api.editRequests.get())
    }

    @Test fun non2xxAndMalformedResponseNeverExposeErrorBodyOrSerializationCause() {
        api.createHook = { exchange, _ -> api.respond(exchange, 401, "error " + ProviderApiFixture.API_KEY) }
        val http = assertFailsWith<ManagementHttpException> { api.client().create(draft()) }
        assertEquals("管理请求失败：HTTP 401", http.message)
        assertNull(http.cause)
        api.createHook = { exchange, _ -> api.respond(exchange, 200, "not-json " + ProviderApiFixture.API_KEY) }
        val unknown = assertFailsWith<ManagementExecutionUnknown> { api.client().create(draft()) }
        assertNull(unknown.cause)
        assertFalse(unknown.stackTraceToString().contains(ProviderApiFixture.API_KEY))
        assertEquals(2, api.createRequests.get())
    }

    @Test fun oversizedReadsAndWritesAreBoundedAndUnknownPostIsNotRetried() {
        api.listOverride = "x".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1)
        assertFailsWith<ManagementProtocolException> { api.client().list() }
        api.createHook = { exchange, _ -> api.respond(exchange, 200, "x".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1)) }
        assertFailsWith<ManagementExecutionUnknown> { api.client().create(draft()) }
        assertEquals(1, api.createRequests.get())
    }

    @Test fun wrongCookiePortGatewayAndForgedLoopbackBaseFailWithoutCredentialLeak() {
        assertFailsWith<IllegalArgumentException> { ProviderManagementClient(ManagerCredential.fromKey(ProviderApiFixture.MANAGER_KEY, 3430), api.baseUrl) }
        for (url in listOf("http://evil@127.0.0.1:3430", "http://127.0.0.1.evil:3430", "http://localhost:3430", "http://127.0.0.1:3430/?key=x")) {
            assertFailsWith<IllegalArgumentException> { ProviderManagementClient(api.credential, url) }
        }
        assertFailsWith<IllegalArgumentException> { ProviderManagementClient(ManagerCredential.fromKey(ProviderApiFixture.MANAGER_KEY, 3425), "http://127.0.0.1:3425") }
        val wrong = ManagerCredential.fromKey("f".repeat(32), api.port)
        val error = assertFailsWith<ManagementHttpException> { ProviderManagementClient(wrong, api.baseUrl).list() }
        assertEquals(401, error.httpCode)
        assertFalse(error.stackTraceToString().contains(ProviderApiFixture.API_KEY))
    }

    @Test fun managementNeverTestsDetectsOrCallsRealProviderEvenWhenLoopbackUrlIsSupplied() {
        val remoteCalls = AtomicInteger()
        val remote = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        remote.createContext("/") { exchange -> remoteCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        remote.start()
        try {
            val client = api.client()
            client.list(); client.editable("custom-api")
            client.create(draft(url = "http://127.0.0.1:" + remote.address.port + "/v1/chat/completions", local = true))
            assertEquals(0, remoteCalls.get())
            assertTrue(api.requests.all { it.path == "/api/providers" || it.path == "/api/mobile/capabilities" || it.path.startsWith("/api/mobile/providers/") })
            assertTrue(api.requests.none { it.path.contains("test") || it.path.contains("detect") || it.path == "/api/provider/save" })
        } finally { remote.stop(0) }
    }
}

internal class ProviderApiFixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "provider-test").apply { isDaemon = true } }
    val port: Int = server.address.port
    val baseUrl = "http://127.0.0.1:$port"
    val credential = ManagerCredential.fromKey(MANAGER_KEY, port)
    val requests = CopyOnWriteArrayList<ProviderRecordedRequest>()
    val createRequests = AtomicInteger()
    val editRequests = AtomicInteger()
    val modelRequests = AtomicInteger()
    @Volatile var modelsStatus: Int = 200
    @Volatile var modelsOverride: String? = null
    @Volatile var modelsHook: ((HttpExchange, JsonObject) -> Unit)? = null
    @Volatile var revision = "a".repeat(64)
    @Volatile var capabilitiesBody: String = CAPABILITIES
    @Volatile var listOverride: String? = null
    @Volatile var snapshotOverride: String? = null
    @Volatile var editableStatus: Int = 200
    @Volatile var createHook: ((HttpExchange, JsonObject) -> Unit)? = null
    @Volatile var editHook: ((HttpExchange, JsonObject) -> Unit)? = null
    @Volatile var canonical: JsonObject = buildJsonObject {
        put("id", "custom-api"); put("name", "Custom API"); put("chat", "https://api.example/v1/chat/completions")
        put("key", API_KEY); put("models", JsonArray(listOf(JsonPrimitive("vendor/raw-id")))); put("noModels", false)
        put("futureSetting", "keep advanced settings")
    }
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests.add(ProviderRecordedRequest(exchange.requestMethod, exchange.requestURI.path, body,
                    exchange.requestHeaders.getFirst("Cookie"), exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Origin"), exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Content-Length"), exchange.requestHeaders.getFirst("Transfer-Encoding")))
                if (exchange.requestHeaders.getFirst("Cookie") != credential.cookieHeader()) respond(exchange, 401, "error " + API_KEY)
                else if (exchange.requestHeaders.getFirst("Authorization") != null || exchange.requestHeaders.getFirst("Origin") != null) respond(exchange, 403, "native management only")
                else when {
                    exchange.requestMethod == "GET" && exchange.requestURI.path == "/api/mobile/capabilities" -> respond(exchange, 200, capabilitiesBody)
                    exchange.requestMethod == "GET" && exchange.requestURI.path == "/api/providers" ->
                        respond(exchange, 200, listOverride ?: JsonObject(mapOf("providers" to JsonArray(listOf(providerRow())))).toString())
                    exchange.requestMethod == "GET" && exchange.requestURI.path.startsWith("/api/mobile/providers/editable/") ->
                        respond(exchange, editableStatus, if (editableStatus == 200) snapshotOverride ?: safeSnapshot().toString() else "untrusted " + API_KEY)
                    exchange.requestMethod == "POST" && exchange.requestURI.path == "/api/mobile/providers/models" -> {
                        modelRequests.incrementAndGet()
                        val request = Json.parseToJsonElement(body).jsonObject
                        val hook = modelsHook
                        val suppliedId = request["id"]
                        if (hook != null) hook(exchange, request)
                        else if (suppliedId != null && (suppliedId != canonical["id"] || request["revision"] != JsonPrimitive(revision))) respond(exchange, 409, "stale " + API_KEY)
                        else if (modelsStatus != 200) respond(exchange, modelsStatus, "untrusted model error " + API_KEY)
                        else respond(exchange, 200, modelsOverride ?: buildJsonObject {
                            put("baseUrl", request["chat"]!!.jsonPrimitive.content.removeSuffix("/chat/completions").trimEnd('/'))
                            put("models", JsonArray(listOf(buildJsonObject { put("id", "catalog-model"); put("name", "Catalog Model") })))
                        }.toString())
                    }
                    exchange.requestMethod == "POST" && exchange.requestURI.path == "/api/mobile/providers/create" -> {
                        createRequests.incrementAndGet()
                        val request = Json.parseToJsonElement(body).jsonObject
                        val hook = createHook
                        if (hook != null) hook(exchange, request) else {
                            if (request["new"] != JsonPrimitive(true) || request.keys != setOf("new", "id", "name", "chat", "key", "models", "noModels")) respond(exchange, 400, "invalid " + API_KEY)
                            else { canonical = JsonObject(request - "new"); revision = "b".repeat(64); respond(exchange, 200, safeSnapshot().toString()) }
                        }
                    }
                    exchange.requestMethod == "POST" && exchange.requestURI.path == "/api/mobile/providers/edit" -> {
                        editRequests.incrementAndGet()
                        val request = Json.parseToJsonElement(body).jsonObject
                        val hook = editHook
                        if (hook != null) hook(exchange, request) else if (request["revision"] != JsonPrimitive(revision)) respond(exchange, 409, "revision conflict " + API_KEY)
                        else { commitEdit(request); respond(exchange, 200, safeSnapshot().toString()) }
                    }
                    else -> respond(exchange, 404, "unsupported management path")
                }
            } catch (_: Exception) { exchange.close() }
        }
        server.start()
    }
    fun client(timeout: Duration = Duration.ofSeconds(2)) = ProviderManagementClient(credential, baseUrl, timeout)
    fun safeSnapshot(): JsonObject = buildJsonObject {
        put("id", canonical["id"]!!); put("revision", revision); put("name", canonical["name"]!!)
        put("chat", canonical["chat"]!!); put("models", canonical["models"]!!); put("noModels", canonical["noModels"]!!)
        put("key", buildJsonObject { put("set", true); put("masked", API_KEY); put("optional", false) })
    }
    fun providerRow(): JsonObject = buildJsonObject {
        put("id", canonical["id"]!!); put("name", canonical["name"]!!); put("chat", canonical["chat"]!!)
        put("preset", ""); put("ready", true); put("off", false); put("noModels", canonical["noModels"]!!)
        put("key", buildJsonObject { put("set", true); put("masked", API_KEY); put("optional", false) })
        val chosen = canonical["models"] as JsonArray
        put("chosen", chosen)
        put("models", buildJsonArray {
            (chosen.map { it.jsonPrimitive.content } + "catalog-only").distinct().forEach { id ->
                add(buildJsonObject { put("id", id); put("name", "Display " + id); put("on", chosen.any { it.jsonPrimitive.content == id }) })
            }
        })
        put("headers", buildJsonObject { put("Authorization", "Bearer " + API_KEY) })
        put("futureSecret", API_KEY)
    }
    fun commitEdit(request: JsonObject) {
        canonical = JsonObject(canonical + request.filterKeys { it != "revision" && it != "id" })
        revision = "b".repeat(64)
    }
    fun respond(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
    companion object {
        const val MANAGER_KEY = "0123456789abcdef0123456789abcdef"
        const val API_KEY = "sk-provider-regression-only-secret"
        const val CAPABILITIES = """{"schemaVersion":1,"coreVersion":"mobile-provider-v1","mobile":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false,"providerSchemaVersion":1,"providerManagement":true,"providerModelDiscovery":true}"""
    }
}
internal data class ProviderRecordedRequest(
    val method: String, val path: String, val body: String, val cookie: String?, val authorization: String?,
    val origin: String?, val query: String?, val contentLength: String?, val transferEncoding: String?,
) { override fun toString(): String = "ProviderRecordedRequest(credentials=<redacted>)" }
