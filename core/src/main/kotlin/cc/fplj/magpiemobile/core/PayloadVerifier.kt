package cc.fplj.magpiemobile.core

import java.io.File
import java.io.RandomAccessFile

/**
 * 引擎载荷完整性自检（P0-1 静态门槛的可执行版，plan.md §6 P0 的真机前置）。
 *
 * 检查项（与 p0/inspect.json 同语义）：
 * - ELF magic（\x7fELF）
 * - 64 位小端
 * - e_machine = AArch64 (183)
 * - e_type = ET_DYN (PIE)
 * 全部通过返回 Pass；失败返回带具体原因的 Fail（UI 可直接展示）。
 * 不做 DT_NEEDED/16KB 深检（构建期已验），只做启动前快速自检。
 */
object PayloadVerifier {

    sealed class Verdict {
        object Pass : Verdict()
        data class Fail(val reason: String) : Verdict()
    }

    fun verify(file: File): Verdict {
        if (!file.exists()) return Verdict.Fail("载荷不存在：" + file.name)
        if (file.length() < 64) return Verdict.Fail("文件过小，不是 ELF：" + file.name)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(20)
                raf.readFully(head)
                if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte())
                    return Verdict.Fail("ELF magic 不匹配：" + file.name)
                if (head[4] != 2.toByte()) return Verdict.Fail("非 64 位 ELF：" + file.name)
                if (head[5] != 1.toByte()) return Verdict.Fail("非小端 ELF：" + file.name)
                val eType = readU16LE(head, 16)
                val eMachine = readU16LE(head, 18)
                if (eMachine != 183) return Verdict.Fail("非 AArch64（e_machine=" + eMachine + "）：" + file.name)
                if (eType != 3) return Verdict.Fail("非 PIE（e_type=" + eType + "）：" + file.name)
                Verdict.Pass
            }
        } catch (e: Exception) {
            Verdict.Fail("读取失败：" + e.message)
        }
    }

    private fun readU16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
