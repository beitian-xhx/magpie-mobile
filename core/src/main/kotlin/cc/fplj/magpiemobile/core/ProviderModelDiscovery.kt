package cc.fplj.magpiemobile.core

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure URL normalization, not permission to contact a host. HTTP still requires explicit
 * allowLoopbackHttp at discovery/save. localhost is not silently rewritten: the core accepts
 * literal 127.0.0.1 or [::1] with a port, not HTTP DNS aliases.
 */
object ProviderBaseUrl {
    /** Canonical base without trailing slash. Root input gets /v1; known full endpoint suffixes
     * are removed, with an explicit root endpoint preserving its root (no invented /v1).
     * Non-root custom prefixes are kept exactly, never appended with a second /v1.
     */
    fun normalize(input: String): String {
        val candidate = inputUri(input)
        val path = candidate.rawPath.orEmpty().trimEnd('/')
        var basePath = path
        var fullEndpoint = false
        for (suffix in listOf("/chat/completions", "/responses", "/models")) {
            if (basePath.endsWith(suffix)) {
                basePath = basePath.removeSuffix(suffix).trimEnd('/')
                fullEndpoint = true
                break
            }
        }
        if (basePath.isEmpty() && !fullEndpoint) basePath = "/v1"
        val base = candidate.scheme.lowercase() + "://" + candidate.rawAuthority.lowercase() + basePath
        return try { providerChatUrl(base, true) } catch (_: Exception) { throw invalidBaseUrl() }
    }

    /** Empty means no suggestion yet. Callers keep their own id/name-dirty flags, so a user's
     * manual edits are never overwritten by subsequent URL/debounce results.
     */
    fun suggestId(input: String): String = try {
        val uri = URI(normalize(input))
        val host = uri.host.removeSurrounding("[", "]").lowercase()
        val local = host == "127.0.0.1" || host == "::1"
        val seed = if (local) "local-" + uri.port else suggestionStem(host)
        val slug = seed.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(64).trimEnd('-')
        slug.ifEmpty { "provider" }
    } catch (_: Exception) { "" }

    fun suggestName(input: String): String = try {
        val uri = URI(normalize(input))
        val host = uri.host.removeSurrounding("[", "]").lowercase()
        when {
            host == "127.0.0.1" || host == "::1" -> "本机服务 " + uri.port
            host == "api.openai.com" || host == "openai.com" -> "OpenAI"
            host == "api.deepseek.com" || host == "deepseek.com" -> "DeepSeek"
            else -> suggestionStem(host).take(120)
        }
    } catch (_: Exception) { "" }

    internal fun chatEndpoint(input: String, allowLoopbackHttp: Boolean): String =
        providerChatUrl(normalize(input) + "/chat/completions", allowLoopbackHttp)

    /** Validate an actual returned stem without inventing /v1; origin must be checked by caller. */
    internal fun returnedBase(input: String): String =
        providerChatUrl(input, true).trimEnd('/')

    internal fun sameOrigin(a: String, b: String): Boolean {
        val first = URI(a)
        val second = URI(b)
        fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) && port(first) == port(second)
    }

    private fun inputUri(input: String): URI {
        if (input.isBlank() || input.any { it.isISOControl() }) throw invalidBaseUrl()
        val value = input.trim()
        if (value.codePointCount(0, value.length) > 4096) throw invalidBaseUrl()
        val withScheme = if (value.contains("://")) value else "https://" + value
        val uri = try { URI(withScheme) } catch (_: Exception) { throw invalidBaseUrl() }
        if (uri.isOpaque || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty().contains('%') ||
            uri.rawPath.orEmpty().split('/').any { it == "." || it == ".." }) throw invalidBaseUrl()
        val canonical = uri.scheme.lowercase() + "://" + uri.rawAuthority.lowercase() + uri.rawPath.orEmpty()
        try { providerChatUrl(canonical, true) } catch (_: Exception) { throw invalidBaseUrl() }
        return URI(canonical)
    }

    private fun suggestionStem(host: String): String {
        val labels = host.split('.')
        if (labels.size > 1 && labels.first() in listOf("api", "www", "gateway", "proxy")) return labels[1]
        return labels.firstOrNull().orEmpty().ifEmpty { "provider" }
    }
    private fun invalidBaseUrl() = IllegalArgumentException("Base URL无效：请用公开HTTPS，或带端口的127.0.0.1/[::1]本机HTTP；禁止凭据、查询参数和localhost别名")
}

/** Read-only metadata request, independent of the one-shot write drafts. The UI's form Key
 * is a separate value; clearing this temporary request after a read never clears the form.
 * id/revision together let the core reuse its stored Key without returning it to the App.
 */
class ProviderModelDiscoveryRequest(
    chatUrl: String,
    apiKey: String = "",
    val providerId: String? = null,
    val revision: String? = null,
    val allowLoopbackHttp: Boolean = false,
) {
    val chatUrl: String = ProviderBaseUrl.chatEndpoint(chatUrl, allowLoopbackHttp)
    val baseUrl: String = ProviderBaseUrl.normalize(chatUrl)
    private var secret = apiKey
    init {
        require((providerId == null) == (revision == null) &&
            (providerId == null || validProviderExistingId(providerId)) &&
            (revision == null || validProviderRevision(revision))) { "编辑目录读取需要有效提供商ID和版本；请先读取编辑快照" }
    }
    fun requestBody(): JsonObject {
        val key = providerApiKey(secret, allowBlank = true)
        return buildJsonObject {
            put("chat", chatUrl)
            if (key.isNotEmpty()) put("key", key)
            if (providerId != null) { put("id", providerId); put("revision", revision!!) }
        }
    }
    fun clearSecret() { secret = "" }
    override fun toString(): String = "ProviderModelDiscoveryRequest(credentials=<redacted>)"
}

class ProviderModelCatalog internal constructor(val models: List<ProviderModelSummary>, val baseUrl: String) {
    override fun toString(): String = "ProviderModelCatalog(count=" + models.size + ",metadata=<safe>)"
}

enum class ProviderModelDiscoveryFailure {
    KEY_OR_PERMISSION, RATE_LIMITED, NO_CATALOG, STALE_SNAPSHOT, UNSUPPORTED,
    NETWORK_OR_RESPONSE, TIMEOUT, INVALID_RESPONSE, INVALID_REQUEST,
    DNS_FAILURE, TLS_CERTIFICATE, RESPONSE_TOO_LARGE, REDIRECT_REJECTED, SENSITIVE_CONTENT,
}
/** Fixed user messages only. No vendor error body, URL, Key, or original exception cause. */
class ProviderModelDiscoveryException internal constructor(
    val failure: ProviderModelDiscoveryFailure,
    val httpCode: Int? = null,
) : ManagementException(when (failure) {
    ProviderModelDiscoveryFailure.DNS_FAILURE -> "Android系统DNS未能解析模型服务（HTTP502）；请更新网络修复版并检查网络"
    ProviderModelDiscoveryFailure.TLS_CERTIFICATE -> "系统TLS证书校验失败（HTTP502）；请检查时间/系统证书，不会跳过安全校验"
    ProviderModelDiscoveryFailure.RESPONSE_TOO_LARGE -> "模型目录响应超过安全大小上限；未修改配置"
    ProviderModelDiscoveryFailure.REDIRECT_REJECTED -> "模型目录重定向已拒绝，避免密钥被转发；请填写最终可信Base URL"
    ProviderModelDiscoveryFailure.SENSITIVE_CONTENT -> "模型目录含敏感凭据回显，已拒绝展示；未修改配置"
    ProviderModelDiscoveryFailure.KEY_OR_PERMISSION -> "模型目录需要有效Key或访问权限（HTTP " + httpCode + "）；请检查密钥"
    ProviderModelDiscoveryFailure.RATE_LIMITED -> "模型目录暂时限流（HTTP429）；请稍候重新读取"
    ProviderModelDiscoveryFailure.NO_CATALOG -> "该地址未提供兼容模型目录（HTTP " + httpCode + "）；请检查Base URL或手填模型ID"
    ProviderModelDiscoveryFailure.STALE_SNAPSHOT -> "提供商配置已变化；请重新读取编辑快照后再查模型目录"
    ProviderModelDiscoveryFailure.UNSUPPORTED -> "当前核心或提供商未开放只读模型目录；请更新核心或手填模型ID"
    ProviderModelDiscoveryFailure.TIMEOUT -> "模型目录读取超时（HTTP504）；请稍候重试，配置未修改"
    ProviderModelDiscoveryFailure.INVALID_RESPONSE -> "模型目录响应无效或不安全；未保存配置，请检查地址或手填模型ID"
    ProviderModelDiscoveryFailure.INVALID_REQUEST -> "模型目录请求无效；请检查Base URL、Key和编辑版本"
    ProviderModelDiscoveryFailure.NETWORK_OR_RESPONSE -> "模型目录读取未完成（网络、超时或响应异常" + (httpCode?.let { "，HTTP " + it } ?: "") + "）；可手动重试，配置未修改"
})

class ProviderMetadataReadException : ManagementException("管理元数据读取失败或超时；保存未发送，可重新读取")
class ProviderAlreadyExistsException : ManagementException("提供商标识已存在；请更换新增标识或从列表读取后编辑，不会覆盖原配置")
