package cc.fplj.magpiemobile.core

import java.io.File

/** Initialize writable app-private directories before native startup, never on the UI thread. */
object EngineEnvironment {
    fun prepare(layout: EnginePaths.Layout): Map<String, String> {
        for (dir in listOf(layout.homeDir, layout.configDir, layout.cacheDir)) {
            require(dir.isAbsolute) { "引擎目录必须是绝对路径" }
            check(dir.isDirectory || dir.mkdirs()) { "无法初始化引擎目录：" + dir.absolutePath }
        }
        return mapOf(
            "HOME" to layout.homeDir.absolutePath,
            "TMPDIR" to layout.cacheDir.absolutePath,
            "XDG_CONFIG_HOME" to layout.configDir.absolutePath,
            "XDG_CACHE_HOME" to layout.cacheDir.absolutePath,
            "MAGPIE_BUN" to EnginePaths.bunExecutable(layout).absolutePath,
            "MAGPIE_MOBILE" to "1",
            "PATH" to "/system/bin:/system/xbin",
        )
    }
}
