package cc.fplj.magpiemobile.core

import java.time.Duration
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Management only. No gateway Bearer, provider/test, detect, preset/save or inference calls.
 * This client projects whitelisted metadata; it never returns or logs providerJSON/raw JSON.
 * POSTs reuse the bounded, fixed-length, no-redirect/no-replay management transport.
 */
class ProviderManagementClient(
    credential: ManagerCredential,
    baseUrl: String = "http://127.0.0.1:3430",
    timeout: Duration = Duration.ofSeconds(5),
) {
    private val endpoint = LoopbackEndpoint(baseUrl)
    private val transport: BoundedCoreHttp
    @Volatile private var providerCapabilitiesVerified = false
    @Volatile private var modelDiscoverySupported = false
    @Volatile private var observedFileError = false
    init {
        require(endpoint.port != 3425) { "管理 cookie 不得发送到网关" }
        require(endpoint.port == credential.port) { "管理 cookie 端口不匹配" }
        transport = BoundedCoreHttp(endpoint, timeout, credential.cookieHeader())
    }

    fun list(): ProviderListSnapshot = getProviders()
    fun getProviders(): ProviderListSnapshot {
        observedFileError = true // a failed/malformed refresh cannot authorize a stale write
        val body = request("GET", "/api/providers")
        return readSafe { parseList(body) }.also { observedFileError = it.hasFileError }
    }

    /** Canonical, versioned snapshot, supported only for custom API-key OpenAI Chat. */
    fun editable(id: String): ProviderEditSnapshot {
        require(validProviderExistingId(id)) { "提供商标识格式无效" }
        val body = request("GET", "/api/mobile/providers/editable/$id")
        return readSafe { parseEditable(body).also { if (it.id != id) throw ManagementProtocolException() } }
    }

    fun create(draft: ProviderCreateDraft): ProviderEditSnapshot {
        var writeAttempted = false
        try {
            if (observedFileError) throw ProviderConfigurationUnreadableException()
            draft.validate() // state/replay before pure preview and non-writing capability requests
            require(draft.requestBody().toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "提供商请求超过安全上限" }
            ensureProviderCapabilities()
            val requestBody = draft.submissionBody() // consume only after non-writing validation
            val submittedKey = (requestBody["key"] as? JsonPrimitive)?.content
            val body = requestBody.toString().toByteArray(Charsets.UTF_8)
            writeAttempted = true
            val reply = request("POST", "/api/mobile/providers/create", body)
            return writeSafe { parseEditable(reply, listOfNotNull(submittedKey)).also { if (it.id != draft.id || !it.keySet) throw ManagementProtocolException() } }
        } finally { if (writeAttempted) draft.clearSecret() }
    }

    fun edit(draft: ProviderEditDraft): ProviderEditSnapshot {
        var writeAttempted = false
        try {
            if (observedFileError) throw ProviderConfigurationUnreadableException()
            draft.validate() // state/replay before pure preview and non-writing capability requests
            require(draft.requestBody().toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "提供商请求超过安全上限" }
            ensureProviderCapabilities()
            val requestBody = draft.submissionBody()
            val submittedKey = (requestBody["key"] as? JsonPrimitive)?.content
            val body = requestBody.toString().toByteArray(Charsets.UTF_8)
            writeAttempted = true
            val reply = request("POST", "/api/mobile/providers/edit", body)
            return writeSafe { parseEditable(reply, listOfNotNull(submittedKey)).also { if (it.id != draft.snapshot.id) throw ManagementProtocolException() } }
        } finally { if (writeAttempted) draft.clearSecret() }
    }

    /** Read-only RPC: the core makes GET models, never a vendor POST or a config write.
     * No retries. A read failure is a discovery error, not an unknown write/recovery event.
     */
    fun discoverModels(discovery: ProviderModelDiscoveryRequest): ProviderModelCatalog = try {
        val requestBody = discovery.requestBody()
        val submittedKey = (requestBody["key"] as? JsonPrimitive)?.content
        val body = requestBody.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_REQUEST_BYTES) { "模型目录请求超过安全上限" }
        ensureProviderCapabilities()
        if (!modelDiscoverySupported) throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.UNSUPPORTED)
        val reply = transport.request("POST", "/api/mobile/providers/models", body)
        try { parseCatalog(reply, discovery, listOfNotNull(submittedKey)) } catch (_: Exception) {
            throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.INVALID_RESPONSE)
        }
    } catch (error: ProviderModelDiscoveryException) {
        throw error
    } catch (error: ManagementHttpException) {
        val specific = when (error.safeErrorCode) {
            "provider_models_dns" -> ProviderModelDiscoveryFailure.DNS_FAILURE
            "provider_models_tls_certificate" -> ProviderModelDiscoveryFailure.TLS_CERTIFICATE
            "provider_models_invalid" -> ProviderModelDiscoveryFailure.INVALID_RESPONSE
            "provider_models_too_large" -> ProviderModelDiscoveryFailure.RESPONSE_TOO_LARGE
            "provider_models_sensitive_content" -> ProviderModelDiscoveryFailure.SENSITIVE_CONTENT
            "provider_models_redirect_rejected" -> ProviderModelDiscoveryFailure.REDIRECT_REJECTED
            else -> null
        }
        val kind = specific ?: when (error.httpCode) {
            401, 403 -> ProviderModelDiscoveryFailure.KEY_OR_PERMISSION
            404, 405 -> ProviderModelDiscoveryFailure.NO_CATALOG
            409 -> ProviderModelDiscoveryFailure.STALE_SNAPSHOT
            422 -> ProviderModelDiscoveryFailure.UNSUPPORTED
            429 -> ProviderModelDiscoveryFailure.RATE_LIMITED
            504 -> ProviderModelDiscoveryFailure.TIMEOUT
            400 -> ProviderModelDiscoveryFailure.INVALID_REQUEST
            else -> ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE
        }
        throw ProviderModelDiscoveryException(kind, error.httpCode)
    } catch (_: ManagementCapabilitiesMismatch) {
        throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.UNSUPPORTED)
    } catch (_: ManagementExecutionUnknown) {
        throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE)
    } catch (_: ProviderMetadataReadException) {
        throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE)
    } catch (_: IllegalArgumentException) {
        throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.INVALID_REQUEST)
    } catch (_: Exception) {
        throw ProviderModelDiscoveryException(ProviderModelDiscoveryFailure.INVALID_RESPONSE)
    } finally { discovery.clearSecret() }

    private fun parseCatalog(reply: String, discovery: ProviderModelDiscoveryRequest, keys: List<String>): ProviderModelCatalog {
        val root = Json.parseToJsonElement(reply).objectValue()
        val redactor = ProviderMetadataRedactor(root, keys)
        val base = ProviderBaseUrl.returnedBase(root.string("baseUrl", required = true))
        if (!ProviderBaseUrl.sameOrigin(base, discovery.chatUrl) || redactor.credentialEcho(base)) throw ManagementProtocolException()
        val requestedPath = java.net.URI(discovery.baseUrl).rawPath.orEmpty().trimEnd('/')
        val alternatePath = if (requestedPath.endsWith("/v1")) requestedPath.removeSuffix("/v1") else requestedPath + "/v1"
        if (java.net.URI(base).rawPath.orEmpty().trimEnd('/') !in setOf(requestedPath, alternatePath)) throw ManagementProtocolException()
        val rows = root["models"] as? JsonArray ?: throw ManagementProtocolException()
        if (rows.size > 1000) throw ManagementProtocolException()
        val models = rows.map { item ->
            val row = item.objectValue()
            val id = safeModelId(row.string("id", required = true), redactor)
            val name = row.string("name")
            if (redactor.credentialEcho(name) || name.any { it.isISOControl() } || name.codePointCount(0, name.length) > 256) throw ManagementProtocolException()
            ProviderModelSummary(id, redactor.display(name, id, 256), true)
        }.distinctBy { it.id }
        return ProviderModelCatalog(immutable(models), base)
    }

    /** One negotiation per owner-bound native session; older cores may list but cannot write. */
    @Synchronized
    private fun ensureProviderCapabilities() {
        if (providerCapabilitiesVerified) return
        val body = request("GET", "/api/mobile/capabilities")
        val row = readSafe { Json.parseToJsonElement(body).objectValue() }
        val version = row["coreVersion"] as? JsonPrimitive
        if (row["schemaVersion"] != JsonPrimitive(1) || version == null || !version.isString || version.content.isBlank() ||
            row.strictBoolOrNull("mobile") != true || row.strictBoolOrNull("loopbackKeys") != true ||
            row.strictBoolOrNull("adminLoopbackOnly") != true || row.strictBoolOrNull("debugEndpoints") != false ||
            row.strictBoolOrNull("nativeAutoUpdate") != false || row["providerSchemaVersion"] != JsonPrimitive(1) ||
            row.strictBoolOrNull("providerManagement") != true) throw ManagementCapabilitiesMismatch()
        modelDiscoverySupported = row.strictBoolOrNull("providerModelDiscovery") == true
        providerCapabilitiesVerified = true
    }

    private fun request(method: String, path: String, body: ByteArray? = null): String = try {
        transport.request(method, path, body)
    } catch (error: ManagementHttpException) {
        // A server-side write failure may be after commit (including core write_unknown503).
        // Do not read free error bodies to distinguish it; conservatively require readonly review.
        if (method == "POST" && error.httpCode >= 500) throw ManagementExecutionUnknown()
        when (error.httpCode) {
            409 -> if (path == "/api/mobile/providers/create") throw ProviderAlreadyExistsException() else throw ProviderRevisionConflictException()
            422 -> throw ProviderUnsupportedException()
            else -> throw error // fixed status only; no free-form error body
        }
    } catch (error: ManagementExecutionUnknown) {
        if (method == "GET") throw ProviderMetadataReadException()
        throw error
    }

    private fun parseList(body: String): ProviderListSnapshot {
        val root = Json.parseToJsonElement(body).objectValue()
        val rows = root.array("providers", required = true)
        if (rows.size > 1000) throw ManagementProtocolException()
        val providers = rows.map { parseSummary(it.objectValue()) }
        if (providers.map { it.id }.toSet().size != providers.size) throw ManagementProtocolException()
        val error = root["fileError"]
        val hasError = if (error == null || error == JsonNull) false else error.stringValue().isNotEmpty()
        return ProviderListSnapshot(immutable(providers), hasError)
    }

    private fun parseSummary(row: JsonObject): ProviderSummary {
        val redactor = ProviderMetadataRedactor(row)
        val id = row.string("id", required = true)
        if (!validProviderExistingId(id) || redactor.sensitive(id)) throw ManagementProtocolException()
        val name = redactor.display(row.string("name"), id, 120)
        val chatRaw = row.string("chat")
        val chat = if (redactor.sensitive(chatRaw)) "" else try { providerChatUrl(chatRaw, true) } catch (_: Exception) { "" }
        val key = parseKey(row["key"], strict = false)
        val models = row.array("models").map { element ->
            val model = element.objectValue()
            val modelId = safeModelId(model.string("id", required = true), redactor)
            ProviderModelSummary(modelId, redactor.display(model.string("name"), modelId, 256), model.bool("on"))
        }.distinctBy { it.id }
        if (models.size > 1000) throw ManagementProtocolException()
        val chosen = row.array("chosen").map { safeModelId(it.stringValue(), redactor) }.distinct()
        if (chosen.size > 1000) throw ManagementProtocolException()
        val custom = row.string("preset").isEmpty() &&
            (row["account"] == null || row["account"] == JsonNull) &&
            row.string("responses").isEmpty() && row.string("anthropic").isEmpty() &&
            row.string("gemini").isEmpty() && row.string("decide").isEmpty() &&
            row.string("baseAPI") in listOf("", "chat")
        return ProviderSummary(id, name, chat, key, immutable(models), immutable(chosen),
            row.bool("noModels"), row.bool("off"), row.bool("ready"),
            custom && chat.isNotEmpty() && key.known && !key.optional)
    }

    private fun parseEditable(body: String, submittedSecrets: List<String> = emptyList()): ProviderEditSnapshot {
        val row = Json.parseToJsonElement(body).objectValue()
        val redactor = ProviderMetadataRedactor(row, submittedSecrets)
        val id = row.string("id", required = true)
        val revision = row.string("revision", required = true)
        if (!validProviderExistingId(id) || redactor.sensitive(id) || !validProviderRevision(revision)) throw ManagementProtocolException()
        val name = redactor.display(providerName(row.string("name", required = true)), id, 120)
        val chatRaw = row.string("chat", required = true)
        if (redactor.sensitive(chatRaw)) throw ManagementProtocolException()
        val chat = providerChatUrl(chatRaw, true)
        if (row["models"] == JsonNull) throw ManagementProtocolException()
        val models = row.array("models", required = true).map { safeModelId(it.stringValue(), redactor) }
        if (models.size > 1000 || models.distinct().size != models.size) throw ManagementProtocolException()
        val noModels = row.bool("noModels", required = true)
        if (noModels && models.isNotEmpty()) throw ManagementProtocolException()
        val key = parseKey(row["key"], strict = true)
        if (key.optional) throw ManagementProtocolException()
        val serverPin = providerServerPin(row.string("serverPin"))
        if (redactor.sensitive(serverPin)) throw ManagementProtocolException()
        return ProviderEditSnapshot(id, revision, name, chat, immutable(models), noModels, key, serverPin)
    }

    private fun parseKey(element: JsonElement?, strict: Boolean): ProviderKeyMetadata {
        if (element !is JsonObject) {
            if (strict) throw ManagementProtocolException()
            return ProviderKeyMetadata(false, false, false)
        }
        val set = element.strictBoolOrNull("set")
        val optional = element.strictBoolOrNull("optional")
        if (set == null || optional == null || (strict && element["masked"] !is JsonPrimitive)) {
            if (strict) throw ManagementProtocolException()
            return ProviderKeyMetadata(false, false, false)
        }
        if (strict) element.string("masked", required = true) // type-check only; never retain raw mask
        return ProviderKeyMetadata(set, optional, true)
    }

    private fun safeModelId(raw: String, redactor: ProviderMetadataRedactor): String {
        val id = providerModelIds(listOf(raw)).single()
        if (id != raw || redactor.credentialEcho(id)) throw ManagementProtocolException()
        return id
    }
    private fun <T> readSafe(action: () -> T): T = try { action() } catch (_: Exception) { throw ManagementProtocolException() }
    private fun <T> writeSafe(action: () -> T): T = try { action() } catch (_: Exception) { throw ManagementExecutionUnknown() }
    companion object { internal const val MAX_REQUEST_BYTES = 512 * 1024 }
}

/** No raw key or even vendor-supplied mask retained: masks can contain accidental secrets. */
data class ProviderKeyMetadata internal constructor(val set: Boolean, val optional: Boolean, val known: Boolean) {
    val masked: String get() = if (known && set) "••••••" else ""
    override fun toString(): String = "ProviderKeyMetadata(credentials=<redacted>)"
}
data class ProviderModelSummary internal constructor(val id: String, val name: String, val on: Boolean) {
    override fun toString(): String = "ProviderModelSummary(metadata=<safe>)"
}
data class ProviderSummary internal constructor(
    val id: String, val name: String, val chatUrl: String, val key: ProviderKeyMetadata,
    val models: List<ProviderModelSummary>, val chosen: List<String>,
    val noModels: Boolean, val off: Boolean, val ready: Boolean, val editable: Boolean,
) {
    val chat: String get() = chatUrl
    val keySet: Boolean get() = key.known && key.set
    val selectedModels: List<String> get() = chosen
    override fun toString(): String = "ProviderSummary(metadata=<safe>,credentials=<redacted>)"
}
data class ProviderListSnapshot(val providers: List<ProviderSummary> = emptyList(), val hasFileError: Boolean = false) {
    override fun toString(): String = "ProviderListSnapshot(count=" + providers.size + ",metadata=<safe>)"
}
class ProviderEditSnapshot internal constructor(
    val id: String, val revision: String, val name: String, val chatUrl: String,
    val modelIds: List<String>, val noModels: Boolean, val key: ProviderKeyMetadata,
    val serverPin: String = "",
) {
    val chat: String get() = chatUrl
    val keySet: Boolean get() = key.known && key.set
    val models: List<String> get() = modelIds
    val selectedModels: List<String> get() = modelIds
    override fun toString(): String = "ProviderEditSnapshot(metadata=<safe>,credentials=<redacted>)"
}
class ProviderConfigurationUnreadableException : ManagementException("提供商配置文件异常；请修复并重新读取，拒绝提交")
class ProviderRevisionConflictException : ManagementException("提供商配置版本已变化；请重新读取编辑快照，禁止静默覆盖")
class ProviderUnsupportedException : ManagementException("此提供商不支持当前原生编辑；仅支持自定义API-key OpenAI Chat")

private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: throw ManagementProtocolException()
private fun JsonElement.stringValue(): String {
    val primitive = this as? JsonPrimitive ?: throw ManagementProtocolException()
    if (!primitive.isString) throw ManagementProtocolException()
    return primitive.content
}
private fun JsonObject.string(field: String, required: Boolean = false): String {
    val element = this[field]
    if (element == null || element == JsonNull) {
        if (required) throw ManagementProtocolException()
        return ""
    }
    return element.stringValue()
}
private fun JsonObject.strictBoolOrNull(field: String): Boolean? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return if (!primitive.isString) primitive.booleanOrNull else null
}
private fun JsonObject.bool(field: String, required: Boolean = false): Boolean {
    if (this[field] == null || this[field] == JsonNull) {
        if (required) throw ManagementProtocolException()
        return false
    }
    return strictBoolOrNull(field) ?: throw ManagementProtocolException()
}
private fun JsonObject.array(field: String, required: Boolean = false): JsonArray {
    val element = this[field]
    if (element == null) {
        if (required) throw ManagementProtocolException()
        return JsonArray(emptyList())
    }
    // Existing providersJSON may serialize a nil slice as null; project it as an empty list.
    if (element == JsonNull) return JsonArray(emptyList())
    return element as? JsonArray ?: throw ManagementProtocolException()
}
private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

/** Hide suspect metadata echoes too, not only obvious raw key fields. No source JSON escapes. */
private class ProviderMetadataRedactor(root: JsonObject, submittedSecrets: List<String> = emptyList()) {
    private val forbidden = mutableListOf<String>()
    init { forbidden.addAll(submittedSecrets.filter { it.isNotEmpty() }); collect(root, false) }
    private fun collect(value: JsonElement, secretContext: Boolean) {
        when (value) {
            is JsonObject -> value.forEach { (field, child) ->
                val label = field.lowercase()
                collect(child, secretContext || label.contains("key") || label.contains("token") ||
                    label.contains("secret") || label.contains("password") || label == "authorization" || label == "headers")
            }
            is JsonArray -> value.forEach { collect(it, secretContext) }
            is JsonPrimitive -> if (secretContext && value.isString && value.content.isNotEmpty()) forbidden.add(value.content)
        }
    }
    fun credentialEcho(value: String): Boolean = value.isNotEmpty() &&
        (forbidden.any { if (it.length < 8) value == it else value.contains(it) } || SECRET_PATTERN.containsMatchIn(value))
    fun sensitive(value: String): Boolean = credentialEcho(value) || value.isNotEmpty() &&
        (HEX_TOKEN.containsMatchIn(value) || value.matches(BARE_TOKEN))
    fun display(value: String, fallback: String, maxRunes: Int): String = when {
        value.isBlank() -> fallback
        value != value.trim() || value.any { it.isISOControl() } || value.codePointCount(0, value.length) > maxRunes || sensitive(value) -> "[已隐藏]"
        else -> value
    }
    companion object {
        private val SECRET_PATTERN = Regex("""(?i)(?:^|[^A-Za-z0-9])(?:sk[-_]|bearer\s|api[_-]?key[=:]|token[=:]|password[=:]|secret[=:]|eyJ[A-Za-z0-9_-]{20,})""")
        private val HEX_TOKEN = Regex("[0-9a-fA-F]{32,}")
        private val BARE_TOKEN = Regex("[A-Za-z0-9_+/=]{40,}")
    }
}
