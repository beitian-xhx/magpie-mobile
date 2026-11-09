package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PostMortemReport(
    val timestamp: Long,
    val lastKnownPhase: String,
    val exitCode: Int,
    val sanitizedDetail: String,
)

/**
 * Persists and audits abnormal process termination / crashes without secret leakage (P4-2).
 * Enables cold-start diagnosis and post-mortem analysis for background resilience.
 */
class PostMortemAuditor(private val storageDir: File) {

    private val auditFile = File(storageDir, AUDIT_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        storageDir.mkdirs()
    }

    @Synchronized
    fun recordAbnormalExit(phase: String, exitCode: Int, detail: String): PostMortemReport {
        val sanitized = SecretRedactor.redact(detail).take(2048)
        val report = PostMortemReport(
            timestamp = System.currentTimeMillis(),
            lastKnownPhase = phase,
            exitCode = exitCode,
            sanitizedDetail = sanitized,
        )

        try {
            val tempFile = File(storageDir, "$AUDIT_FILE_NAME.tmp")
            tempFile.writeText(json.encodeToString(report), Charsets.UTF_8)
            tempFile.renameTo(auditFile)
        } catch (_: Exception) {
            // Failure to write post-mortem report must not crash the process
        }
        return report
    }

    @Synchronized
    fun readReport(): PostMortemReport? {
        if (!auditFile.exists() || !auditFile.isFile) return null
        return try {
            val text = auditFile.readText(Charsets.UTF_8)
            json.decodeFromString<PostMortemReport>(text)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        if (auditFile.exists()) {
            auditFile.delete()
        }
    }

    companion object {
        const val AUDIT_FILE_NAME = "post_mortem.json"
    }
}
