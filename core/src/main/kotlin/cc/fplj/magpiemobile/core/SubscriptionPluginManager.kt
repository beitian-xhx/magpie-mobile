package cc.fplj.magpiemobile.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SubscriptionPlugin(
    val id: String,
    val name: String,
    val version: String,
    val entrypoint: String,
    val enabled: Boolean = true,
    val checksum: String = "",
) {
    init {
        require(PLUGIN_ID_REGEX.matches(id)) { "插件标识格式无效: $id" }
        require(name.isNotBlank() && name.length <= 128) { "插件名称无效" }
        require(version.isNotBlank() && version.length <= 32) { "插件版本号无效" }
        require(validEntrypoint(entrypoint)) { "插件入口点无效或存在路径遍历风险: $entrypoint" }
    }

    companion object {
        private val PLUGIN_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

        fun validEntrypoint(path: String): Boolean {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.startsWith("\\")) return false
            val parts = trimmed.split('/', '\\')
            return parts.all { it.isNotEmpty() && it != ".." && it != "." }
        }
    }
}

/**
 * Manages sandboxed subscription plugins and safe version rollbacks (P2-4).
 * Enforces path isolation within the app-private plugins directory.
 */
class SubscriptionPluginManager(
    private val pluginsDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        pluginsDir.mkdirs()
    }

    @Synchronized
    fun install(plugin: SubscriptionPlugin, scriptContent: String): Boolean {
        val targetDir = File(pluginsDir, plugin.id)
        val backupDir = File(pluginsDir, plugin.id + ".bak")

        // Atomic rollback preparation: backup previous version
        if (targetDir.exists() && targetDir.isDirectory) {
            backupDir.deleteRecursively()
            targetDir.renameTo(backupDir)
        }

        try {
            targetDir.mkdirs()
            val entryFile = File(targetDir, plugin.entrypoint)
            entryFile.parentFile?.mkdirs()
            entryFile.writeText(scriptContent, Charsets.UTF_8)

            val sha256 = sha256Hex(scriptContent)
            val manifestFile = File(targetDir, "plugin.json")
            val finalizedPlugin = plugin.copy(checksum = sha256)
            manifestFile.writeText(json.encodeToString(finalizedPlugin), Charsets.UTF_8)
            return true
        } catch (e: Exception) {
            // Restore backup on failure
            targetDir.deleteRecursively()
            if (backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            throw e
        }
    }

    @Synchronized
    fun rollback(pluginId: String): Boolean {
        require(SubscriptionPlugin.Companion.run { pluginId.isNotBlank() })
        val targetDir = File(pluginsDir, pluginId)
        val backupDir = File(pluginsDir, pluginId + ".bak")

        if (!backupDir.exists() || !backupDir.isDirectory) {
            return false
        }

        targetDir.deleteRecursively()
        val restored = backupDir.renameTo(targetDir)
        return restored
    }

    @Synchronized
    fun list(): List<SubscriptionPlugin> {
        if (!pluginsDir.exists() || !pluginsDir.isDirectory) return emptyList()
        val dirs = pluginsDir.listFiles { f -> f.isDirectory && !f.name.endsWith(".bak") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifestFile = File(dir, "plugin.json")
            if (manifestFile.exists() && manifestFile.canRead()) {
                try {
                    json.decodeFromString<SubscriptionPlugin>(manifestFile.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                    null
                }
            } else null
        }.sortedBy { it.id }
    }

    @Synchronized
    fun remove(pluginId: String): Boolean {
        val targetDir = File(pluginsDir, pluginId)
        val backupDir = File(pluginsDir, pluginId + ".bak")
        val removed = targetDir.deleteRecursively()
        backupDir.deleteRecursively()
        return removed
    }

    @Synchronized
    fun toggle(pluginId: String): Boolean {
        val targetDir = File(pluginsDir, pluginId)
        val manifestFile = File(targetDir, "plugin.json")
        if (!manifestFile.exists()) return false

        val current = try {
            json.decodeFromString<SubscriptionPlugin>(manifestFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return false
        }

        val updated = current.copy(enabled = !current.enabled)
        manifestFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
        return true
    }

    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
