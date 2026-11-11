package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Authenticated GUI management API, pinned to upstream 54dc257f plus the mobile overlay.
 * The gateway (3425) alone has /api/hello; GUI (3430) has state/caller-keys/capabilities.
 * Native GET/POST intentionally have no Origin and use the direct, port-bound webGuard cookie.
 * No redirects or application retries. POST uses fixed-length streaming to disable JDK replay.
 */
class CoreManagementClient(
    private val credential: ManagerCredential,
    baseUrl: String = "http://127.0.0.1:3430",
    timeout: Duration = Duration.ofSeconds(5),
) {
    private val endpoint = LoopbackEndpoint(baseUrl)
    private val transport: BoundedCoreHttp
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(endpoint.port != 3425) { "管理 cookie 不得发送到网关" }
        require(endpoint.port == credential.port) { "管理 cookie 端口不匹配" }
        transport = BoundedCoreHttp(endpoint, timeout, credential.cookieHeader())
    }

    /** Fail closed before provisioning if any required mobile safety property differs. */
    fun capabilities(): MobileCapabilities {
        val body = transport.request("GET", "/api/mobile/capabilities")
        val result = decode { json.decodeFromString(MobileCapabilities.serializer(), body) }
        if (result.schemaVersion != 1 || result.coreVersion.isBlank() || !result.mobile ||
            !result.loopbackKeys || !result.adminLoopbackOnly || result.debugEndpoints || result.nativeAutoUpdate) {
            throw ManagementCapabilitiesMismatch()
        }
        return result
    }

    fun listCallerKeys(): List<RegisteredGatewayKey> {
        val body = transport.request("GET", "/api/caller-keys")
        return decode { json.decodeFromString(CallerKeysResponse.serializer(), body).keys.map { it.copy(secret = "") } }
    }

    /**
     * GET /api/caller-keys/models (upstream 54dc257f caller_keys.go): the model catalog a
     * caller key may reach, including routing groups and account/decision entries.
     * Metadata only; no secrets are present in this response.
     */
    fun callerKeyModels(): List<CallerKeyModel> {
        val body = transport.request("GET", "/api/caller-keys/models")
        return decode { json.decodeFromString(CallerKeyModelsResponse.serializer(), body).models }
    }

    /** Read-only usage insight endpoints (whitelisted exact paths, P5-4). Returns raw JSON text. */
    fun usageInsight(endpoint: UsageInsightEndpoint): String {
        return transport.request("GET", endpoint.path)
    }

    /** GET /api/notices (upstream 54dc257f): open-source license notices text. Bounded display. */
    fun notices(): String {
        val body = transport.request("GET", "/api/notices")
        return decode { json.decodeFromString(NoticesResponse.serializer(), body).text }
    }

    /** GET /api/usage (upstream 54dc257f usage.go usageState(period, zone)): aggregate usage state. Raw JSON. */
    fun usageSummary(period: String = ""): String {
        require(period.isEmpty() || period in setOf("today", "week", "month", "all")) { "period 仅允许 today/week/month/all" }
        val suffix = if (period.isEmpty()) "" else "?period=" + period
        return transport.request("GET", "/api/usage" + suffix)
    }

    /** Exactly one add-key POST, sending only name. The core generates 24 bytes (48 hex).
     * access.List() removes every row secret. Associate the top-level secret only with the
     * unique newly added id/name, using the immediately preceding GET id set; never masked.
     * A malformed successful response is UNKNOWN: the key may already have been issued.
     */
    @Synchronized
    fun createCallerKey(name: String): RegisteredGatewayKey {
        require(validGatewayName(name)) { "网关密钥名称无效" }
        val before = listCallerKeys()
        if (before.any { !validGatewayMetadata(it) } || before.map { it.id }.toSet().size != before.size) {
            throw ManagementProtocolException()
        }
        // A prior UNKNOWN or concurrent startup can reveal a reserved key on this second GET.
        // Stop before add-key; an explicit retry will recover it through copy-key.
        if (name == GatewayKeyProvisioner.RESERVED_NAME && before.any { it.name == name }) {
            throw GatewayKeyProvisioningException()
        }
        val oldIds = before.map { it.id }.toSet()
        val request = json.encodeToString(AddCallerKey(name)).toByteArray(Charsets.UTF_8)
        val body = transport.request("POST", "/api/caller-keys/add-key", request)
        try {
            val response = json.decodeFromString(CreatedCallerKeyResponse.serializer(), body)
            if (!response.secret.matches(Regex("sk-magpie-key-[0-9a-fA-F]{48}")) ||
                response.keys.map { it.id }.toSet().size != response.keys.size) {
                throw ManagementProtocolException()
            }
            val candidates = response.keys.filter { it.id !in oldIds && it.name == name }
            val key = (candidates.singleOrNull() ?: throw ManagementProtocolException()).copy(secret = response.secret)
            if (!usableGatewayKey(key)) throw ManagementProtocolException()
            return key
        } catch (_: Exception) {
            throw ManagementExecutionUnknown()
        }
    }

    /** Upstream copy-key is a read operation expressed as POST; it never rotates a key.
     * Only this top-level secret (not list[].masked/list[].secret) can recover an owned id.
     */
    fun copyCallerKey(id: String): RegisteredGatewayKey {
        require(validGatewayId(id)) { "网关密钥标识无效" }
        val request = json.encodeToString(CopyCallerKey(id)).toByteArray(Charsets.UTF_8)
        val body = transport.request("POST", "/api/caller-keys/copy-key", request)
        try {
            val response = json.decodeFromString(CreatedCallerKeyResponse.serializer(), body)
            val metadata = response.keys.filter { it.id == id }.singleOrNull() ?: throw ManagementProtocolException()
            val key = metadata.copy(secret = response.secret)
            if (!usableGatewayKey(key)) throw ManagementProtocolException()
            return key
        } catch (_: Exception) {
            throw ManagementExecutionUnknown()
        }
    }

    /** Legacy state belongs to the management plane and always needs an explicit cookie. */
    fun state(): JsonObject {
        val body = transport.request("GET", "/api/state")
        return decode { json.parseToJsonElement(body).jsonObject }
    }

    /**
     * GET /api/model-speeds（upstream speeds.go）：按 "provider/model" 的周均首 token 耗时与输出速度。
     * 上游 schema 未钉死：单条字段缺失取默认值，整体畸形返回空表（绝不呈现原始正文）。
     */
    fun modelSpeeds(): Map<String, ModelSpeed> {
        val body = transport.request("GET", "/api/model-speeds")
        return decode { parseModelSpeeds(body) }
    }

    /**
     * GET /api/catalog/models（upstream catalog_models.go）：已同步的 models.dev 目录条目。
     * 只读元数据（id/名称/厂商/上下文窗口/价格），无凭据；解析失败返回空表。
     */
    fun catalogModels(): List<CatalogModel> {
        val body = transport.request("GET", "/api/catalog/models")
        return decode { parseCatalogModels(body) }
    }

    /**
     * POST /api/caller-keys/{action}（upstream 3781）：按白名单动作更新密钥限制。
     * 每次只发送单一动作字段（encodeDefaults=false），绝不误清其他限制；
     * 返回脱敏后的完整密钥列表。
     */
    @Synchronized
    fun updateCallerKey(action: CallerKeyAction, change: CallerKeyChange): List<RegisteredGatewayKey> {
        require(validGatewayId(change.key)) { "网关密钥标识无效" }
        val request = Json { encodeDefaults = false }.encodeToString(change).toByteArray(Charsets.UTF_8)
        val body = transport.request("POST", "/api/caller-keys/" + action.path, request)
        return decode {
            json.decodeFromString(CreatedCallerKeyResponse.serializer(), body).keys.map { it.copy(secret = "") }
        }
    }

    /** Recent request audit trail from management plane (zero request/response body leakage). */
    fun recentRequests(limit: Int = 5): List<UsageRecord> {
        require(limit in 1..50) { "limit 必须在 1..50" }
        val body = transport.request("GET", "/api/usage/requests?limit=$limit")
        return UsageClient.parseUsageRecords(body)
    }

    private fun <T> decode(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // Serialization errors can quote the response (including secrets). Never chain them.
        throw ManagementProtocolException()
    }
}

/** speedJSON（upstream speeds.go）：首 token 平均毫秒、首 token 后每秒输出 token、计时样本数。 */
@Serializable
data class ModelSpeed(
    val ttft: Long = 0,
    val tps: Double = 0.0,
    val n: Int = 0,
)

/** catalogModel（upstream catalog_models.go）：models.dev 目录的一个去重条目（只读元数据）。 */
@Serializable
data class CatalogModel(
    val id: String,
    val name: String = "",
    val maker: String = "",
    val context: Int = 0,
    val output: Int = 0,
    val input: Double = 0.0,
    val cost: Double = 0.0,
    val images: Boolean = false,
)

/** 弹性解析 {"provider/model": {ttft,tps,n}}；畸形/空输入返回空表，绝不抛原文。 */
internal fun parseModelSpeeds(raw: String): Map<String, ModelSpeed> {
    if (raw.isBlank()) return emptyMap()
    return try {
        Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, ModelSpeed>>(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 弹性解析 models.dev 目录数组；畸形/空输入返回空表。 */
internal fun parseCatalogModels(raw: String): List<CatalogModel> {
    if (raw.isBlank()) return emptyList()
    return try {
        Json { ignoreUnknownKeys = true }.decodeFromString<List<CatalogModel>>(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

@Serializable
data class MobileCapabilities(
    val schemaVersion: Int,
    val coreVersion: String,
    val mobile: Boolean,
    val loopbackKeys: Boolean,
    val debugEndpoints: Boolean,
    val adminLoopbackOnly: Boolean,
    val nativeAutoUpdate: Boolean,
)

@Serializable
data class RegisteredGatewayKey(
    val id: String,
    val name: String,
    val secret: String = "",
    val off: Boolean = false,
    val readOnly: Boolean = false,
    val browser: Boolean = false,
    /** RFC3339 from access.Key; absent means no expiry. Kept as the exact core value. */
    val expires: String? = null,
    /** models-key 白名单（upstream 3781）；空=可用全部模型。 */
    val models: List<String> = emptyList(),
    /** aliases-key 模型别名（名称 -> provider/model）。 */
    val aliases: Map<String, String> = emptyMap(),
    /** tier-key 服务档；空=跟随请求。 */
    val serviceTier: String = "",
    /** 首个额度窗口（limit-key 设置）；无=不限额。 */
    val limit: KeyLimit? = null,
    /** 后续额度窗口（moreLimits）。 */
    val moreLimits: List<KeyLimit> = emptyList(),
    /** 并发上限（concurrency-key）；0=不限。 */
    val maxConcurrency: Int = 0,
) {
    // Even accidentally logging a collection/map must not print any part of secret.
    override fun toString(): String = "RegisteredGatewayKey(secret=<redacted>)"
}

@Serializable
private data class CallerKeysResponse(val keys: List<RegisteredGatewayKey>)

/** upstream 3781 access.Limit：一个额度窗口（period/days/hours/tokens/cost/cacheReads/models）。 */
@Serializable
data class KeyLimit(
    val period: String = "",
    val days: Int = 0,
    val hours: Int = 0,
    val tokens: Long = 0,
    val cost: Double = 0.0,
    val cacheReads: Boolean = false,
    val models: List<String> = emptyList(),
)

/** 白名单化密钥更新动作（精确路径，非前缀）。 */
enum class CallerKeyAction(internal val path: String) {
    MODELS("models-key"),
    ALIASES("aliases-key"),
    TIER("tier-key"),
}

/** upstream 3781 access.Change 的移动端子集：key 必填，其余按需单字段发送。 */
@Serializable
data class CallerKeyChange(
    val key: String = "",
    val models: List<String> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val serviceTier: String = "",
)

/** Upstream keyModelJSON (caller_keys.go): id is "<provider>/<model>" or "group/<id>". */
@Serializable
data class CallerKeyModel(
    val id: String,
    val name: String,
    val provider: String? = null,
    val providerName: String? = null,
    val group: Boolean = false,
    val account: Boolean = false,
    val key: Boolean = false,
    val plan: String? = null,
)
@Serializable
private data class CallerKeyModelsResponse(val models: List<CallerKeyModel> = emptyList())
@Serializable
private data class NoticesResponse(val text: String = "")
@Serializable
private data class CreatedCallerKeyResponse(val keys: List<RegisteredGatewayKey>, val secret: String)
@Serializable
private data class AddCallerKey(val name: String)
@Serializable
private data class CopyCallerKey(val key: String)

open class ManagementException internal constructor(message: String) : RuntimeException(message)
class ManagementHttpException(val httpCode: Int, val safeErrorCode: String? = null) : ManagementException("管理请求失败：HTTP $httpCode")
class ManagementExecutionUnknown : ManagementException("管理请求执行结果未知；不得重放写请求，请重新只读核对密钥列表")
class ManagementProtocolException : ManagementException("管理响应格式无效或超过安全上限")
class ManagementCapabilitiesMismatch : ManagementException("核心移动安全能力不兼容；拒绝注册密钥")

internal fun safeModelErrorCode(value: String?): String? = value?.takeIf { it in setOf(
    "provider_models_dns", "provider_models_tls_certificate", "provider_models_unavailable",
    "provider_models_timeout", "provider_models_invalid", "provider_models_too_large",
    "provider_models_sensitive_content", "provider_models_redirect_rejected", "provider_models_upstream_error",
    "provider_models_key_required", "provider_models_forbidden", "provider_models_not_found",
    "provider_models_rate_limited", "provider_models_origin_changed",
) }

internal fun validGatewayName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
    name.codePointCount(0, name.length) <= 120 && name.none { it.isISOControl() }

internal fun validGatewayId(id: String): Boolean = id.isNotBlank() && id.length <= 256 && id.none { it.isISOControl() }
internal fun validGatewayMetadata(key: RegisteredGatewayKey): Boolean = validGatewayId(key.id) && validGatewayName(key.name)
internal fun validGatewayIdentity(key: RegisteredGatewayKey): Boolean = validGatewayMetadata(key) &&
    key.secret.length in 8..256 && key.secret.all { it in '!'..'~' }

internal fun usableGatewayMetadata(key: RegisteredGatewayKey, now: Instant = Instant.now()): Boolean {
    if (!validGatewayMetadata(key) || key.off || key.browser || key.readOnly) return false
    val expires = key.expires ?: return true
    return try { Instant.parse(expires).isAfter(now) } catch (_: Exception) { false }
}
internal fun usableGatewayKey(key: RegisteredGatewayKey): Boolean = validGatewayIdentity(key) && usableGatewayMetadata(key)

/** No DNS aliases, userinfo, escaped/forged authority, query, fragment, or path accepted. */
internal class LoopbackEndpoint(baseUrl: String) {
    val port: Int
    val base: String
    init {
        val parsed = try { URI(baseUrl) } catch (_: Exception) {
            throw IllegalArgumentException("核心地址必须为精确 loopback HTTP URL")
        }
        val authority = parsed.rawAuthority
        val match = Regex("""(127[.]0[.]0[.]1|\[::1\]):([1-9][0-9]{0,4})""").matchEntire(authority ?: "")
        val candidatePort = match?.groupValues?.get(2)?.toIntOrNull()
        require(parsed.scheme == "http" && parsed.rawUserInfo == null && candidatePort != null &&
            candidatePort in 1..65535 && parsed.port == candidatePort &&
            (parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") &&
            parsed.rawQuery == null && parsed.rawFragment == null) {
            "核心地址必须为精确 loopback HTTP URL"
        }
        port = candidatePort
        base = "http://$authority"
    }
}

/** Shared bounded transport; cookie is passed explicitly only by the management client. */
internal class BoundedCoreHttp(
    private val endpoint: LoopbackEndpoint,
    timeout: Duration,
    private val cookie: String? = null,
) {
    private val timeoutMillis: Int
    init {
        val millis: Long = try { timeout.toMillis() } catch (_: ArithmeticException) { -1L }
        require(millis in 1..Int.MAX_VALUE.toLong()) { "核心请求超时无效" }
        timeoutMillis = millis.toInt()
    }

    fun request(method: String, path: String, payload: ByteArray? = null): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URI(endpoint.base + path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
            }
            val code = conn.responseCode
            if (code < 100) throw ManagementExecutionUnknown()
            // Never read free-form errorBody, follow Location, or replay a POST.
            if (code !in 200..299) throw ManagementHttpException(code, safeModelErrorCode(conn.getHeaderField("X-Magpie-Mobile-Error-Code")))
            if (conn.contentLengthLong > MAX_RESPONSE_BYTES) throw ManagementProtocolException()
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_RESPONSE_BYTES) throw ManagementProtocolException()
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } catch (_: IOException) {
            // Native/JDK errors may quote addresses, headers or bodies: no original cause retained.
            throw ManagementExecutionUnknown()
        } catch (e: ManagementProtocolException) {
            if (method == "POST") throw ManagementExecutionUnknown()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    companion object { const val MAX_RESPONSE_BYTES = 1024 * 1024 }
}
