package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
            if (gatewayActive) "网关正在运行。登录前请先在上方停止网关；否则登录按钮会提示。"
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
                when {
                    gatewayActive -> viewModel.showError("请先停止网关并等待停止完成，再登录 WorkBuddy。")
                    workbuddyKey.isBlank() -> viewModel.showError("请输入 WorkBuddy API Key。")
                    else -> {
                        viewModel.login(workbuddyProvider, "api", workbuddyKey)
                        workbuddyKey = ""
                    }
                }
            },
        ) {
            Text(if (state.busy) "登录中…" else "登录 WorkBuddy")
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
                when {
                    gatewayActive -> viewModel.showError("请先停止网关并等待停止完成，再登录 Qoder。")
                    qoderPat.isBlank() -> viewModel.showError("请输入 Qoder PAT。")
                    else -> {
                        val method = if (qoderProvider == "qoder") "Sign in with Qoder PAT" else "Sign in with Qoder CN PAT"
                        viewModel.login(qoderProvider, method, qoderPat)
                        qoderPat = ""
                    }
                }
            },
        ) {
            Text(if (state.busy) "登录中…" else "登录 Qoder")
        }
        if (state.busy && state.stage.isNotEmpty()) {
            Text("登录进度：" + state.stage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.notice.isNotEmpty()) {
            Text(state.notice, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
        }
        if (state.error.isNotEmpty()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
