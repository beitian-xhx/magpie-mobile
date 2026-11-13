package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * PC-style black-and-white account panel. Feedback for each action is rendered
 * immediately underneath its own button, so it is visible without scrolling
 * back to the top of the long page.
 */
@Composable
internal fun MagpieProvidersCard(
    viewModel: MagpieProvidersViewModel,
    state: MagpieProvidersUiState,
    gatewayActive: Boolean,
) {
    var workbuddyProvider by remember { mutableStateOf("workbuddy") }
    var workbuddyKey by remember { mutableStateOf("") }
    var qoderProvider by remember { mutableStateOf("qoder") }
    var qoderPat by remember { mutableStateOf("") }

    MonoPanel("Accounts / 账号接入") {
        Text(
            if (gatewayActive) "网关正在运行。建议先停止网关再登录；写入凭据后必须重启网关才会生效。"
            else "网关已停止，可以登录。登录成功后启动网关，模型会出现在下方列表。",
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
        OutlinedTextField(
            value = workbuddyKey,
            onValueChange = { workbuddyKey = it },
            label = { Text("WorkBuddy API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !state.busy,
            onClick = {
                viewModel.loginWorkbuddy(workbuddyProvider, workbuddyKey)
                workbuddyKey = ""
            },
        ) {
            Text(if (state.busy && state.action == "workbuddy") "处理中…" else "登录 WorkBuddy")
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
        OutlinedTextField(
            value = qoderPat,
            onValueChange = { qoderPat = it },
            label = { Text("Qoder PAT") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !state.busy,
            onClick = {
                val method = if (qoderProvider == "qoder") "Sign in with Qoder PAT" else "Sign in with Qoder CN PAT"
                viewModel.loginQoder(qoderProvider, method, qoderPat)
                qoderPat = ""
            },
        ) {
            Text(if (state.busy && state.action == "qoder") "处理中…" else "登录 Qoder")
        }
        if (state.action == "qoder") {
            LoginFeedback(state)
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
