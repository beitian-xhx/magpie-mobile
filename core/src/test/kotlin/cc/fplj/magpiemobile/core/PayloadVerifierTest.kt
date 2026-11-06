package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayloadVerifierTest {

    @TempDir
    lateinit var tmp: File

    private fun writeElf(bytes: ByteArray): File {
        val f = File(tmp, "test.so")
        f.writeBytes(bytes)
        return f
    }

    private fun validElf(eType: Int = 3, eMachine: Int = 183): ByteArray {
        val b = ByteArray(64)
        b[0]=0x7f; b[1]='E'.code.toByte(); b[2]='L'.code.toByte(); b[3]='F'.code.toByte()
        b[4]=2; b[5]=1 // 64-bit LE
        b[16]=eType.toByte(); b[17]=(eType shr 8).toByte()
        b[18]=eMachine.toByte(); b[19]=(eMachine shr 8).toByte()
        return b
    }

    @Test
    fun validPieArm64Passes() {
        assertEquals(PayloadVerifier.Verdict.Pass, PayloadVerifier.verify(writeElf(validElf())))
    }

    @Test
    fun missingFileFails() {
        val v = PayloadVerifier.verify(File(tmp, "nope.so"))
        assertTrue(v is PayloadVerifier.Verdict.Fail && v.reason.contains("不存在"))
    }

    @Test
    fun badMagicFails() {
        val bad = validElf(); bad[0] = 0x00
        val v = PayloadVerifier.verify(writeElf(bad))
        assertTrue(v is PayloadVerifier.Verdict.Fail && v.reason.contains("magic"))
    }

    @Test
    fun x86MachineFails() {
        val v = PayloadVerifier.verify(writeElf(validElf(eMachine = 62)))
        assertTrue(v is PayloadVerifier.Verdict.Fail && v.reason.contains("AArch64"))
    }

    @Test
    fun nonPieFails() {
        val v = PayloadVerifier.verify(writeElf(validElf(eType = 2)))
        assertTrue(v is PayloadVerifier.Verdict.Fail && v.reason.contains("PIE"))
    }

    @Test
    fun tinyFileFails() {
        val v = PayloadVerifier.verify(writeElf(ByteArray(10)))
        assertTrue(v is PayloadVerifier.Verdict.Fail && v.reason.contains("过小"))
    }
}
