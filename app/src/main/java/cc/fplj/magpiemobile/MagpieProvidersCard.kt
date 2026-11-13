package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
) {
    var workbuddyProvider by remember { mutableStateOf("workbuddy") }
    var workbuddyKey by remember { mutableStateOf("") }
    var qoderProvider by remember { mutableStateOf("qoder") }
    var qoderPat by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WorkBuddy / Qoder 模型接入", style = MaterialTheme.typography.titleMedium)
            Text(
                "已内置 Magpie 官方 WorkBuddy 与 Qoder 插件。登录前请先停止网关；登录成功后重新启动网关，即可在模型列表中看到对应模型。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (state.bunReady) "Bun 插件运行时：就绪" else "Bun 插件运行时：未就绪（请确认安装的是 arm64 APK）",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.bunReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            HorizontalDivider()
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
                label = { Text("WorkBuddy API Key（/profile/keys 创建）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !state.busy && workbuddyKey.isNotBlank(),
                onClick = {
                    viewModel.login(workbuddyProvider, "api", workbuddyKey)
                    workbuddyKey = ""
                },
            ) {
                Text(if (state.busy) "登录中…" else "登录 WorkBuddy")
            }
            HorizontalDivider()
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
                label = { Text("Qoder PAT（/account/integrations 创建）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !state.busy && qoderPat.isNotBlank(),
                onClick = {
                    val method = if (qoderProvider == "qoder") "Sign in with Qoder PAT" else "Sign in with Qoder CN PAT"
                    viewModel.login(qoderProvider, method, qoderPat)
                    qoderPat = ""
                },
            ) {
                Text(if (state.busy) "登录中…" else "登录 Qoder")
            }
            if (state.notice.isNotEmpty()) {
                Text(state.notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            if (state.error.isNotEmpty()) {
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
