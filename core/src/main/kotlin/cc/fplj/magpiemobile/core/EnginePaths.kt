package cc.fplj.magpiemobile.core

import java.io.File

/**
 * 引擎载荷路径解析（plan.md §4.1 的纯逻辑部分，无 Android 依赖）。
 *
 * Android 侧传入 applicationInfo.nativeLibraryDir 与 filesDir/cacheDir，
 * 这里负责：载荷命名约定、目录布局。存在性/可执行校验由 EngineProcessRunner 执行时做。
 *
 * 命名约定：libmagpie_exec.so / libbun_exec.so（APK jniLibs 打包，
 * 安装时由系统提取到 nativeLibraryDir 并 chmod 755——W^X 合规路径）。
 */
object EnginePaths {

    const val MAGPIE_LIB = "libmagpie_exec.so"
    const val BUN_LIB = "libbun_exec.so"

    data class Layout(
        val nativeLibraryDir: File,
        val homeDir: File,
        val cacheDir: File,
        val configDir: File,
    )

    fun magpieExecutable(layout: Layout): File =
        layout.nativeLibraryDir.resolve(MAGPIE_LIB)

    fun bunExecutable(layout: Layout): File =
        layout.nativeLibraryDir.resolve(BUN_LIB)

    /** 从任意 nativeLibraryDir 字符串构建布局（Android 侧调用），null/空白/相对路径拒绝。 */
    fun buildLayout(nativeLibraryDir: String?, filesDir: File, cacheDir: File): Layout {
        require(!nativeLibraryDir.isNullOrBlank()) { "nativeLibraryDir 为空：APK 未正确安装 native 载荷" }
        val lib = File(nativeLibraryDir)
        require(lib.isAbsolute) { "nativeLibraryDir 必须绝对：" + nativeLibraryDir }
        return Layout(
            nativeLibraryDir = lib,
            homeDir = filesDir,
            cacheDir = File(cacheDir, "xdg-cache"),
            configDir = File(filesDir, "config"),
        )
    }
}
