package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
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

    private fun <T> decode(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // Serialization errors can quote the response (including secrets). Never chain them.
        throw ManagementProtocolException()
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
) {
    // Even accidentally logging a collection/map must not print any part of secret.
    override fun toString(): String = "RegisteredGatewayKey(secret=<redacted>)"
}

@Serializable
private data class CallerKeysResponse(val keys: List<RegisteredGatewayKey>)
@Serializable
private data class CreatedCallerKeyResponse(val keys: List<RegisteredGatewayKey>, val secret: String)
@Serializable
private data class AddCallerKey(val name: String)
@Serializable
private data class CopyCallerKey(val key: String)

open class ManagementException internal constructor(message: String) : RuntimeException(message)
class ManagementHttpException(val httpCode: Int) : ManagementException("管理请求失败：HTTP $httpCode")
class ManagementExecutionUnknown : ManagementException("管理请求执行结果未知；不得重放写请求，请重新只读核对密钥列表")
class ManagementProtocolException : ManagementException("管理响应格式无效或超过安全上限")
class ManagementCapabilitiesMismatch : ManagementException("核心移动安全能力不兼容；拒绝注册密钥")

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
            if (code !in 200..299) throw ManagementHttpException(code)
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
