package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreManagementClientTest {
    private lateinit var api: ManagementApiFixture
    @BeforeEach fun start() { api = ManagementApiFixture() }
    @AfterEach fun stop() { api.close() }

    @Test fun capabilitiesUsesDirectPortCookieWithoutOriginOrBootstrap() {
        val capabilities = api.client().capabilities()
        assertEquals(1, capabilities.schemaVersion)
        assertTrue(capabilities.mobile && capabilities.loopbackKeys && capabilities.adminLoopbackOnly)
        assertFalse(capabilities.debugEndpoints || capabilities.nativeAutoUpdate)
        val request = api.requests.single()
        assertEquals("/api/mobile/capabilities", request.path)
        assertEquals(api.credential.cookieHeader(), request.cookie)
        assertNull(request.origin)
        assertNull(request.fetchSite)
    }

    @Test fun wrongManagerValueIs401AndDoesNotExposeFreeErrorBody() {
        val wrong = ManagerCredential.fromKey("f".repeat(32), api.port)
        val error = assertFailsWith<ManagementHttpException> { CoreManagementClient(wrong, api.baseUrl).listCallerKeys() }
        assertEquals(401, error.httpCode)
        assertEquals("管理请求失败：HTTP 401", error.message)
        assertNull(error.cause)
        assertFalse(error.toString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun cookiePortMismatchAndGatewayPortAreRejectedBeforeRequest() {
        assertFailsWith<IllegalArgumentException> { CoreManagementClient(ManagerCredential.fromKey(ManagementApiFixture.MANAGER_KEY, 3430), api.baseUrl) }
        assertFailsWith<IllegalArgumentException> { CoreManagementClient(ManagerCredential.fromKey(ManagementApiFixture.MANAGER_KEY, 3425), "http://127.0.0.1:3425") }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun forgedHostUserinfoQueryFragmentAndNonLoopbackAreRejected() {
        val badUrls = listOf(
            "http://127.0.0.1:${api.port}@evil.invalid:80",
            "http://evil.invalid@127.0.0.1:${api.port}",
            "http://127.0.0.1.evil.invalid:${api.port}",
            "http://localhost:${api.port}", "http://127.1:${api.port}",
            "http://2130706433:${api.port}", "http://192.168.1.1:${api.port}",
            "http://127.0.0.1:${api.port}/api", "http://127.0.0.1:${api.port}/?k=hidden",
            "http://127.0.0.1:${api.port}/#fragment", "https://127.0.0.1:${api.port}",
            "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:03430",
            "http://%31%32%37.0.0.1:${api.port}",
        )
        for (url in badUrls) assertFailsWith<IllegalArgumentException> { CoreManagementClient(api.credential, url) }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun preciseIpv6LoopbackIsAllowedAndTimeoutIsBounded() {
        assertEquals(3430, LoopbackEndpoint("http://[::1]:3430/").port)
        for (timeout in listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertFailsWith<IllegalArgumentException> { api.client(timeout) }
        }
    }

    @Test fun realListOmitsSecretAndParsesRestrictionsAndExpiry() {
        api.keys = listOf(RegisteredGatewayKey("real-id", "read only", ManagementApiFixture.SECRET,
            off = true, readOnly = true, browser = true, expires = "2020-01-01T00:00:00Z"))
        val key = api.client().listCallerKeys().single()
        assertEquals("real-id", key.id)
        assertEquals("", key.secret)
        assertTrue(key.off && key.browser && key.readOnly)
        assertEquals("2020-01-01T00:00:00Z", key.expires)
        assertFalse(api.listBody().contains(ManagementApiFixture.SECRET))
    }

    @Test fun coreGenerated48HexUsesNewIdDifferenceNotMaskedOrOldSameName() {
        val name = "custom duplicate name"
        api.keys = listOf(RegisteredGatewayKey("old-id", name, "sk-magpie-key-" + "b".repeat(48)))
        val key = api.client().createCallerKey(name)
        assertEquals("000000000000000000000001", key.id)
        assertEquals(ManagementApiFixture.SECRET, key.secret)
        assertEquals(48, key.secret.removePrefix("sk-magpie-key-").length)
        assertEquals(1, api.addRequests.get())
        assertEquals(1, api.listRequests.get())
        val post = api.requests.single { it.path.endsWith("add-key") }
        assertEquals("POST", post.method)
        assertEquals(setOf("name"), Json.parseToJsonElement(post.body).jsonObject.keys)
        assertEquals(post.body.toByteArray(Charsets.UTF_8).size.toString(), post.contentLength)
        assertNull(post.transferEncoding)
        assertNull(post.origin)
    }

    @Test fun noticesReturnsLicenseText() {
        val text = api.client().notices()
        assertTrue(text.contains("MIT License"))
        val request = api.requests.single { it.path == "/api/notices" }
        assertEquals("GET", request.method)
        assertEquals(api.credential.cookieHeader(), request.cookie)
    }

    @Test fun callerKeyModelsParsesCatalogMetadata() {
        val models = api.client().callerKeyModels()
        assertEquals(3, models.size)
        assertEquals("group/zero", models[0].id)
        assertTrue(models[0].group)
        assertEquals("openai/gpt-4o", models[1].id)
        assertEquals("OpenAI", models[1].providerName)
        assertFalse(models[1].group)
        assertEquals("anthropic/claude", models[2].id)
        assertTrue(models[2].account)
        val request = api.requests.single { it.path == "/api/caller-keys/models" }
        assertEquals("GET", request.method)
        assertEquals(api.credential.cookieHeader(), request.cookie)
    }

    @Test fun recentRequestsReturnsParsedList() {
        val list = api.client().recentRequests(5)
        assertEquals(1, list.size)
        assertEquals("req-1", list[0].id)
        assertEquals("gpt-4o", list[0].model)
        assertEquals(200, list[0].statusCode)
        assertEquals(150L, list[0].durationMs)
        assertEquals(42, list[0].totalTokens)
    }

    @Test fun copyKeyUsesIdAndTopLevelSecretWithoutRotating() {
        api.keys = listOf(RegisteredGatewayKey("owned-id", GatewayKeyProvisioner.RESERVED_NAME, ManagementApiFixture.SECRET))
        val key = api.client().copyCallerKey("owned-id")
        assertEquals("owned-id", key.id)
        assertEquals(ManagementApiFixture.SECRET, key.secret)
        assertEquals(1, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
        val post = api.requests.single()
        assertEquals(setOf("key"), Json.parseToJsonElement(post.body).jsonObject.keys)
        assertEquals("owned-id", Json.parseToJsonElement(post.body).jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test fun ambiguousNewIdsAreUnknownAndAddIsNotRepeated() {
        api.addHandler = { exchange ->
            api.keys = listOf(
                RegisteredGatewayKey("new-1", GatewayKeyProvisioner.RESERVED_NAME),
                RegisteredGatewayKey("new-2", GatewayKeyProvisioner.RESERVED_NAME),
            )
            api.respond(exchange, 200, api.listBody(ManagementApiFixture.SECRET))
        }
        val error = assertFailsWith<ManagementExecutionUnknown> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
        assertEquals(1, api.addRequests.get())
        assertNull(error.cause)
        assertFalse(error.toString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun existingIdOnlyOrWrongNameAfterPostIsUnknown() {
        api.keys = listOf(RegisteredGatewayKey("existing", "other"))
        api.addHandler = { api.respond(it, 200, api.listBody(ManagementApiFixture.SECRET)) }
        assertFailsWith<ManagementExecutionUnknown> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
        assertEquals(1, api.addRequests.get())
    }

    @Test fun postRedirects302303307308NeverFollowOrResendOrSendCookieToTarget() {
        ManagementApiFixture().use { destination ->
            for (code in listOf(302, 303, 307, 308)) {
                api.addHandler = { exchange ->
                    exchange.responseHeaders.add("Location", destination.baseUrl + "/api/caller-keys/add-key")
                    api.respond(exchange, code, "untrusted redirect " + ManagementApiFixture.SECRET)
                }
                val error = assertFailsWith<ManagementHttpException> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
                assertEquals(code, error.httpCode)
            }
            assertEquals(4, api.addRequests.get())
            assertTrue(destination.requests.isEmpty())
        }
    }

    @Test fun post401429And500AreFixedStatusOnlyAndNeverReplayed() {
        for (code in listOf(401, 429, 500)) {
            api.addHandler = { api.respond(it, code, "server leaked " + ManagementApiFixture.SECRET) }
            val error = assertFailsWith<ManagementHttpException> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
            assertEquals("管理请求失败：HTTP $code", error.message)
            assertNull(error.cause)
        }
        assertEquals(3, api.addRequests.get())
    }

    @Test fun postTimeoutAfterCoreCommitIsUnknownWithoutImplicitJdkReplay() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        api.addHandler = { exchange ->
            api.keys = listOf(RegisteredGatewayKey("committed", GatewayKeyProvisioner.RESERVED_NAME, ManagementApiFixture.SECRET))
            try {
                release.await(3, TimeUnit.SECONDS)
                api.respond(exchange, 200, api.listBody(ManagementApiFixture.SECRET))
            } finally { finished.countDown() }
        }
        try {
            val error = assertFailsWith<ManagementExecutionUnknown> {
                api.client(Duration.ofMillis(100)).createCallerKey(GatewayKeyProvisioner.RESERVED_NAME)
            }
            assertEquals(1, api.addRequests.get())
            assertEquals("committed", api.keys.single().id)
            assertNull(error.cause)
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun postConnectionCloseAfterBodyDoesNotImplicitlyResend() {
        api.addHandler = { it.close() }
        assertFailsWith<ManagementExecutionUnknown> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
        assertEquals(1, api.addRequests.get())
    }

    @Test fun oversizedDeclaredAndChunkedReadsAreBoundedAndSanitized() {
        api.listHandler = { api.respond(it, 200, "x".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1)) }
        assertFailsWith<ManagementProtocolException> { api.client().listCallerKeys() }
        api.listHandler = { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(ByteArray(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1) { 120 }) }
        }
        assertFailsWith<ManagementProtocolException> { api.client().listCallerKeys() }
    }

    @Test fun oversizedPostSuccessIsUnknownAndNeverReissued() {
        api.addHandler = { api.respond(it, 200, "x".repeat(BoundedCoreHttp.MAX_RESPONSE_BYTES + 1)) }
        assertFailsWith<ManagementExecutionUnknown> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
        assertEquals(1, api.addRequests.get())
    }

    @Test fun malformedResponseCannotLeakSecretViaSerializationCause() {
        api.listHandler = { api.respond(it, 200, "invalid JSON " + ManagementApiFixture.SECRET) }
        val error = assertFailsWith<ManagementProtocolException> { api.client().listCallerKeys() }
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun postBadSuccessResponseIsUnknownNotSafeToRetry() {
        api.addHandler = { api.respond(it, 200, "invalid JSON " + ManagementApiFixture.SECRET) }
        val error = assertFailsWith<ManagementExecutionUnknown> { api.client().createCallerKey(GatewayKeyProvisioner.RESERVED_NAME) }
        assertEquals(1, api.addRequests.get())
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun registeredKeyToStringHidesEverySecretCharacterEvenInCollections() {
        val key = RegisteredGatewayKey("id", "name", ManagementApiFixture.SECRET)
        assertEquals("RegisteredGatewayKey(secret=<redacted>)", key.toString())
        assertFalse(listOf(key).toString().contains("sk-magpie-key-"))
        assertFalse(mapOf("key" to key).toString().contains(ManagementApiFixture.SECRET.takeLast(6)))
    }
}

/** Truthful GUI fixture: access.List() removes secret from every row, including POST rows.
 * Only copy-key/add-key top-level secret is full. Every API is behind direct webGuard cookie.
 */
internal class ManagementApiFixture : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "management-test").apply { isDaemon = true } }
    val port: Int = server.address.port
    val baseUrl = "http://127.0.0.1:$port"
    val credential = ManagerCredential.fromKey(MANAGER_KEY, port)
    val requests = CopyOnWriteArrayList<ManagementRecordedRequest>()
    val addRequests = AtomicInteger()
    val copyRequests = AtomicInteger()
    val listRequests = AtomicInteger()
    @Volatile var keys: List<RegisteredGatewayKey> = emptyList()
    @Volatile var capabilitiesBody = CAPABILITIES
    @Volatile var addHandler: ((HttpExchange) -> Unit)? = null
    @Volatile var copyHandler: ((HttpExchange) -> Unit)? = null
    @Volatile var listHandler: ((HttpExchange) -> Unit)? = null

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                requests.add(ManagementRecordedRequest(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Cookie"), exchange.requestHeaders.getFirst("Origin"),
                    exchange.requestHeaders.getFirst("Sec-Fetch-Site"), body,
                    exchange.requestHeaders.getFirst("Content-Length"), exchange.requestHeaders.getFirst("Transfer-Encoding")))
                if (exchange.requestHeaders.getFirst("Cookie") != credential.cookieHeader()) {
                    respond(exchange, 401, "untrusted auth error " + SECRET)
                } else if (exchange.requestHeaders.getFirst("Origin") != null ||
                    exchange.requestHeaders.getFirst("Sec-Fetch-Site") != null) {
                    respond(exchange, 403, "native request should not spoof browser origin")
                } else when (exchange.requestMethod + " " + exchange.requestURI.path) {
                    "GET /api/mobile/capabilities" -> respond(exchange, 200, capabilitiesBody)
                    "GET /api/state" -> respond(exchange, 200, """{"anything":{"nested":[1,2,3]},"version":7}""")
                    "GET /api/usage/requests" -> respond(exchange, 200, """{"requests":[{"id":"req-1","model":"gpt-4o","status":200,"duration":150,"total_tokens":42}]}""")
                    "GET /api/notices" -> respond(exchange, 200, """{"text":"Magpie is MIT License. Bundled third-party components: MIT License, Apache-2.0."}""")
                    "GET /api/caller-keys/models" -> respond(exchange, 200, """{"models":[
                        {"id":"group/zero","name":"Zero 组","group":true},
                        {"id":"openai/gpt-4o","name":"GPT-4o","provider":"openai","providerName":"OpenAI"},
                        {"id":"anthropic/claude","name":"Claude","provider":"anthropic","providerName":"Anthropic","account":true,"plan":"pro"}
                    ]}""")
                    "GET /api/caller-keys" -> {
                        listRequests.incrementAndGet()
                        val handler = listHandler
                        if (handler != null) handler(exchange) else respond(exchange, 200, listBody())
                    }
                    "POST /api/caller-keys/add-key" -> {
                        val sequence = addRequests.incrementAndGet()
                        val handler = addHandler
                        if (handler != null) handler(exchange) else {
                            val name = Json.parseToJsonElement(body).jsonObject["name"]!!.jsonPrimitive.content
                            val id = sequence.toString(16).padStart(24, '0')
                            keys = keys + RegisteredGatewayKey(id, name, SECRET)
                            respond(exchange, 200, listBody(SECRET))
                        }
                    }
                    "POST /api/caller-keys/copy-key" -> {
                        copyRequests.incrementAndGet()
                        val handler = copyHandler
                        if (handler != null) handler(exchange) else {
                            val id = Json.parseToJsonElement(body).jsonObject["key"]!!.jsonPrimitive.content
                            val key = keys.singleOrNull { it.id == id }
                            if (key == null) respond(exchange, 404, "key not found") else respond(exchange, 200, listBody(key.secret))
                        }
                    }
                    else -> respond(exchange, 404, "GUI has no hello")
                }
            } catch (_: Exception) { exchange.close() }
        }
        server.start()
    }

    fun client(timeout: Duration = Duration.ofSeconds(2)) = CoreManagementClient(credential, baseUrl, timeout)
    fun listBody(secret: String? = null): String = buildJsonObject {
        put("keys", buildJsonArray {
            keys.forEach { key -> add(buildJsonObject {
                put("id", key.id); put("name", key.name)
                put("masked", "sk-magpie-key-…" + key.secret.takeLast(6))
                if (key.off) put("off", true)
                if (key.readOnly) put("readOnly", true)
                if (key.browser) put("browser", true)
                key.expires?.let { put("expires", it) }
            }) }
        })
        put("accountNames", JsonObject(emptyMap()))
        if (secret != null) put("secret", secret)
    }.toString()

    fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
    companion object {
        const val MANAGER_KEY = "0123456789abcdef0123456789abcdef"
        val SECRET = "sk-magpie-key-" + "a".repeat(48)
        const val CAPABILITIES = """{"schemaVersion":1,"coreVersion":"0.1.3188-mobile","mobile":true,"loopbackKeys":true,"debugEndpoints":false,"adminLoopbackOnly":true,"nativeAutoUpdate":false}"""
    }
}

internal data class ManagementRecordedRequest(
    val method: String, val path: String, val cookie: String?, val origin: String?, val fetchSite: String?,
    val body: String, val contentLength: String?, val transferEncoding: String?,
)
