package cc.fplj.magpiemobile.core

import java.io.File

/**
 * 插件脚本受控安装器（plan.md §4.5 的可测试核心）。
 *
 * 安全边界（用户已确认取舍）：
 * - 只落地 JS 文本供预置 Bun 读取解释；绝不落地可执行位（W^X：可写目录不可执行）
 * - 路径穿越拒绝：目标名不允许 ..、绝对路径、路径分隔符
 * - 大小上限 2MB（单文件），防资源耗尽
 * - 固定私有根 <filesDir>/plugins/，Bun 由此读；无 native 下载、无外部 CLI
 */
object ScriptAssetInstaller {

    const val MAX_BYTES = 2L * 1024 * 1024

    fun install(pluginsRoot: File, relativeName: String, content: ByteArray): File {
        require(relativeName.isNotBlank()) { "脚本名不能为空" }
        require(!relativeName.contains("..") && !relativeName.startsWith("/") && !relativeName.contains('\\')) {
            "脚本名不允许路径穿越：$relativeName"
        }
        require(!relativeName.contains('/')) { "只支持单层脚本名（无子目录）：$relativeName" }
        require(content.size <= MAX_BYTES) { "脚本超过上限：" + content.size + " > " + MAX_BYTES }
        require(!pluginsRoot.exists() || pluginsRoot.isDirectory) { "插件根不是目录：" + pluginsRoot }

        pluginsRoot.mkdirs()
        val target = pluginsRoot.resolve(relativeName)
        // 防符号链接覆盖（symlink 指向私有区外）
        if (target.exists() && target.canonicalPath != target.absolutePath) {
            throw SecurityException("目标已是符号链接：" + target.absolutePath)
        }
        target.writeBytes(content)
        // 双保险：绝无可执行位
        target.setExecutable(false, false)
        return target
    }

    fun listInstalled(pluginsRoot: File): List<File> =
        pluginsRoot.listFiles()?.filter { it.isFile && it.canRead() }?.sortedBy { it.name } ?: emptyList()
}
