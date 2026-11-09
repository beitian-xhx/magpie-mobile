package cc.fplj.magpiemobile.core

import java.io.File
import java.io.FileInputStream

data class BunRuntimeStatus(
    val installed: Boolean,
    val executable: Boolean,
    val architecture: String,
    val path: String,
    val errorMessage: String? = null,
)

/**
 * Validates and inspects the embedded ARM64 Bun execution payload (libbun_exec.so)
 * in accordance with P2-4 requirements.
 */
object BunRuntimeInspector {

    private const val ELF_MAGIC = 0x7F454C46 // \x7fELF in big endian representation
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_2LSB = 1 // Little endian
    private const val EM_AARCH64 = 183 // ARM 64-bit architecture

    fun inspect(layout: EnginePaths.Layout): BunRuntimeStatus {
        val bunFile = EnginePaths.bunExecutable(layout)
        val pathStr = bunFile.absolutePath

        if (!bunFile.exists()) {
            return BunRuntimeStatus(
                installed = false,
                executable = false,
                architecture = "none",
                path = pathStr,
                errorMessage = "未在 nativeLibraryDir 发现预置的 libbun_exec.so"
            )
        }

        if (!bunFile.canRead()) {
            return BunRuntimeStatus(
                installed = true,
                executable = false,
                architecture = "unknown",
                path = pathStr,
                errorMessage = "libbun_exec.so 无读取权限"
            )
        }

        val arch = detectElfArchitecture(bunFile)
        val isAarch64 = arch == "aarch64"
        val isExec = bunFile.canExecute()

        return BunRuntimeStatus(
            installed = true,
            executable = isExec && isAarch64,
            architecture = arch,
            path = pathStr,
            errorMessage = if (!isAarch64) "载荷非 ARM64 (AArch64) 架构: $arch" else null
        )
    }

    internal fun detectElfArchitecture(file: File): String = try {
        FileInputStream(file).use { input ->
            val header = ByteArray(20)
            val read = input.read(header)
            if (read < 20) return "truncated"

            // Check \x7fELF
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
                return "not-elf"
            }

            val elfClass = header[4].toInt() and 0xFF
            val elfData = header[5].toInt() and 0xFF
            if (elfClass != ELF_CLASS_64 || elfData != ELF_DATA_2LSB) {
                return "incompatible-elf"
            }

            // e_machine at offset 18, 2 bytes, little endian
            val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            if (machine == EM_AARCH64) "aarch64" else "machine-$machine"
        }
    } catch (_: Exception) {
        "unreadable"
    }
}
