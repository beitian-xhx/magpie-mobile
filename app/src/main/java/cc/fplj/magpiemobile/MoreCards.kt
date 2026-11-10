package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NoticesUiState(
    val loading: Boolean = false,
    val text: String = "",
    val error: String = "",
)

/** Loads upstream open-source license notices (GET /api/notices) on demand; display is bounded. */
class NoticesViewModel(
    private val reader: suspend () -> String = {
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().notices() }
    },
) : ViewModel() {
    private val mutable = MutableStateFlow(NoticesUiState())
    val state = mutable.asStateFlow()

    fun load() {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val text = reader()
                // 显示上限 64KB：许可证全文本在核心侧即为静态嵌入，超限仅截断展示。
                mutable.update { it.copy(loading = false, text = text.take(65536)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: "读取许可声明失败") }
            }
        }
    }
}

@Composable
internal fun LicenseNoticesCard(
    clientReady: Boolean,
    viewModel: NoticesViewModel,
    noticesState: NoticesUiState,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("开源许可声明", style = MaterialTheme.typography.titleMedium)
            Text(
                "核心二进制内嵌的第三方许可文本（GET /api/notices）。只读展示，上限 64KB。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(enabled = clientReady && !noticesState.loading, onClick = viewModel::load) {
                Text(if (noticesState.loading) "读取中…" else if (noticesState.text.isNotEmpty()) "重新读取" else "读取许可声明")
            }
            if (noticesState.error.isNotBlank()) {
                Text(noticesState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (noticesState.text.isNotEmpty()) {
                Text(
                    noticesState.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().height(280.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}