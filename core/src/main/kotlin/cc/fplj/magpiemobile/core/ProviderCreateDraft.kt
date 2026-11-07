package cc.fplj.magpiemobile.core

import java.net.URI
import java.net.InetAddress
import java.util.Collections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Create-only request for /api/mobile/providers/create, never the legacy free-form save.
 * Key lives only in this short-lived form/request object. The client clears it after every
 * submission outcome; never put drafts/requestBody into a status Flow, log or saved state.
 */
class ProviderCreateDraft(
    id: String,
    name: String,
    chatUrl: String,
    apiKey: String,
    modelIds: List<String>,
    val allowLoopbackHttp: Boolean = false,
) {
    val id: String = providerCreateId(id)
    val name: String = providerName(name)
    val chatUrl: String = providerChatUrl(chatUrl, allowLoopbackHttp)
    val modelIds: List<String> = providerModelIds(modelIds)
    private var secret: String = apiKey
    private var submitted = false
    val chat: String get() = chatUrl

    fun requestBody(): JsonObject {
        val key = providerApiKey(secret, allowBlank = false)
        return buildJsonObject {
            put("new", true); put("id", id); put("name", name); put("chat", chatUrl); put("key", key)
            put("models", buildJsonArray { modelIds.forEach { add(JsonPrimitive(it)) } })
            // Empty alone can mean ALL in the core. Creation always expresses Select none.
            put("noModels", modelIds.isEmpty())
        }
    }
    @Synchronized
    internal fun submissionBody(): JsonObject {
        check(!submitted) { "新增请求已提交；请重新只读核对提供商列表" }
        val body = requestBody()
        submitted = true
        return body
    }
    fun clearSecret() { secret = "" }
    override fun toString(): String = "ProviderCreateDraft(credentials=<redacted>)"
}

/** Editing requires a freshly obtained safe snapshot and its opaque revision.
 * null means untouched; identical values are omitted. No rename, delete or advanced fields.
 * An explicitly edited empty model list means Select none, never accidentally Select all.
 */
class ProviderEditDraft(
    val snapshot: ProviderEditSnapshot,
    name: String? = null,
    chatUrl: String? = null,
    apiKey: String = "",
    modelIds: List<String>? = null,
    val allowLoopbackHttp: Boolean = false,
) {
    private val changedName = name?.let { providerName(it) }
    private val changedChat = chatUrl?.let {
        // A trusted unchanged loopback URL is not a new opt-in/change of endpoint.
        if (it == snapshot.chatUrl) null else providerChatUrl(it, allowLoopbackHttp)
    }
    private val changedModels = modelIds?.let { providerModelIds(it) }
    private var secret = apiKey
    private var submitted = false

    fun requestBody(): JsonObject {
        if (!validProviderExistingId(snapshot.id) || !validProviderRevision(snapshot.revision)) {
            throw ProviderEditPreconditionException()
        }
        val key = providerApiKey(secret, allowBlank = true)
        val nameDirty = changedName != null && changedName != snapshot.name
        val chatDirty = changedChat != null && changedChat != snapshot.chatUrl
        val modelsDirty = changedModels != null &&
            (changedModels != snapshot.modelIds || changedModels.isEmpty() != snapshot.noModels)
        if (!nameDirty && !chatDirty && !modelsDirty && key.isEmpty()) throw ProviderEditPreconditionException()
        return buildJsonObject {
            put("id", snapshot.id); put("revision", snapshot.revision)
            if (nameDirty) put("name", changedName!!)
            if (chatDirty) put("chat", changedChat!!)
            if (key.isNotEmpty()) put("key", key) // blank keeps the existing credential; never clear it
            if (modelsDirty) {
                put("models", buildJsonArray { changedModels!!.forEach { add(JsonPrimitive(it)) } })
                put("noModels", changedModels!!.isEmpty())
            }
        }
    }
    @Synchronized
    internal fun submissionBody(): JsonObject {
        if (submitted) throw ProviderEditPreconditionException()
        val body = requestBody()
        submitted = true
        return body
    }
    fun clearSecret() { secret = "" }
    override fun toString(): String = "ProviderEditDraft(credentials=<redacted>)"
}

class ProviderEditPreconditionException : ManagementException("编辑缺少有效快照、版本或修改字段；请重新读取提供商配置")

internal fun providerCreateId(raw: String): String {
    require(raw.none { it.isISOControl() }) { "提供商标识格式无效" }
    val id = raw.trim()
    require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "提供商标识格式无效" }
    return id
}
internal fun validProviderExistingId(id: String): Boolean = id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))
internal fun validProviderRevision(revision: String): Boolean = revision.matches(Regex("[0-9a-fA-F]{64}"))
internal fun providerName(raw: String): String {
    require(raw.none { it.isISOControl() }) { "提供商名称格式无效" }
    val name = raw.trim()
    require(name.isNotEmpty() && name.codePointCount(0, name.length) <= 120) { "提供商名称格式无效" }
    return name
}
internal fun providerApiKey(raw: String, allowBlank: Boolean): String {
    require(raw.none { it.isISOControl() }) { "API密钥格式无效" }
    val key = raw.trim()
    require((allowBlank || key.isNotEmpty()) && key.length <= 8192 && key.all { it in '!'..'~' }) { "API密钥格式无效" }
    return key
}
internal fun providerModelIds(raw: List<String>): List<String> {
    require(raw.size <= 1000) { "模型标识数量超出安全上限" }
    val ids = raw.map { value ->
        require(value.none { it.isISOControl() }) { "模型标识格式无效" }
        val id = value.trim()
        require(id.isNotEmpty() && id.codePointCount(0, id.length) <= 256) { "模型标识格式无效" }
        id
    }.distinct()
    return Collections.unmodifiableList(ArrayList(ids))
}

/** Validation only: never contacts the provider, tests a key, or detects models. */
internal fun providerChatUrl(raw: String, allowLoopbackHttp: Boolean): String {
    require(raw.none { it.isISOControl() }) { "提供商地址格式无效" }
    val value = raw.trim()
    require(value.codePointCount(0, value.length) in 1..4096) { "提供商地址格式无效" }
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("提供商地址格式无效") }
    val validHttps = uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        providerPublicHttpsHost(uri.host) && !uri.rawAuthority.endsWith(":") &&
        (uri.port == -1 || uri.port in 1..65535)
    val validLocalHttp = allowLoopbackHttp && uri.scheme == "http" && uri.port in 1..65535 &&
        Regex("""(127[.]0[.]0[.]1|\[::1\]):[1-9][0-9]{0,4}""").matches(uri.rawAuthority ?: "")
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        !uri.rawPath.orEmpty().contains("%") && (validHttps || validLocalHttp)) { "提供商地址必须为无凭据、无查询参数的HTTPS或显式允许的loopback HTTP" }
    return value
}

/** Numeric literals only use numeric parsing, never a hostname/DNS lookup. This validates
 * URL shape and local literal ranges; it is deliberately not a DNS-rebinding guarantee.
 */
private fun providerPublicHttpsHost(rawHost: String): Boolean {
    val host = rawHost.removeSurrounding("[", "]").lowercase()
    if (host.contains(':')) {
        if (!host.matches(Regex("[0-9a-f:.]+"))) return false
        val address = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
        val bytes = address.address
        val uniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        return !uniqueLocal && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
    }
    if (host.all { it in '0'..'9' || it == '.' }) {
        val labels = host.split('.')
        if (labels.size != 4) return false
        val numbers = labels.map { label ->
            val number = label.toIntOrNull() ?: return false
            if (number !in 0..255 || label != number.toString()) return false
            number
        }
        if (numbers.all { it == 255 } || numbers.first() == 0) return false
        val address = InetAddress.getByAddress(numbers.map { it.toByte() }.toByteArray())
        return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress
    }
    if (host.length > 253 || host.endsWith('.') || !host.contains('.') || host == "localhost" ||
        host.endsWith(".localhost") || host.endsWith(".local")) return false
    return host.split('.').all { label ->
        label.length in 1..63 && label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
    }
}
