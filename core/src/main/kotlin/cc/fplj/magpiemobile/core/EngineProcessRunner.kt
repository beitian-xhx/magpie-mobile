package cc.fplj.magpiemobile.core

import java.io.File
import java.time.Duration

/**
 * 引擎进程执行抽象（plan.md §4.1 的可测试核心）。
 *
 * 职责：以绝对路径执行 ELF PIE 载荷、注入隔离环境变量、捕获 stdout/stderr/exit。
 * Android 侧的 nativeLibraryDir 解析由调用方传入（core 不依赖 android API）。
 *
 * 安全约束：
 * - exe 必须是绝对路径且存在（Android 10+ W^X：只允许 APK 内嵌载荷路径）
 * - 环境变量白名单注入（HOME/TMPDIR/XDG_*），不透传父进程环境
 * - 超时强杀并标记 TIMEOUT（EngineSupervisor据此不自动复活）
 */
class EngineProcessRunner(
    private val timeout: Duration = Duration.ofSeconds(60),
) {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    fun run(
        exePath: String,
        args: List<String>,
        homeDir: File,
        cacheDir: File,
        configDir: File,
    ): Result {
        val exe = File(exePath)
        require(exe.isAbsolute) { "载荷必须是绝对路径（Android 10+ W^X：nativeLibraryDir 内），got $exePath" }
        require(exe.exists()) { "载荷不存在：$exePath" }

        configDir.mkdirs()
        cacheDir.mkdirs()

        val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
        // 白名单注入，不透传父环境（核心行为确定性）
        val env = pb.environment()
        env.clear()
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = cacheDir.absolutePath
        env["XDG_CONFIG_HOME"] = configDir.absolutePath
        env["XDG_CACHE_HOME"] = cacheDir.absolutePath
        env["PATH"] = "/system/bin:/system/xbin"

        val proc = pb.start()
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val t1 = Thread { proc.inputStream.bufferedReader().forEachLine { outBuf.append(it).append('\n') } }
        val t2 = Thread { proc.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') } }
        t1.start(); t2.start()
        val done = proc.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            proc.destroyForcibly()
            t1.join(2000); t2.join(2000)
            return Result(-1, outBuf.toString(), errBuf.toString(), timedOut = true)
        }
        t1.join(2000); t2.join(2000)
        return Result(proc.exitValue(), outBuf.toString(), errBuf.toString(), timedOut = false)
    }
}
