package cc.fplj.magpiemobile.data

import cc.fplj.magpiemobile.core.SessionRecord
import cc.fplj.magpiemobile.core.StreamingChatFailure
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal object ChatRecordPolicy {
    private val httpFailure = Regex("HTTP [1-5][0-9]{2}")
    private val sensitiveEngine = Regex("(?i)(cookie|bearer|authorization|api[_-]?key|access[_-]?token|(^|[:/])sk-)")

    fun engineIsIdentifier(engine: String): Boolean = engine.length <= 256 &&
        !engine.contains("://") && !sensitiveEngine.containsMatchIn(engine) &&
        engine.all { it.isLetterOrDigit() || it in "._:/+-" }

    fun safeFailure(text: String): Boolean {
        if (httpFailure.matches(text)) return true
        val separator = "; partial characters="
        val index = text.indexOf(separator)
        if (index < 0) return false
        val category = text.substring(0, index)
        val count = text.substring(index + separator.length)
        val length = count.toIntOrNull() ?: return false
        return length >= 0 && length.toString() == count &&
            StreamingChatFailure.entries.any { it.safeMessage == category }
    }

    fun legacyRecord(line: String): SessionRecord? {
        // Use the core strict JSON parser, then require the actual JSON field types.
        // Its backward-compatible optional engine field is supported, never credentials.
        val record = SessionRecord.fromJsonLine(line) ?: return null
        return try {
            val json = JSONObject(line)
            val ts = (json.get("ts") as? Number)?.toString()?.toLongOrNull() ?: return null
            if (ts < 0 || json.get("role") !is String || json.get("text") !is String) return null
            if (json.has("engine") && json.get("engine") !is String) return null
            if (record.role !in setOf("user", "assistant", "error", "system")) return null
            if (!engineIsIdentifier(record.engine)) return null
            // Old error records are never inference input, but must not become an
            // archive of arbitrary upstream response bodies either.
            if (record.role == "error" && !safeFailure(record.text) &&
                StreamingChatFailure.entries.none { it.safeMessage == record.text }) return null
            record
        } catch (_: Exception) {
            null
        }
    }
}

internal class LegacyFileTooLarge : IOException()

/** Bounded raw byte input, including when the file grows after the initial length check. */
private class BoundedLegacyInput(input: InputStream) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = `in`.read(buffer, offset, length)
        if (read > 0) account(read)
        return read
    }

    private fun account(read: Int) {
        count += read
        if (count > LegacyChatImporter.MAX_BYTES) throw LegacyFileTooLarge()
    }
}

internal object LegacyChatImporter {
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_LINE_CHARS = 256 * 1024
    const val BAD_LINES_WARNING = "部分旧记录无法导入，原文件已保留。"
    const val TOO_LARGE_WARNING = "旧记录文件过大，未导入；原文件已保留，可稍后重试。"
    const val READ_WARNING = "无法读取旧记录，未导入；原文件已保留，可稍后重试。"

    /** Inserts through the caller's transaction; no readLines or full-file buffering. */
    fun scan(file: File, accept: (SessionRecord) -> Unit): LegacyImportReport {
        if (file.length() > MAX_BYTES) throw LegacyFileTooLarge()
        var imported = 0
        var skipped = 0
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        BufferedReader(InputStreamReader(BoundedLegacyInput(file.inputStream()), decoder)).use { reader ->
            val buffer = CharArray(8192)
            val line = StringBuilder()
            var tooLong = false
            fun emit() {
                if (tooLong) {
                    skipped++
                } else {
                    if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                    val record = ChatRecordPolicy.legacyRecord(line.toString())
                    if (record == null) skipped++ else {
                        accept(record)
                        imported++
                    }
                }
                line.setLength(0)
                tooLong = false
            }
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = buffer[index]
                    if (char == '\n') emit()
                    else if (!tooLong) {
                        if (line.length == MAX_LINE_CHARS) {
                            tooLong = true
                            line.setLength(0)
                        } else line.append(char)
                    }
                }
            }
            if (line.isNotEmpty() || tooLong) emit()
        }
        return LegacyImportReport(imported, skipped, if (skipped > 0) BAD_LINES_WARNING else null)
    }
}
