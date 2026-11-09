package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostMortemAuditorTest {

    @Test
    fun recordsAndReadsSanitizedReport(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        assertNull(auditor.readReport())

        val leakyMessage = "Process failed with key=sk-1234567890abcdef and token=ghp_abcdef1234567890"
        val report = auditor.recordAbnormalExit("BACKOFF", 137, leakyMessage)

        assertEquals("BACKOFF", report.lastKnownPhase)
        assertEquals(137, report.exitCode)
        assertFalse(report.sanitizedDetail.contains("sk-1234567890abcdef"))
        assertFalse(report.sanitizedDetail.contains("ghp_abcdef1234567890"))

        val readBack = auditor.readReport()
        assertNotNull(readBack)
        assertEquals(report.timestamp, readBack.timestamp)
        assertEquals(report.lastKnownPhase, readBack.lastKnownPhase)
        assertEquals(report.exitCode, readBack.exitCode)
        assertEquals(report.sanitizedDetail, readBack.sanitizedDetail)

        auditor.clear()
        assertNull(auditor.readReport())
    }

    @Test
    fun handlesCorruptFileGracefully(@TempDir tempDir: File) {
        val auditor = PostMortemAuditor(tempDir)
        val file = File(tempDir, PostMortemAuditor.AUDIT_FILE_NAME)
        file.writeText("invalid json content")

        assertNull(auditor.readReport())
        auditor.clear()
        assertFalse(file.exists())
    }
}
