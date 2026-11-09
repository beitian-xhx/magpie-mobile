package cc.fplj.magpiemobile.core.backup

import cc.fplj.magpiemobile.core.SecretRedactor
import cc.fplj.magpiemobile.core.providerChatUrl
import cc.fplj.magpiemobile.core.providerModelIds
import cc.fplj.magpiemobile.core.providerName
import cc.fplj.magpiemobile.core.validProviderExistingId
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Storage Access Framework (SAF) Safe Config Backup & Restore (M4).
 *
 * Security Contract:
 * 1. Zero plaintext secrets: never exports or imports API keys, tokens or passwords.
 * 2. Pure stream I/O: never accesses absolute system file paths or public Downloads.
 * 3. Bounded payload: hard limit of 5MB against OOM and zip-bomb attacks.
 * 4. SHA-256 integrity: verifies tamper-proof digest of exported configuration.
 * 5. Strict schema & URL validation: rejects invalid protocols (e.g. file://, javascript:).
 */
object ConfigBackupManager {
    const val APP_NAME = "Magpie-Mobile"
    const val APP_VERSION = "0.1.18"
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024 // 5 MB

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val compactJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Serializable
    data class ExportedProvider(
        val id: String,
        val name: String,
        val chatUrl: String,
        val models: List<String> = emptyList(),
    )

    @Serializable
    data class ExportedPreferences(
        val theme: String = "SYSTEM",
        val defaultModel: String = "",
    )

    @Serializable
    data class BackupDocument(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val appName: String = APP_NAME,
        val appVersion: String = APP_VERSION,
        val exportedAt: String,
        val providers: List<ExportedProvider> = emptyList(),
        val preferences: ExportedPreferences = ExportedPreferences(),
        val sha256: String = "",
    )

    data class BackupResult(
        val schemaVersion: Int,
        val appVersion: String,
        val exportedAt: String,
        val providers: List<ExportedProvider>,
        val preferences: ExportedPreferences,
        val sha256: String,
    )

    /**
     * Safely export metadata to the given output stream.
     * Throws BackupSecurityException if any value contains suspected secrets.
     */
    fun export(
        outputStream: OutputStream,
        providers: List<ExportedProvider>,
        preferences: ExportedPreferences,
        now: Instant = Instant.now(),
    ) {
        val isoDate = DateTimeFormatter.ISO_INSTANT.format(now)
        // 1. Validate providers and audit secrets
        for (p in providers) {
            require(validProviderExistingId(p.id)) { "提供商标识格式无效: " + p.id }
            providerName(p.name)
            providerChatUrl(p.chatUrl, allowLoopbackHttp = true)
            providerModelIds(p.models)
            assertNoSecrets(p.id)
            assertNoSecrets(p.name)
            assertNoSecrets(p.chatUrl)
            p.models.forEach { assertNoSecrets(it) }
        }
        assertNoSecrets(preferences.defaultModel)

        // 2. Build canonical payload without sha256 to compute signature
        val canonicalPayload = buildCanonicalPayload(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appName = APP_NAME,
            appVersion = APP_VERSION,
            exportedAt = isoDate,
            providers = providers,
            preferences = preferences,
        )
        val canonicalBytes = compactJson.encodeToString(canonicalPayload).toByteArray(Charsets.UTF_8)
        val computedSha = sha256Hex(canonicalBytes)

        val document = BackupDocument(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appName = APP_NAME,
            appVersion = APP_VERSION,
            exportedAt = isoDate,
            providers = providers,
            preferences = preferences,
            sha256 = computedSha,
        )

        val finalBytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        outputStream.write(finalBytes)
        outputStream.flush()
    }

    /**
     * Safely import and validate backup from the given input stream.
     */
    fun import(inputStream: InputStream): BackupResult {
        val rawBytes = readBounded(inputStream, MAX_BACKUP_BYTES)
        if (rawBytes.isEmpty()) throw BackupValidationException("备份文件内容为空")

        val rootElement = try {
            json.parseToJsonElement(rawBytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupValidationException("备份文件不是合法的 JSON 格式: " + (e.message ?: e.javaClass.simpleName))
        }
        val rootObj = rootElement as? JsonObject ?: throw BackupValidationException("备份根节点必须为 JSON 对象")

        val schema = rootObj["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw BackupValidationException("缺少 schemaVersion 字段")
        if (schema != CURRENT_SCHEMA_VERSION) {
            throw BackupValidationException("不支持的备份版本 schemaVersion=$schema (当前支持 $CURRENT_SCHEMA_VERSION)")
        }

        val appName = rootObj["appName"]?.jsonPrimitive?.content ?: ""
        if (appName != APP_NAME) {
            throw BackupValidationException("备份应用名称不匹配: $appName (期望 $APP_NAME)")
        }

        val appVersion = rootObj["appVersion"]?.jsonPrimitive?.content ?: "unknown"
        val exportedAt = rootObj["exportedAt"]?.jsonPrimitive?.content ?: ""
        val declaredSha = rootObj["sha256"]?.jsonPrimitive?.content ?: ""
        if (declaredSha.isBlank()) {
            throw BackupChecksumMismatchException("备份文件缺少 SHA-256 校验和")
        }

        // Parse providers
        val providersElement = rootObj["providers"]?.jsonArray ?: JsonArray(emptyList())
        val parsedProviders = mutableListOf<ExportedProvider>()
        for (item in providersElement) {
            val itemObj = item as? JsonObject ?: throw BackupValidationException("提供商条目必须为 JSON 对象")
            val id = itemObj["id"]?.jsonPrimitive?.content ?: throw BackupValidationException("提供商缺少 id")
            val name = itemObj["name"]?.jsonPrimitive?.content ?: throw BackupValidationException("提供商缺少 name")
            val chatUrl = itemObj["chatUrl"]?.jsonPrimitive?.content ?: throw BackupValidationException("提供商缺少 chatUrl")
            val models = (itemObj["models"]?.jsonArray ?: JsonArray(emptyList())).map {
                it.jsonPrimitive.content
            }

            if (!validProviderExistingId(id)) throw BackupValidationException("提供商标识格式无效: $id")
            val validName = try { providerName(name) } catch (e: Exception) { throw BackupValidationException(e.message ?: "名称无效") }
            val validChat = try { providerChatUrl(chatUrl, allowLoopbackHttp = true) } catch (e: Exception) { throw BackupValidationException(e.message ?: "地址无效") }
            val validModels = try { providerModelIds(models) } catch (e: Exception) { throw BackupValidationException(e.message ?: "模型列表无效") }

            assertNoSecrets(id)
            assertNoSecrets(validName)
            assertNoSecrets(validChat)
            validModels.forEach { assertNoSecrets(it) }

            parsedProviders.add(ExportedProvider(id, validName, validChat, validModels))
        }

        // Parse preferences
        val prefObj = rootObj["preferences"]?.jsonObject
        val theme = prefObj?.get("theme")?.jsonPrimitive?.content ?: "SYSTEM"
        if (theme !in listOf("SYSTEM", "LIGHT", "DARK")) {
            throw BackupValidationException("主题设置无效: $theme")
        }
        val defaultModel = prefObj?.get("defaultModel")?.jsonPrimitive?.content ?: ""
        if (defaultModel.isNotEmpty()) {
            if (defaultModel.length > 512 || defaultModel.any { it.isISOControl() }) {
                throw BackupValidationException("默认模型标识无效")
            }
            assertNoSecrets(defaultModel)
        }
        val parsedPref = ExportedPreferences(theme = theme, defaultModel = defaultModel)

        // Verify SHA256
        val canonicalPayload = buildCanonicalPayload(
            schemaVersion = schema,
            appName = appName,
            appVersion = appVersion,
            exportedAt = exportedAt,
            providers = parsedProviders,
            preferences = parsedPref,
        )
        val canonicalBytes = compactJson.encodeToString(canonicalPayload).toByteArray(Charsets.UTF_8)
        val computedSha = sha256Hex(canonicalBytes)

        if (!computedSha.equals(declaredSha, ignoreCase = true)) {
            throw BackupChecksumMismatchException("备份文件完整性校验失败：计算值 $computedSha，声明值 $declaredSha")
        }

        return BackupResult(
            schemaVersion = schema,
            appVersion = appVersion,
            exportedAt = exportedAt,
            providers = parsedProviders,
            preferences = parsedPref,
            sha256 = declaredSha,
        )
    }

    private fun readBounded(inputStream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw BackupPayloadTooLargeException("备份文件大小超过上限 (" + (maxBytes / 1024 / 1024) + "MB)")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildCanonicalPayload(
        schemaVersion: Int,
        appName: String,
        appVersion: String,
        exportedAt: String,
        providers: List<ExportedProvider>,
        preferences: ExportedPreferences,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("appName", appName)
        put("appVersion", appVersion)
        put("exportedAt", exportedAt)
        put("providers", buildJsonArray {
            for (p in providers) {
                add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("chatUrl", p.chatUrl)
                    put("models", buildJsonArray {
                        p.models.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        })
        put("preferences", buildJsonObject {
            put("theme", preferences.theme)
            put("defaultModel", preferences.defaultModel)
        })
    }

    private fun assertNoSecrets(value: String) {
        if (value.isBlank()) return
        val redacted = SecretRedactor.redact(value)
        if (redacted != value) {
            throw BackupSecurityException("配置项包含疑似凭据或未脱敏内容，已被安全拦截")
        }
        // Additional regex checks for bare keys
        if (SUSPECT_SECRET.containsMatchIn(value)) {
            throw BackupSecurityException("配置项包含疑似 API Key 或 Token，已被安全拦截")
        }
    }

    private val SUSPECT_SECRET = Regex(
        "(?i)(sk-[a-zA-Z0-9_-]{8,}|bearer\\s+[a-zA-Z0-9-._~]{8,}|gh[pousr]_[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{20,})"
    )
}

open class BackupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class BackupSecurityException(message: String) : BackupException(message)
class BackupPayloadTooLargeException(message: String) : BackupException(message)
class BackupChecksumMismatchException(message: String) : BackupException(message)
class BackupValidationException(message: String) : BackupException(message)
