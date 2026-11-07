package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.ManagementExecutionUnknown
import cc.fplj.magpiemobile.core.ManagementHttpException
import cc.fplj.magpiemobile.core.ProviderCreateDraft
import cc.fplj.magpiemobile.core.ProviderEditDraft
import cc.fplj.magpiemobile.core.ProviderEditSnapshot
import cc.fplj.magpiemobile.core.ProviderListSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provider metadata is UI state. The API key exists only in this unsaved password field/draft. */
@Composable
fun ProvidersPanel(enabled: Boolean, onProvidersChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    var list by remember { mutableStateOf<ProviderListSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("先连接核心，再读取真实提供商配置") }
    var search by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var editSnapshot by remember { mutableStateOf<ProviderEditSnapshot?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var chatUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") } // intentionally not rememberSaveable
    var modelsText by remember { mutableStateOf("") }
    var modelsTouched by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(emptyList<String>()) }
    var allowLoopbackHttp by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var writeUncertain by remember { mutableStateOf(false) }

    fun modelIds(): List<String> = modelsText.split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun errorMessage(e: Exception): String = when {
        e is cc.fplj.magpiemobile.core.ProviderConfigurationUnreadableException -> "核心提供商文件异常，已拒绝写入；请保留文件并排查。"
        e is cc.fplj.magpiemobile.core.ProviderRevisionConflictException -> "核心配置版本已变化，未覆盖；请重新打开编辑快照。"
        e is cc.fplj.magpiemobile.core.ProviderUnsupportedException -> "当前仅能编辑自定义API Key/OpenAI Chat渠道，此类型原配置保持。"
        e is cc.fplj.magpiemobile.core.ProviderEditPreconditionException -> "没有修改字段或编辑版本无效，请重新读取后修改。"
        e is ManagementExecutionUnknown -> "保存结果不确定：请刷新列表并重新打开编辑，不要重复保存或重建提供商。"
        e is ManagementHttpException && e.httpCode == 409 -> "核心配置已变化，保存被拒绝；请重新读取后编辑，未覆盖新配置。"
        e is ManagementHttpException -> "管理操作失败：HTTP " + e.httpCode
        e is IllegalArgumentException -> e.message ?: "表单字段格式错误"
        else -> "操作失败（" + e.javaClass.simpleName + "），请检查输入或重新读取配置。"
    }
    fun closeEditor() {
        apiKey = ""; confirmSave = false; open = false; editSnapshot = null
    }
    fun preset(presetId: String, presetName: String, url: String, models: String) {
        editSnapshot = null; id = presetId; name = presetName; chatUrl = url; apiKey = ""
        modelsText = models; modelsTouched = false; availableModels = emptyList(); writeUncertain = false; allowLoopbackHttp = false; open = true
    }
    LaunchedEffect(enabled) {
        if (!enabled) { closeEditor(); list = null; message = "核心已断开，提供商请求暂停" }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("提供商管理（API Key）", style = MaterialTheme.typography.titleMedium)
            Text("支持基础 OpenAI 兼容渠道。凭据存在不等于推理已验证；此页面不自动调用 test/detect 或真实模型。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && !busy, onClick = {
                    busy = true
                    val sessionId = NativeCoreSession.providerSessionId()
                    scope.launch {
                        try {
                            val loaded = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().getProviders() }
                            if (latestEnabled && sessionId == NativeCoreSession.providerSessionId()) { list = loaded; message = if (loaded.hasFileError) "核心提供商文件异常，写入暂停；请保留文件并排查" else "已读取核心提供商列表" }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = errorMessage(e) }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "处理中…" else "刷新提供商") }
                Button(enabled = enabled && !busy && list?.hasFileError != true, onClick = { preset("", "", "", "") }) { Text("新增") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("按名称或标识筛选") }, modifier = Modifier.fillMaxWidth())
            val matches = list?.providers.orEmpty().filter { it.name.contains(search, true) || it.id.contains(search, true) }
            if (list != null && matches.isEmpty()) Text("没有匹配的提供商；可新增自己的 API Key 渠道")
            matches.take(50).forEach { provider ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(provider.name.ifBlank { provider.id }, style = MaterialTheme.typography.titleSmall)
                        Text("标识：" + provider.id + " · 可见模型 " + provider.models.size, style = MaterialTheme.typography.bodySmall)
                        Text(if (provider.key.set) "凭据已设置（不展示完整密钥）" else if (provider.key.optional) "该渠道允许无密钥" else "未配置凭据", style = MaterialTheme.typography.bodySmall)
                        Text("连通/推理：未由本次操作验证", style = MaterialTheme.typography.bodySmall)
                        if (!provider.editable) Text("此类型暂不支持基础编辑，原配置保持", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = enabled && !busy && provider.editable, onClick = {
                            busy = true
                            val sessionId = NativeCoreSession.providerSessionId()
                            scope.launch {
                                try {
                                    val snapshot = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().editable(provider.id) }
                                    if (latestEnabled && sessionId == NativeCoreSession.providerSessionId()) {
                                        editSnapshot = snapshot; id = snapshot.id; name = snapshot.name; chatUrl = snapshot.chatUrl
                                        apiKey = ""; modelsText = snapshot.modelIds.joinToString("\n"); modelsTouched = false
                                        availableModels = (provider.models.map { it.id } + snapshot.modelIds).distinct()
                                        allowLoopbackHttp = snapshot.chatUrl.startsWith("http://")
                                        writeUncertain = false; open = true; message = "已取得核心编辑版本，未支持字段由核心保留"
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = errorMessage(e) }
                                finally { busy = false }
                            }
                        }) { Text("读取并安全编辑") }
                    }
                }
            }
            if (matches.size > 50) Text("匹配 " + matches.size + " 个，仅显示前50个；请进一步筛选")
            if (open) {
                Text(if (editSnapshot == null) "新增 OpenAI 兼容提供商" else "编辑 " + id, style = MaterialTheme.typography.titleSmall)
                if (editSnapshot == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { preset("deepseek-api", "DeepSeek API", "https://api.deepseek.com/v1", "deepseek-chat") }) { Text("DeepSeek模板") }
                        TextButton(enabled = !busy, onClick = { preset("openai-api", "OpenAI API", "https://api.openai.com/v1", "") }) { Text("OpenAI模板") }
                    }
                }
                OutlinedTextField(value = id, onValueChange = { id = it }, enabled = editSnapshot == null && !busy,
                    label = { Text("唯一标识（小写字母、数字、短横线）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                    label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = chatUrl, onValueChange = { chatUrl = it }, enabled = !busy,
                    label = { Text("Chat API地址（通常含/v1）") }, modifier = Modifier.fillMaxWidth())
                Row {
                    Checkbox(checked = allowLoopbackHttp, onCheckedChange = { allowLoopbackHttp = it }, enabled = !busy)
                    Text("明确允许本机 HTTP（仅loopback）；外部地址仍须HTTPS", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, enabled = !busy,
                    label = { Text(if (editSnapshot == null) "API Key" else "新API Key（留空保留原密钥）") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = modelsText, onValueChange = { modelsText = it; modelsTouched = true }, enabled = !busy,
                    label = { Text("模型ID，每行一个或逗号分隔") }, modifier = Modifier.fillMaxWidth())
                Text("请填写该账号实际可用的原始模型ID。空列表将明确禁用全部模型，不隐式启用所有。", style = MaterialTheme.typography.bodySmall)
                availableModels.take(30).forEach { model ->
                    Row {
                        val selected = model in modelIds()
                        Checkbox(checked = selected, enabled = !busy, onCheckedChange = { checked ->
                            val ids = modelIds().toMutableList()
                            if (checked && model !in ids) ids.add(model) else if (!checked) ids.remove(model)
                            modelsText = ids.joinToString("\n"); modelsTouched = true
                        })
                        Text(model, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (availableModels.size > 30) Text("更多模型可在上方ID输入框中选择/填写")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled && !busy && !writeUncertain && list?.hasFileError != true, onClick = { confirmSave = true }) { Text("确认保存配置") }
                    TextButton(enabled = !busy, onClick = { closeEditor() }) { Text("取消并清除输入密钥") }
                }
                if (writeUncertain) Text("本次写入结果不确定；请取消表单、刷新核心列表后再操作。")
            }
        }
    }
    if (confirmSave) {
        AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("保存提供商配置？") },
            text = { Text("API Key将仅交给本App私有核心配置。请确认接口地址可信。本操作不做真实模型推理；编辑只修改已选字段，版本冲突时拒绝覆盖。") },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("返回检查") } },
            confirmButton = { TextButton(enabled = enabled && !busy, onClick = save@{
                if (busy || !latestEnabled) return@save
                val sessionId = NativeCoreSession.providerSessionId() ?: return@save
                confirmSave = false; busy = true
                val snapshot = editSnapshot
                val inputId = id; val inputName = name; val inputUrl = chatUrl
                val inputKey = apiKey; val inputModels = modelIds(); val changeModels = modelsTouched
                val allowHttp = allowLoopbackHttp
                apiKey = "" // the UI must not retain a submitted secret
                scope.launch {
                    var newDraft: ProviderCreateDraft? = null
                    var editDraft: ProviderEditDraft? = null
                    var saved = false
                    try {
                        // Draft construction/validation is caught, never thrown from the UI callback.
                        if (snapshot == null) newDraft = ProviderCreateDraft(inputId, inputName, inputUrl, inputKey, inputModels, allowHttp)
                        else editDraft = ProviderEditDraft(snapshot, name = inputName, chatUrl = inputUrl, apiKey = inputKey,
                            modelIds = if (changeModels) inputModels else null, allowLoopbackHttp = allowHttp)
                        withContext(Dispatchers.IO) {
                            val client = NativeCoreSession.providerClient()
                            val created = newDraft
                            if (created != null) client.create(created) else client.edit(checkNotNull(editDraft))
                        }
                        saved = true
                        if (latestEnabled && sessionId == NativeCoreSession.providerSessionId()) {
                            open = false; editSnapshot = null; onProvidersChanged()
                            list = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().getProviders() }
                            message = "配置已保存，尚未调用真实模型；可刷新网关模型目录"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (saved) message = "配置保存已确认，但刷新列表失败；不要再次提交保存，请重新读取。"
                        else { writeUncertain = e is ManagementExecutionUnknown; message = errorMessage(e) }
                    } finally { newDraft?.clearSecret(); editDraft?.clearSecret(); apiKey = ""; busy = false }
                }
            }) { Text("保存，不进行推理") } })
    }
}
