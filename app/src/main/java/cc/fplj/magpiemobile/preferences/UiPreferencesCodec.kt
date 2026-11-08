package cc.fplj.magpiemobile.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppPage { GATEWAY, PROVIDERS, CHAT, MORE }
enum class GatewayIntent { NONE, START, STOP }

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val page: AppPage = AppPage.GATEWAY,
    val modelId: String = "",
    val gatewayIntent: GatewayIntent = GatewayIntent.NONE,
    val intentRevision: Long = 0,
)

data class UiPreferencesState(
    val ready: Boolean = false,
    val value: UiPreferences = UiPreferences(),
    val warning: String? = null,
)

/** Fixed exception only; no input, upstream diagnostic, file path or cause. */
class UiPreferencesWriteException : IllegalStateException(UiPreferenceWarnings.WRITE), kotlinx.coroutines.CopyableThrowable<UiPreferencesWriteException> {
    override fun createCopy(): UiPreferencesWriteException? = null
}

internal object UiPreferenceWarnings {
    const val READ = "无法读取界面设置，暂用安全默认值；原文件已保留。"
    const val INVALID = "界面设置版本或内容不受支持，已禁止写入；原文件已保留。"
    const val WRITE = "无法保存界面设置，原文件已保留。"
    const val MODEL = "模型标识无效，未保存。"
    const val REVISION = "启停意图版本已达上限，未保存。"
    const val QUEUE = "界面设置队列暂不可用，本次修改未记录。"
}

internal class InvalidStoredUiPreferences : RuntimeException()
internal class UiIntentRevisionOverflow : RuntimeException()

internal object UiModelIdPolicy {
    private val sensitive = Regex(
        "(?i)(bearer|cookie|authorization|password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token|" +
            "(^|[:/])(sk[-_]|gh[pousr]_|github_pat_|hf_|AKIA|AIza|key[-_]|token[-_])|(^|[:/])eyJ[A-Za-z0-9_-]*\\.)",
    )
    private val urlScheme = Regex("(?i)^(https?|file|content|data|javascript):")
    private val opaqueHex = Regex("(?i)^[a-f0-9]{40,}$")

    fun valid(value: String): Boolean = value.length <= 512 &&
        !value.contains("://") && !urlScheme.containsMatchIn(value) &&
        !sensitive.containsMatchIn(value) &&
        value.split('/', ':').none { atom -> opaqueHex.matches(atom) ||
            (atom.length >= 40 && atom.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } &&
                atom.any { it in 'A'..'Z' } && atom.any { it in 'a'..'z' } && atom.any { it in '0'..'9' }) } &&
        value.all { it.isLetterOrDigit() || it in "._:/+-" }
}

internal object UiPreferencesCodec {
    val schema = intPreferencesKey("schema")
    val theme = stringPreferencesKey("theme")
    val page = stringPreferencesKey("page")
    val modelId = stringPreferencesKey("modelId")
    val gatewayIntent = stringPreferencesKey("gatewayIntent")
    val intentRevision = longPreferencesKey("intentRevision")

    /** Inspect raw values, not typed get(): wrong-type keys must never become defaults. */
    fun decode(preferences: Preferences): UiPreferences {
        val values = preferences.asMap().entries.associate { it.key.name to it.value }
        val version = values[schema.name]
        if (version != null && (version !is Int || version != 1)) throw InvalidStoredUiPreferences()
        val storedModel = values[modelId.name]
        if (storedModel != null && (storedModel !is String || !UiModelIdPolicy.valid(storedModel))) {
            throw InvalidStoredUiPreferences()
        }
        val revision = values[intentRevision.name]
        if (revision != null && (revision !is Long || revision < 0)) throw InvalidStoredUiPreferences()
        return UiPreferences(
            theme = decodeEnum(values[theme.name], ThemeMode.SYSTEM),
            page = decodeEnum(values[page.name], AppPage.GATEWAY),
            modelId = storedModel as? String ?: "",
            gatewayIntent = decodeEnum(values[gatewayIntent.name], GatewayIntent.NONE),
            intentRevision = revision as? Long ?: 0,
        )
    }

    private inline fun <reified E : Enum<E>> decodeEnum(value: Any?, default: E): E {
        if (value == null) return default
        if (value !is String) throw InvalidStoredUiPreferences()
        return enumValues<E>().firstOrNull { it.name == value } ?: throw InvalidStoredUiPreferences()
    }
}
