package cc.fplj.magpiemobile

import android.content.Context
import cc.fplj.magpiemobile.core.EngineEnvironment
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.SecretRedactor
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

    private data class Bundled(val pkg: String, val asset: String, val version: String)

    private val BUNDLED = listOf(
        Bundled(WORKBUDDY, "workbuddy", "0.2.33"),
        Bundled(QODER, "qoder", "0.2.67"),
    )

    data class InstallResult(val copied: Int, val settingsPath: String, val warning: String = "")
    data class LoginResult(val ok: Boolean, val output: String)
    data class BunCheck(val ok: Boolean, val detail: String)

    fun checkBun(layout: EnginePaths.Layout): BunCheck {
        val bun = EnginePaths.bunExecutable(layout)
        if (!bun.isFile) return BunCheck(false, "Bun 载荷不存在")
        var logFile: File? = null
        return try {
            val output = File.createTempFile("magpie-bun-", ".log", layout.cacheDir)
            logFile = output
            val builder = ProcessBuilder(bun.absolutePath, "--version")
                .directory(layout.homeDir)
                .redirectErrorStream(true)
                .redirectOutput(output)
            val environment = builder.environment()
            environment.clear()
            environment.putAll(EngineEnvironment.prepare(layout))
            val process = builder.start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                BunCheck(false, "Bun --version 超时")
            } else {
                val text = runCatching { output.readText(Charsets.UTF_8).trim() }.getOrDefault("")
                if (process.exitValue() == 0) BunCheck(true, text.ifBlank { "ready" })
                else BunCheck(false, "Bun exit=${process.exitValue()} " + text.take(200))
            }
        } catch (e: Exception) {
            BunCheck(false, e.message ?: e.javaClass.simpleName)
        } finally {
            logFile?.delete()
        }
    }

    @Synchronized
    fun ensureInstalled(context: Context, layout: EnginePaths.Layout): InstallResult {
        EngineEnvironment.prepare(layout)
        val settingsDir = File(layout.configDir, "magpie")
        val nodeModules = File(settingsDir, "plugins/node_modules")
        var copied = 0
        for (bundle in BUNDLED) {
            val target = File(nodeModules, bundle.pkg)
            if (!isPackageReady(target, bundle.version)) {
                val staging = File(nodeModules, bundle.pkg + ".staging")
                if (staging.exists()) staging.deleteRecursively()
                copyAssetTree(context, "magpie-plugins/${bundle.asset}", staging)
                if (target.exists()) target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
                copied++
            }
        }

        val listFile = File(settingsDir, "plugins.json")
        var warning = ""
        val root = if (listFile.isFile) {
            val raw = listFile.readText(Charsets.UTF_8)
            runCatching { JSONObject(raw) }.getOrElse {
                val backup = File(settingsDir, "plugins.json.broken-${System.currentTimeMillis()}")
                runCatching { listFile.copyTo(backup, overwrite = true) }
                warning = "plugins.json 无法解析，已备份为 ${backup.name} 并重建"
                JSONObject()
            }
        } else {
            JSONObject()
        }
        val plugins = root.optJSONArray("plugins") ?: JSONArray().also { root.put("plugins", it) }
        val existing = HashSet<String>()
        for (i in 0 until plugins.length()) {
            val spec = plugins.optJSONObject(i)?.optString("spec").orEmpty()
            if (spec.isNotBlank()) existing.add(packageName(spec))
        }
        for (bundle in BUNDLED) {
            if (!existing.contains(bundle.pkg)) {
                plugins.put(JSONObject().put("spec", bundle.pkg))
            }
        }
        listFile.parentFile?.mkdirs()
        val updated = root.toString(2)
        if (!listFile.isFile || listFile.readText(Charsets.UTF_8) != updated) {
            val temporary = File(settingsDir, "plugins.json.tmp")
            temporary.writeText(updated, Charsets.UTF_8)
            if (!temporary.renameTo(listFile)) {
                listFile.delete()
                temporary.renameTo(listFile)
            }
        }
        return InstallResult(copied, listFile.absolutePath, warning)
    }

    /**
     * Write an "api" plugin credential directly, the exact shape Magpie's
     * plugin host reads from plugin-auth.json. Verified against the desktop
     * CLI: `magpie plugin login workbuddy api` produces
     * {"workbuddy":{"type":"api","key":"..."}}.
     *
     * WorkBuddy needs no token exchange, so this avoids a Bun child process,
     * a browser jump, and any network round-trip: it is immediate and works
     * even while the gateway is failing to start.
     */
    @Synchronized
    fun writeApiKey(
        context: Context,
        layout: EnginePaths.Layout,
        provider: String,
        key: String,
        onStage: (String) -> Unit = {},
    ): LoginResult {
        return try {
            onStage("正在检查内置插件…")
            ensureInstalled(context, layout)
            onStage("正在写入凭据…")
            val settingsDir = File(layout.configDir, "magpie")
            settingsDir.mkdirs()
            val authFile = File(settingsDir, "plugin-auth.json")
            val root = if (authFile.isFile) {
                runCatching { JSONObject(authFile.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
            } else JSONObject()
            root.put(provider, JSONObject().put("type", "api").put("key", key))
            val temporary = File(settingsDir, "plugin-auth.json.tmp")
            temporary.writeText(root.toString(2), Charsets.UTF_8)
            if (!temporary.renameTo(authFile)) {
                authFile.delete()
                temporary.renameTo(authFile)
            }
            onStage("")
            LoginResult(true, "已保存 $provider 凭据。请启动或重启网关，然后刷新模型列表。")
        } catch (e: Exception) {
            LoginResult(false, "写入凭据失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun login(
        context: Context,
        layout: EnginePaths.Layout,
        provider: String,
        method: String?,
        secret: String,
        onStage: (String) -> Unit = {},
    ): LoginResult {
        var logFile: File? = null
        var process: Process? = null
        return try {
            onStage("正在检查内置插件…")
            ensureInstalled(context, layout)
            val executable = EnginePaths.magpieExecutable(layout)
            if (!executable.isFile) {
                return LoginResult(false, "核心载荷不存在：" + executable.absolutePath)
            }
            onStage("正在启动 Magpie 登录进程…")
            val command = mutableListOf(executable.absolutePath, "plugin", "login", provider)
            if (!method.isNullOrBlank()) command.add(method)
            val outputFile = File.createTempFile("magpie-login-", ".log", layout.cacheDir)
            logFile = outputFile
            val builder = ProcessBuilder(command)
                .directory(layout.homeDir)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
            val environment = builder.environment()
            environment.clear()
            environment.putAll(EngineEnvironment.prepare(layout))
            // The Go CLI's Android browser opener expects termux-open-url. The
            // app has no Termux; a quiet no-op keeps PAT login from waiting on
            // (or erroring over) a browser jump it does not need.
            val shimDir = File(layout.homeDir, "bin").apply { mkdirs() }
            val browserShim = File(shimDir, "termux-open-url")
            if (!browserShim.isFile) {
                browserShim.writeText("#!/system/bin/sh\nexit 0\n", Charsets.UTF_8)
            }
            browserShim.setExecutable(true, false)
            environment["PATH"] = shimDir.absolutePath + ":/system/bin:/system/xbin"
            environment["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            val started = builder.start()
            process = started
            onStage("已启动，正在提交凭据…")
            started.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(secret)
                writer.newLine()
            }
            onStage("凭据已提交，等待插件返回（最长 90 秒）…")
            val finished = started.waitFor(90, TimeUnit.SECONDS)
            if (!finished) {
                stopProcessTree(started)
                LoginResult(false, "登录超时（90 秒）。请确认网络可以访问供应商站点后重试。")
            } else {
                onStage("已返回，正在读取登录结果…")
                val output = runCatching { outputFile.readText(Charsets.UTF_8) }.getOrDefault("")
                val safe = SecretRedactor.redact(output).replace(secret, "[REDACTED]")
                LoginResult(started.exitValue() == 0, safe.takeLast(4000))
            }
        } catch (e: Exception) {
            LoginResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            process?.let { if (it.isAlive) stopProcessTree(it) }
            logFile?.delete()
        }
    }

    /** Stop the CLI and the Bun host it started; killing only the Go parent can orphan Bun. */
    private fun stopProcessTree(root: Process) {
        val pid = processPid(root)
        if (pid <= 0L) {
            root.destroyForcibly()
            return
        }
        val descendants = descendantsOf(pid)
        descendants.asReversed().forEach { signal(it, 15) }
        signal(pid, 15)
        if (!runCatching { root.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
            descendants.asReversed().forEach { signal(it, 9) }
            signal(pid, 9)
            runCatching { root.destroyForcibly() }
        }
    }

    private fun descendantsOf(pid: Long): List<Long> {
        val parents = HashMap<Long, MutableList<Long>>()
        val entries = File("/proc").listFiles() ?: return emptyList()
        for (entry in entries) {
            val child = entry.name.toLongOrNull() ?: continue
            val parent = runCatching {
                File(entry, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("PPid:") }?.substringAfter(':')?.trim()?.toLongOrNull()
                }
            }.getOrNull() ?: continue
            parents.getOrPut(parent) { mutableListOf() }.add(child)
        }
        val result = mutableListOf<Long>()
        fun walk(parent: Long) {
            parents[parent]?.forEach { child ->
                result.add(child)
                walk(child)
            }
        }
        walk(pid)
        return result
    }

    private fun signal(pid: Long, signal: Int) {
        runCatching { android.os.Process.sendSignal(pid.toInt(), signal) }
    }

    private fun processPid(process: Process): Long = try {
        val value = Process::class.java.getMethod("pid").invoke(process)
        (value as? Number)?.toLong() ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun isPackageReady(target: File, expectedVersion: String): Boolean {
        if (!File(target, "package.json").isFile) return false
        for (name in listOf("index.mjs", "opencode.mjs", "opencode2.mjs")) {
            if (!File(target, name).isFile) return false
        }
        val version = runCatching {
            JSONObject(File(target, "package.json").readText(Charsets.UTF_8)).optString("version")
        }.getOrNull()
        return version == expectedVersion
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
