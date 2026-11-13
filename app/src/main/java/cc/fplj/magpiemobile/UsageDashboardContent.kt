package cc.fplj.magpiemobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun UsageDashboardContent(ready: Boolean, vm: UsageDashboardViewModel, state: UsageDashboardState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("用量", style = MaterialTheme.typography.headlineSmall)
        TextButton(enabled = ready, onClick = { vm.refresh(asked = true) }) { Text("刷新全部") }
    }
    if (!ready) {
        Text("启动网关并完成连接后查看本机真实用量。", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("today" to "今日", "7d" to "7 天", "30d" to "30 天", "all" to "全部").forEach { (id, label) ->
            FilterChip(selected = state.query.period == id, onClick = { vm.select(state.query.copy(period = id)) }, label = { Text(label) })
        }
    }
    Text("${state.query.zone} · 页面停留时每 10 秒更新本地统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MonoPanel("Overview / 概览") {
        SectionStatus(state.overview)
        state.overview.data?.let { overview ->
            TotalsContent(overview.numbers)
            if (overview.series.isNotEmpty()) {
                HorizontalDivider()
                Text("Token 趋势（包含缓存）", style = MaterialTheme.typography.titleSmall)
                NumberBars(overview.series.map { it.numbers.tokens.toDouble() }, "Token 趋势")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(overview.series.first().label, style = MaterialTheme.typography.labelSmall)
                    Text(overview.series.last().label, style = MaterialTheme.typography.labelSmall)
                }
                var showTrend by remember { mutableStateOf(false) }
                TextButton(onClick = { showTrend = !showTrend }) { Text(if (showTrend) "收起数据" else "查看逐时 / 逐日数据") }
                if (showTrend) overview.series.filter { it.numbers.calls > 0 }.forEach { point ->
                    Metric(point.label, "${count(point.numbers.calls)} 次 · ${count(point.numbers.tokens)} Token")
                }
            }
        }
    }
    state.overview.data?.let { overview ->
        GroupPanel("Models / 按模型", overview.models) { g ->
            vm.select(state.query.copy(provider = g.provider, model = if (g.provider.isNotBlank() && g.model.isNotBlank()) "${g.provider}/${g.model}" else g.id))
        }
        GroupPanel("Accounts / 按账号", overview.accounts)
        GroupPanel("Clients / 按客户端", overview.agents)
        GroupPanel("Gateway keys / 按网关密钥", overview.keys)
    }
    QuotasContent(state, vm)
    HeatmapContent(state.heatmap)
    LedgerContent(state, vm)
}

@Composable
private fun TotalsContent(n: UsageNumbers) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(count(n.tokens), style = MaterialTheme.typography.headlineSmall)
            Text("Token（含缓存）", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(count(n.calls), style = MaterialTheme.typography.headlineSmall)
            Text("请求", style = MaterialTheme.typography.labelMedium)
        }
    }
    Metric("输入 / 输出", "${count(n.input)} / ${count(n.output)}")
    Metric("缓存读取 / 写入", "${count(n.cacheRead)} / ${count(n.cacheWrite)}")
    Metric("缓存命中率", n.cacheHit?.let { "${decimal(it)}%" } ?: "未测得")
    Metric("推理 Token（已含在输出中）", count(n.reasoning))
    Metric("失败 / 重试 / 取消", "${n.errors} / ${n.retried} / ${n.canceled}")
    Metric("参考费用 USD", "$${String.format(Locale.US, "%.4f", n.cost)}")
    if (n.unpriced > 0) Text("${n.unpriced} 次请求尚无价格，未计入参考费用；订阅实际扣费以供应商为准。", style = MaterialTheme.typography.bodySmall)
    Metric("平均首内容等待", n.meanTtftMs?.let { seconds(it) } ?: "未测得")
    Metric("输出速度", n.tokensPerSecond?.let { "${decimal(it)} Token/s" } ?: "未测得")
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> SectionStatus(section: UsageSection<T>) {
    if (section.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (section.error.isNotBlank()) Text(
        (if (section.data != null) "更新失败，保留上次读数：" else "读取失败：") + section.error,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    )
    if (section.updatedAt > 0) Text("更新于 ${localStamp(Instant.ofEpochMilli(section.updatedAt).toString())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GroupPanel(title: String, groups: List<UsageGroupData>, select: ((UsageGroupData) -> Unit)? = null) {
    if (groups.isEmpty()) return
    var expanded by remember(title) { mutableStateOf(false) }
    MonoPanel(title) {
        val max = groups.maxOfOrNull { it.numbers.tokens }?.coerceAtLeast(1) ?: 1
        (if (expanded) groups else groups.take(5)).forEach { g ->
            Column(Modifier.fillMaxWidth().then(if (select != null) Modifier.clickable { select(g) }.padding(vertical = 8.dp) else Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.name.ifBlank { g.id.ifBlank { "账号未记录" } }, style = MaterialTheme.typography.bodyMedium)
                if (g.sub.isNotBlank()) Text(g.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (g.numbers.tokens.toFloat() / max).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Metric("${g.numbers.calls} 次 · ${g.numbers.errors} 失败", "${count(g.numbers.tokens)} Token")
                Metric("首内容 / 输出", "${g.numbers.meanTtftMs?.let(::seconds) ?: "—"} / ${g.numbers.tokensPerSecond?.let { decimal(it) + " Token/s" } ?: "—"}")
            }
        }
        if (groups.size > 5) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部 ${groups.size} 项") }
        if (select != null) Text("点选模型可筛选下方请求明细", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QuotasContent(state: UsageDashboardState, vm: UsageDashboardViewModel) {
    MonoPanel("Allowances / 账号额度") {
        Text("供应商读数与本机 Token 统计分开计算。额度独立读取，不阻塞下方请求明细。", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !state.quotas.loading, onClick = { vm.refreshQuotas() }) { Text("刷新账号额度") }
        SectionStatus(state.quotas)
        if (state.quotas.data?.isEmpty() == true) Text("暂无可读取的账号额度。", style = MaterialTheme.typography.bodySmall)
        state.quotas.data?.forEach { card ->
            HorizontalDivider()
            Text(card.name.ifBlank { card.provider }, style = MaterialTheme.typography.titleMedium)
            if (card.user.isNotBlank()) Text(card.user, style = MaterialTheme.typography.bodySmall)
            if (card.plan.isNotBlank()) Metric("订阅", card.plan)
            if (card.balance.isNotBlank()) Metric("余额", card.balance)
            if (card.readAt.isNotBlank()) Metric("供应商读数时间", localStamp(card.readAt))
            if (card.staleAt.isNotBlank()) Text("当前为缓存读数 · ${localStamp(card.staleAt)}", style = MaterialTheme.typography.labelSmall)
            if (card.deferred) Text("尚未向供应商重新查询，可点刷新。", style = MaterialTheme.typography.bodySmall)
            if (card.error.isNotBlank()) Text(card.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            card.windows.forEach { w ->
                val used = w.used
                val balance = w.balance
                val limit = w.limit
                Text(w.name, style = MaterialTheme.typography.titleSmall)
                when {
                    w.unlimited -> Text("不限量", style = MaterialTheme.typography.bodySmall)
                    w.text -> Text(w.display.ifBlank { "供应商未提供数值" }, style = MaterialTheme.typography.bodySmall)
                    balance != null -> Metric("剩余", "${decimal(balance)} ${w.unit}")
                    used != null -> {
                        LinearProgressIndicator(progress = { (used / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Metric("已用 ${decimal(used)}%", "剩余 ${decimal((100 - used).coerceAtLeast(0.0))}%")
                        if (limit != null && limit > 0) Metric("用量 / 上限", "${decimal(w.amount ?: 0.0)} / ${decimal(limit)} ${w.unit}")
                        if (w.display.isNotBlank()) Text(w.display, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text(w.display.ifBlank { "未提供额度数值" }, style = MaterialTheme.typography.bodySmall)
                }
                if (w.resetsAt.isNotBlank()) Metric("重置", localStamp(w.resetsAt))
                else if (w.resetSecs > 0) Metric("重置倒计时（读取时）", "${w.resetSecs / 60} 分钟")
                if (w.runsOutAt.isNotBlank()) Metric("最近到期", localStamp(w.runsOutAt))
            }
            state.history.data.orEmpty().filter { it.provider == card.provider && (card.user.isBlank() || it.user.equals(card.user, true)) }.forEach { h ->
                if (h.points.size >= 2) {
                    Text("${h.name} · 近 7 天剩余额度", style = MaterialTheme.typography.labelSmall)
                    LineGraph(h.points.map { it.second }, "${h.name} 剩余额度百分比")
                    Metric(localStamp(h.points.first().first), localStamp(h.points.last().first))
                    Metric("起点 / 最新", "${decimal(h.points.first().second)}% / ${decimal(h.points.last().second)}%")
                }
            }
        }
        if (state.history.error.isNotBlank()) Text("额度历史：${state.history.error}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HeatmapContent(section: UsageSection<List<HeatDayData>>) {
    MonoPanel("Activity / 请求日历") {
        SectionStatus(section)
        val days = section.data.orEmpty()
        if (days.isEmpty() && !section.loading) Text("暂无历史请求。", style = MaterialTheme.typography.bodySmall)
        else {
            val end = LocalDate.now()
            val start = end.minusWeeks(52).with(java.time.DayOfWeek.MONDAY)
            val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            val byDate = days.associateBy { it.date }
            val max = days.maxOfOrNull { it.calls }?.coerceAtLeast(1) ?: 1
            val ink = MaterialTheme.colorScheme.onSurface
            Text("近 53 周 · 周一至周日 · 色块越深请求越多", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Canvas(Modifier.width(650.dp).height(90.dp).semantics { contentDescription = "53 周请求日历，共 ${days.sumOf { it.calls }} 次请求" }) {
                    val cell = size.height / 7
                    dates.forEachIndexed { i, date ->
                        val calls = byDate[date.toString()]?.calls ?: 0
                        val alpha = if (calls == 0L) .07f else (.25 + .75 * calls / max).toFloat()
                        drawRect(ink.copy(alpha = alpha), Offset(i / 7 * cell, i % 7 * cell), Size(cell - 2, cell - 2))
                    }
                }
            }
            Metric(start.toString(), end.toString())
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起日期数据" else "查看活跃日期数据") }
            if (expanded) days.asReversed().forEach { Metric(it.date, "${it.calls} 次 · ${count(it.tokens)} Token") }
        }
    }
}

@Composable
private fun LedgerContent(state: UsageDashboardState, vm: UsageDashboardViewModel) {
    val q = state.query
    var search by remember(q.search) { mutableStateOf(q.search) }
    var model by remember(q.model) { mutableStateOf(q.model) }
    var day by remember(q.day) { mutableStateOf(q.day) }
    var invalidDay by remember { mutableStateOf(false) }
    MonoPanel("Requests / 请求明细") {
        Text("时间范围内的完整记录，可筛选、翻页并展开查看耗时分解。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索模型 / 供应商") }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型 ID（留空为全部）") }, singleLine = true)
        OutlinedTextField(day, { day = it; invalidDay = false }, Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD（可留空）") }, isError = invalidDay, singleLine = true)
        if (invalidDay) Text("请输入有效日期，例如 2026-11-14。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(q.failed, { vm.select(q.copy(failed = !q.failed)) }, label = { Text("只看失败") })
            TextButton(onClick = { vm.select(UsageQuery(period = q.period, zone = q.zone)); search = ""; model = ""; day = "" }) { Text("清空筛选") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(q.provider.isEmpty(), { vm.select(q.copy(provider = "")) }, label = { Text("全部供应商") })
            state.ledger.data?.providers.orEmpty().forEach { (id, name) ->
                FilterChip(q.provider == id, { vm.select(q.copy(provider = id)) }, label = { Text(name.ifBlank { id }) })
            }
            if (q.provider.isNotBlank()) Text("当前：${q.provider}", style = MaterialTheme.typography.labelSmall)
        }
        Button(onClick = {
            invalidDay = day.isNotBlank() && runCatching { LocalDate.parse(day.trim()) }.isFailure
            if (!invalidDay) {
                val next = q.copy(search = search.trim(), model = model.trim(), day = day.trim())
                if (next == q) vm.refreshLocal() else vm.select(next)
            }
        }) { Text("应用筛选") }
        SectionStatus(state.ledger)
        state.ledger.data?.let { ledger ->
            Text("筛选结果 · ${ledger.total} 次请求", style = MaterialTheme.typography.titleSmall)
            Metric("Token / 参考费用", "${count(ledger.numbers.tokens)} / $${String.format(Locale.US, "%.4f", ledger.numbers.cost)}")
            if (ledger.requests.isEmpty()) Text("当前筛选下没有请求记录。", style = MaterialTheme.typography.bodySmall)
            ledger.requests.forEachIndexed { index, req ->
                key(state.offset, index, req.timestamp) { RequestRow(req) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(enabled = state.offset > 0 && !state.ledger.loading, onClick = { vm.page((state.offset - 20).coerceAtLeast(0)) }) { Text("上一页") }
                Text("${state.offset / 20 + 1} / ${((ledger.total + 19) / 20).coerceAtLeast(1)}", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = state.offset + 20 < ledger.total && !state.ledger.loading, onClick = { vm.page(state.offset + 20) }) { Text("下一页") }
            }
        }
    }
}

@Composable
private fun RequestRow(req: UsageRecord) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(req.model, style = MaterialTheme.typography.bodyMedium)
        Metric(req.provider, if (req.canceled) "已取消" else "HTTP ${req.statusCode}")
        Metric(localStamp(req.timestamp), seconds(req.durationMs))
        Metric("Token / 首内容", "${count(req.inputTokens + req.outputTokens + req.cacheRead + req.cacheWrite)} / ${if (req.ttftMs > 0) seconds(req.ttftMs) else "未测得"}")
        if (req.error.isNotBlank()) Text(req.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(if (open) "收起详情 ▴" else "展开详情 ▾", style = MaterialTheme.typography.labelSmall)
        if (open) {
            Metric("输入 / 输出 / 推理", "${count(req.inputTokens)} / ${count(req.outputTokens)} / ${count(req.reasoningTokens)}")
            Metric("缓存读 / 写", "${count(req.cacheRead)} / ${count(req.cacheWrite)}")
            Metric("网关发送前 / 排队 / 重试", "${seconds(req.sentMs)} / ${seconds(req.queuedMs)} / ${seconds(req.retriedMs)}")
            Metric("首正文等待", if (req.firstTextMs > 0) seconds(req.firstTextMs) else "未测得")
            Text("首内容包括推理或工具调用；首正文是用户可见文本。0 表示本次记录未提供该项计时。", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NumberBars(values: List<Double>, label: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "$label，${values.size} 个时间点" }) {
        val max = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val width = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val height = (v / max * (size.height - 1)).toFloat()
            drawRect(ink, Offset(i * width, size.height - height), Size((width - 2).coerceAtLeast(1f), height))
        }
        drawLine(ink.copy(alpha = .3f), Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
    }
}

@Composable
private fun LineGraph(values: List<Double>, label: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = label }) {
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i.toFloat() / (values.size - 1).coerceAtLeast(1) * size.width
            val y = size.height * (1 - v.coerceIn(0.0, 100.0).toFloat() / 100)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

private fun count(n: Long): String = String.format(Locale.US, "%,d", n)
private fun decimal(n: Double): String = String.format(Locale.US, "%.1f", n)
private fun seconds(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)
private fun localStamp(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) }.getOrDefault(value.take(30))
