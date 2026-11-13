package cc.fplj.magpiemobile

import android.content.Context
import cc.fplj.magpiemobile.core.EngineEnvironment
import cc.fplj.magpiemobile.core.EnginePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bundled WorkBuddy / Qoder Magpie plugins.
 *
 * The embedded Go core reads its plugin list from
 * `$XDG_CONFIG_HOME/magpie/plugins.json` and loads each npm package from
 * `$XDG_CONFIG_HOME/magpie/plugins/node_modules/<package>`.  The APK ships
 * the real official plugin packages as assets; this object copies them into
 * that private directory on first start and records them in plugins.json.
 *
 * Credentials are never put on a command line: the core's `plugin login`
 * CLI reads the key/PAT from stdin when stdin is not a terminal.
 */
object MagpiePlugins {
    const val WORKBUDDY = "@magpie-community/opencode-workbuddy-auth"
    const val QODER = "@magpie-community/opencode-qoder-auth"

    private val BUNDLED = listOf(WORKBUDDY to "workbuddy", QODER to "qoder")

    data class InstallResult(val copied: Int, val settingsPath: String)
    data class LoginResult(val ok: Boolean, val output: String)

    fun ensureInstalled(context: Context, layout: EnginePaths.Layout): InstallResult {
        EngineEnvironment.prepare(layout)
        val settingsDir = File(layout.configDir, "magpie")
        val nodeModules = File(settingsDir, "plugins/node_modules")
        var copied = 0
        for ((pkg, asset) in BUNDLED) {
            val target = File(nodeModules, pkg)
            if (!File(target, "package.json").isFile) {
                copyAssetTree(context, "magpie-plugins/$asset", target)
                copied++
            }
        }

        val listFile = File(settingsDir, "plugins.json")
        val root = if (listFile.isFile) {
            runCatching { JSONObject(listFile.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val plugins = root.optJSONArray("plugins") ?: JSONArray().also { root.put("plugins", it) }
        val existing = HashSet<String>()
        for (i in 0 until plugins.length()) {
            val spec = plugins.optJSONObject(i)?.optString("spec").orEmpty()
            if (spec.isNotBlank()) existing.add(packageName(spec))
        }
        for ((pkg, _) in BUNDLED) {
            if (!existing.contains(pkg)) {
                plugins.put(JSONObject().put("spec", pkg))
            }
        }
        listFile.parentFile?.mkdirs()
        listFile.writeText(root.toString(2), Charsets.UTF_8)
        return InstallResult(copied, listFile.absolutePath)
    }

    fun login(
        context: Context,
        layout: EnginePaths.Layout,
        provider: String,
        method: String?,
        secret: String,
    ): LoginResult {
        ensureInstalled(context, layout)
        val executable = EnginePaths.magpieExecutable(layout)
        if (!executable.isFile) {
            return LoginResult(false, "核心载荷不存在：" + executable.absolutePath)
        }
        val command = mutableListOf(executable.absolutePath, "plugin", "login", provider)
        if (!method.isNullOrBlank()) command.add(method)
        val logFile = File.createTempFile("magpie-login-", ".log", layout.cacheDir)
        return try {
            val builder = ProcessBuilder(command)
                .directory(layout.homeDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
            val environment = builder.environment()
            environment.clear()
            environment.putAll(EngineEnvironment.prepare(layout))
            environment["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            val process = builder.start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(secret)
                writer.newLine()
            }
            val finished = process.waitFor(180, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LoginResult(false, "登录超时（180 秒）。请确认网络可以访问供应商站点后重试。")
            } else {
                val output = runCatching { logFile.readText(Charsets.UTF_8) }.getOrDefault("")
                LoginResult(process.exitValue() == 0, output.takeLast(4000))
            }
        } catch (e: Exception) {
            LoginResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            logFile.delete()
        }
    }

    private fun packageName(spec: String): String {
        val at = spec.lastIndexOf('@')
        return if (at > 0) spec.substring(0, at) else spec
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        require(children.isNotEmpty()) { "APK 缺少内置插件资源：$assetPath" }
        target.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val grandchildren = context.assets.list(childAsset) ?: emptyArray()
            if (grandchildren.isNotEmpty()) {
                copyAssetTree(context, childAsset, File(target, name))
            } else {
                context.assets.open(childAsset).use { input ->
                    File(target, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
