package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.UsageInsightEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 弹性解析结果：上游 schema 未钉死，只提取可安全展示的数字与标签。 */
internal data class InsightSummary(val title: String, val value: String)

internal object InsightParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** 从任意 JSON 形状中提取 (标签, 数值) 对；仅取字符串/长整型字段，深度 2 层以内。 */
    fun summarize(raw: String, maxEntries: Int = 6): List<InsightSummary> {
        if (raw.isBlank()) return emptyList()
        val element = try { json.parseToJsonElement(raw) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<InsightSummary>()
        fun walk(obj: JsonObject, prefix: String) {
            for ((key, value) in obj) {
                if (out.size >= maxEntries) return
                val label = if (prefix.isEmpty()) key else prefix + "." + key
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        val num = value.jsonPrimitive.longOrNull
                        if (num != null) out.add(InsightSummary(label, num.toString()))
                    }
                    is JsonObject -> if (prefix.count { it == '.' } < 1) walk(value, label)
                    is JsonArray -> {
                        // 数组只统计长度，不展开内容（避免泄露条目文本）。
                        out.add(InsightSummary(label, value.size.toString() + " 条"))
                    }
                }
            }
        }
        when (val e = element) {
            is JsonObject -> walk(e, "")
            is JsonArray -> out.add(InsightSummary("条目", e.size.toString() + " 条"))
        }
        return out
    }
}

@Composable
internal fun UsageInsightsCard(
    clientReady: Boolean,
    viewModel: UsageViewModel,
    usageState: UsageUiState,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("用量洞察（热力图与额度）", style = MaterialTheme.typography.titleMedium)
            Text(
                "来自核心只读统计端点（/api/usage/heatmap 与 /api/usage/quotas）。仅展示聚合数字与条目计数；不含请求正文或凭据。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(enabled = clientReady && !usageState.insightsLoading, onClick = viewModel::loadInsights) {
                Text(if (usageState.insightsLoading) "读取中…" else "读取用量洞察")
            }
            if (usageState.insightsError.isNotBlank()) {
                Text(usageState.insightsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val heatmapRows = remember(usageState.heatmapJson) { InsightParser.summarize(usageState.heatmapJson) }
            val quotaRows = remember(usageState.quotasJson) { InsightParser.summarize(usageState.quotasJson) }
            if (heatmapRows.isNotEmpty()) {
                Text("热力图概要", style = MaterialTheme.typography.titleSmall)
                heatmapRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.title, style = MaterialTheme.typography.bodySmall)
                        Text(row.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (quotaRows.isNotEmpty()) {
                Text("额度概要", style = MaterialTheme.typography.titleSmall)
                quotaRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.title, style = MaterialTheme.typography.bodySmall)
                        Text(row.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}