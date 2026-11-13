package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PC-style black-and-white account panel. Sign in with the WorkBuddy / Qoder
 * account you already have -- no API key or PAT. Feedback appears directly
 * underneath its own button.
 */
@Composable
internal fun MagpieProvidersCard(
    viewModel: MagpieProvidersViewModel,
    state: MagpieProvidersUiState,
    gatewayActive: Boolean,
) {
    var workbuddyProvider by remember { mutableStateOf("workbuddy") }
    var qoderProvider by remember { mutableStateOf("qoder") }

    MonoPanel("Accounts / 账号接入") {
        Text(
            "点击后用系统浏览器登录你已有的 WorkBuddy / Qoder 账号并授权，插件会自动完成登录；无需 API Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (gatewayActive) "网关正在运行。建议先停止网关；登录完成后重启网关生效。"
            else "网关已停止。登录完成后启动网关，模型会出现在下方列表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (state.bunReady) "Bun runtime · " + state.bunDetail.ifBlank { "ready" }
            else "Bun runtime · " + state.bunDetail.ifBlank { "unavailable" },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.bunReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text("WorkBuddy", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = workbuddyProvider == "workbuddy",
                onClick = { workbuddyProvider = "workbuddy" },
                label = { Text("国服") },
            )
            FilterChip(
                selected = workbuddyProvider == "workbuddy-ai",
                onClick = { workbuddyProvider = "workbuddy-ai" },
                label = { Text("国际版") },
            )
        }
        Button(
            enabled = !state.busy,
            onClick = {
                val label = if (workbuddyProvider == "workbuddy") "WorkBuddy account (browser)"
                else "WorkBuddy AI account (browser)"
                viewModel.loginBrowser("workbuddy", workbuddyProvider, label)
            },
        ) {
            Text(if (state.busy && state.action == "workbuddy") "处理中…" else "用 WorkBuddy 账号登录")
        }
        if (state.action == "workbuddy") {
            LoginFeedback(state)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Qoder", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = qoderProvider == "qoder",
                onClick = { qoderProvider = "qoder" },
                label = { Text("国际版") },
            )
            FilterChip(
                selected = qoderProvider == "qoder-cn",
                onClick = { qoderProvider = "qoder-cn" },
                label = { Text("国服") },
            )
        }
        Button(
            enabled = !state.busy,
            onClick = {
                val label = if (qoderProvider == "qoder") "Sign in with Qoder" else "Sign in with Qoder CN"
                viewModel.loginBrowser("qoder", qoderProvider, label)
            },
        ) {
            Text(if (state.busy && state.action == "qoder") "处理中…" else "用 Qoder 账号登录")
        }
        if (state.action == "qoder") {
            LoginFeedback(state)
        }
        if (state.busy) {
            TextButton(onClick = viewModel::cancelLogin) { Text("取消登录") }
        }
    }
}

@Composable
private fun LoginFeedback(state: MagpieProvidersUiState) {
    if (state.stage.isNotBlank()) {
        Text("登录进度：" + state.stage, style = MaterialTheme.typography.bodySmall)
    }
    if (state.notice.isNotBlank()) {
        Text(state.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
    if (state.error.isNotBlank()) {
        Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
