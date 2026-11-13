package cc.fplj.magpiemobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.*
import cc.fplj.magpiemobile.theme.MagpieTheme
import java.time.LocalDate

/** Explicit fake-data harness for visual testing on x86, never packaged in release. */
class UsageFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = UsageDashboardParser.overview("""{"calls":42,"errors":2,"retried":1,"canceled":1,"input":120000,"output":24000,"cache_read":600000,"cache_write":3000,"reasoning":1000,"cost":0.1234,"unpriced":1,"timed":40,"ttft_ms":60000,"decode_ms":30000,"decode_out":24000,"models":[{"id":"workbuddy/deepseek-v4.1-flash","provider":"workbuddy","model":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","sub":"WorkBuddy","calls":42,"input":120000,"output":24000,"cache_read":600000}],"series":[{"label":"09:00","calls":2,"input":1000},{"label":"10:00","calls":5,"input":4000},{"label":"11:00","calls":3,"input":2500},{"label":"12:00","calls":12,"input":8000}]}""")
        val quotas = UsageDashboardParser.quotas("""[{"provider":"workbuddy","name":"WorkBuddy","user":"sample-account","plan":"Free","readAt":"2026-11-13T16:00:00Z","windows":[{"name":"Credits","used":25.5,"amount":25.5,"limit":100,"unit":"credits","resetsAt":"2026-11-14T16:00:00Z"},{"name":"Daily status","text":true,"display":"Signed in"}]}]""")
        val rows = (1..20).map { UsageRecord(timestamp = "2026-11-13T16:00:00Z", model = "deepseek-v4.1-flash", provider = "workbuddy", statusCode = if (it == 2) 429 else 200, durationMs = 2200, inputTokens = 1200, outputTokens = 400, cacheRead = 18000, ttftMs = 900, firstTextMs = 1200, error = if (it == 2) "Vendor rate limit; try later" else "") }
        val vm = UsageDashboardViewModel(
            overviewReader = { sample }, ledgerReader = { q, _ -> UsageLedger(if (q.failed) rows.filter { it.statusCode >= 400 } else rows, if (q.failed) 1 else 42, sample.numbers, listOf("workbuddy" to "WorkBuddy")) },
            quotasReader = { quotas }, heatmapReader = { (0..90).map { HeatDayData(LocalDate.now().minusDays(it.toLong()).toString(), (it % 7 + 1).toLong(), (it * 100).toLong()) } },
            historyReader = { listOf(QuotaHistoryData("workbuddy", "sample-account", "Credits", listOf("2026-11-12T16:00:00Z" to 95.0, "2026-11-13T16:00:00Z" to 74.5))) },
        )
        setContent {
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.refresh() }
            MagpieTheme(darkTheme = intent.getBooleanExtra("dark", false)) {
                Surface {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("测试数据 · 不连接供应商")
                        UsageDashboardContent(true, vm, state)
                    }
                }
            }
        }
    }
}
