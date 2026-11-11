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
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** 弹性解析结果：上游 schema 未钉死，只提取可安全展示的数字与条目计数。 */
internal data class InsightSummary(val title: String, val value: String)

internal object InsightParser {
    /** 从任意 JSON 形状中提取 (标签, 数值) 对；仅取长整型字段，对象深度 2 层以内；数组只报条数。 */
    fun summarize(raw: String, maxEntries: Int = 6): List<InsightSummary> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<InsightSummary>()
        try {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                out.add(InsightSummary("条目", JSONArray(trimmed).length().toString() + " 条"))
            } else if (trimmed.startsWith("{")) {
                walk(JSONObject(trimmed), "", out, maxEntries)
            }
        } catch (_: Exception) {
            // 非 JSON 或超限：展示为空，绝不呈现原始正文。
        }
        return out
    }

    private fun walk(obj: JSONObject, prefix: String, out: MutableList<InsightSummary>, maxEntries: Int) {
        for (key in obj.keys()) {
            if (out.size >= maxEntries) return
            val label = if (prefix.isEmpty()) key else prefix + "." + key
            when (val value = obj.opt(key)) {
                is Number -> out.add(InsightSummary(label, value.toString()))
                is JSONObject -> if (prefix.count { it == '.' } < 1) walk(value, label, out, maxEntries)
                is JSONArray -> out.add(InsightSummary(label, value.length().toString() + " 条"))
                else -> {} // 字符串/布尔/空值一律不展示（避免泄露文本内容）
            }
        }
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
            val summaryRows = remember(usageState.summaryJson) { InsightParser.summarize(usageState.summaryJson) }
            val heatmapRows = remember(usageState.heatmapJson) { InsightParser.summarize(usageState.heatmapJson) }
            val quotaRows = remember(usageState.quotasJson) { InsightParser.summarize(usageState.quotasJson) }
            val historyRows = remember(usageState.historyJson) { InsightParser.summarize(usageState.historyJson) }
            if (summaryRows.isNotEmpty()) {
                Text("聚合概要（今日）", style = MaterialTheme.typography.titleSmall)
                summaryRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.title, style = MaterialTheme.typography.bodySmall)
                        Text(row.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
            if (historyRows.isNotEmpty()) {
                Text("额度历史概要", style = MaterialTheme.typography.titleSmall)
                historyRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.title, style = MaterialTheme.typography.bodySmall)
                        Text(row.value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * A5：本周模型速度排行（GET /api/model-speeds）。
 * 展示 provider/model → 首 token 平均耗时（毫秒）与后续输出速度；只取聚合数字。
 */
@Composable
internal fun ModelSpeedsCard(usageState: UsageUiState) {
    if (usageState.speeds.isEmpty()) return
    val rows = usageState.speeds.entries
        .filter { it.value.ttft > 0 || it.value.tps > 0 }
        .sortedWith(compareBy({ if (it.value.ttft > 0) 0 else 1 }, { it.value.ttft }))
        .take(6)
    if (rows.isEmpty()) return
    SectionCard(title = "本周模型速度排行") {
        Text("按近 7 天定时应答统计；只展示聚合数字，不含请求正文或凭据。", style = MaterialTheme.typography.bodySmall)
        rows.forEach { (ref, s) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ref, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                val tps = if (s.tps > 0) ((s.tps * 10).roundToInt() / 10.0).toString() + " tok/s" else "—"
                Text((if (s.ttft > 0) s.ttft.toString() + "ms 首token" else "—") + " · " + tps, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * A4：模型目录（GET /api/catalog/models，models.dev 已同步子集）。
 * 只读元数据：条目总数 + 前 8 条 id/名称/上下文窗口。
 */
@Composable
internal fun CatalogCard(usageState: UsageUiState) {
    if (usageState.catalog.isEmpty()) return
    SectionCard(title = "模型目录（models.dev 已同步）") {
        Text("共 " + usageState.catalog.size + " 个去重条目；价格为参考，不涉及任何凭据。", style = MaterialTheme.typography.bodySmall)
        usageState.catalog.take(8).forEach { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    m.id + if (m.name.isNotBlank()) " · " + m.name else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Text(if (m.context > 0) (m.context / 1000).toString() + "K ctx" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}