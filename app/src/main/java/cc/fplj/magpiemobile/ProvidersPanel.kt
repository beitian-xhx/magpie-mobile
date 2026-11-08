package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.fplj.magpiemobile.core.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only nonsecret write-recovery flags are saveable. Password fields/drafts stay ephemeral. */
@Composable
fun ProvidersPanel(enabled: Boolean, operationBusy: Boolean = false, onBusyChanged: (Boolean) -> Unit = {}, onProvidersChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val latestEnabled by rememberUpdatedState(enabled)
    var providers by remember { mutableStateOf<ProviderListSnapshot?>(null) }
    var listHealthy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ProviderEditSnapshot?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var nameTouched by remember { mutableStateOf(false) }
    var idTouched by remember { mutableStateOf(false) }
    var localHttp by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var modelSearch by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var modelOptions by remember { mutableStateOf(emptyList<ProviderModelSummary>()) }
    var policy by remember { mutableStateOf(ProviderEditorPolicy(true)) }
    var selectionMutation by remember { mutableStateOf(0) }
    val selectedModelEdit = remember(policy, selectionMutation, selected) { policy.editedModels() }
    var discoveryLoading by remember { mutableStateOf(false) }
    var discoveryDone by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf("") }
    var retryCatalog by remember { mutableStateOf(0) }
    var operationSequence by remember { mutableStateOf(0L) }
    var catalogSequence by remember { mutableStateOf(0L) }
    var recoveryRequired by rememberSaveable { mutableStateOf(false) }
    var pendingProviderId by rememberSaveable { mutableStateOf("") }
    var confirmDisable by remember { mutableStateOf(false) }
    val owner = if (enabled) NativeCoreSession.providerSessionId() else null
    fun setBusy(value: Boolean) { busy = value; onBusyChanged(value) }
    fun current(session: Long?, sequence: Long): Boolean = latestEnabled && session != null &&
        session == NativeCoreSession.providerSessionId() && sequence == operationSequence
    fun errorText(e: Exception): String = when (e) {
        is ProviderAlreadyExistsException -> "标识已被占用，请刷新列表并编辑已有提供商，或修改高级标识。"
        is ProviderRevisionConflictException -> "配置已变化，请重新打开编辑；没有覆盖新配置。"
        is ProviderConfigurationUnreadableException -> "核心配置文件异常，写入暂停；请保留文件并排查。"
        is ProviderUnsupportedException -> "此类型暂不支持基础编辑，原配置保持。"
        is ProviderEditPreconditionException -> "尚未修改字段，或编辑版本已过期。"
        is ManagementExecutionUnknown -> "保存结果待核对，请点击“核对保存结果”；不会自动再次提交。"
        is ManagementHttpException -> "请求失败：HTTP " + e.httpCode
        is IllegalArgumentException -> e.message ?: "请检查表单字段"
        else -> "操作失败，请重试读取；不会自动重复保存。"
    }
    fun refresh() {
        if (!enabled || busy) return
        val session = owner; val sequence = ++operationSequence
        listHealthy = false; setBusy(true)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().getProviders() }
                if (current(session, sequence)) {
                    providers = result; listHealthy = !result.hasFileError
                    if (listHealthy) { recoveryRequired = false; pendingProviderId = ""; message = "" }
                    else message = "提供商文件异常，写入暂停"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(session, sequence)) { listHealthy = false; message = "列表读取失败：" + errorText(e) } }
            finally { if (sequence == operationSequence) setBusy(false) }
        }
    }
    fun close() { key = ""; showKey = false; open = false; confirmDisable = false }
    fun newProvider(presetId: String = "", presetName: String = "", url: String = "") {
        if (!listHealthy || recoveryRequired || busy || operationBusy) return
        snapshot = null; id = presetId; name = presetName; baseUrl = url; key = ""
        idTouched = false; nameTouched = false; localHttp = false; advanced = false; manual = false
        selected = emptyList(); modelOptions = emptyList(); policy = ProviderEditorPolicy(true)
        discoveryDone = false; discoveryError = ""; message = ""; manualText = ""; open = true
    }
    LaunchedEffect(enabled, owner) {
        if (!enabled) { key = ""; discoveryLoading = false; listHealthy = false; setBusy(false); message = "核心已断开，草稿名称、地址与选择已保留；重连后请补充密钥" }
        else refresh()
    }
    val normalized = try { ProviderBaseUrl.normalize(baseUrl) } catch (_: Exception) { "" }
    val newOriginNeedsKey = snapshot != null && key.isBlank() && normalized.isNotEmpty() && run {
        val old = java.net.URI(snapshot!!.chatUrl); val next = java.net.URI(normalized)
        fun port(uri: java.net.URI): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        old.scheme != next.scheme || old.host != next.host || port(old) != port(next)
    }
    val keyFingerprint = remember(key) { MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } }
    LaunchedEffect(open, normalized, keyFingerprint, localHttp, owner, snapshot?.revision, retryCatalog) {
        val sequence = ++catalogSequence
        discoveryLoading = false; discoveryDone = false; discoveryError = ""
        if (!open || !enabled || normalized.isEmpty() || newOriginNeedsKey) return@LaunchedEffect
        if (snapshot == null) {
            if (!nameTouched) name = ProviderBaseUrl.suggestName(baseUrl)
            if (!idTouched) {
                val base = ProviderBaseUrl.suggestId(baseUrl)
                val used = providers?.providers.orEmpty().map { it.id }.toSet()
                var suggestion = base; var number = 2
                while (suggestion in used) { suggestion = base.take(57) + "-" + number++ }
                id = suggestion
            }
        }
        discoveryLoading = true
        delay(1000) // settle URL/credential changes; old requests cannot apply to this generation
        var request: ProviderModelDiscoveryRequest? = null
        try {
            val activeRequest = ProviderModelDiscoveryRequest(baseUrl, key, snapshot?.id, snapshot?.revision, localHttp)
            request = activeRequest
            val catalog = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().discoverModels(activeRequest) }
            if (sequence == catalogSequence && latestEnabled && owner == NativeCoreSession.providerSessionId()) {
                modelOptions = catalog.models; selected = policy.applyCatalog(catalog.models.map { it.id })
                manualText = selected.joinToString("\n"); discoveryDone = true
                if (catalog.models.isEmpty()) discoveryError = "目录为空；可检查凭据权限，或打开高级设置补充模型ID。"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (sequence == catalogSequence && latestEnabled) discoveryError = if (e is ProviderModelDiscoveryException) e.message ?: "无法读取模型目录" else "目录读取失败，请重试读取；没有修改配置。"
        } finally { request?.clearSecret(); if (sequence == catalogSequence) discoveryLoading = false }
    }
    fun submit() {
        if (busy || operationBusy || recoveryRequired || !listHealthy || !enabled) return
        val session = owner; val sequence = ++operationSequence
        val initial = snapshot; val inputKey = key; val inputName = name; val inputId = id
        val inputUrl = baseUrl; val inputModels = selected; val editedModels = policy.editedModels(); val allow = localHttp
        setBusy(true)
        scope.launch {
            var create: ProviderCreateDraft? = null; var edit: ProviderEditDraft? = null
            var submitted = false; var confirmed = false
            try {
                if (initial == null) create = ProviderCreateDraft(inputId, inputName, inputUrl, inputKey, inputModels, allow)
                else edit = ProviderEditDraft(initial, inputName, inputUrl, inputKey, editedModels, allow)
                // Validate locally before any network call. A typo does not erase the password field.
                create?.validate(); edit?.validate()
                if (!current(session, sequence)) return@launch
                recoveryRequired = true; pendingProviderId = inputId; submitted = true; key = ""
                withContext(Dispatchers.IO) {
                    val client = NativeCoreSession.providerClient()
                    val draft = create
                    if (draft != null) client.create(draft) else client.edit(checkNotNull(edit))
                }
                confirmed = true
                if (current(session, sequence)) {
                    recoveryRequired = false; pendingProviderId = ""; close(); onProvidersChanged()
                    message = "已保存；正在自动刷新目录，尚未验证真实推理"
                    val latest = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().getProviders() }
                    if (current(session, sequence)) { providers = latest; listHealthy = !latest.hasFileError; message = "配置已保存" }
                }
            } catch (e: CancellationException) { throw e } // pending nonsecret marker survives cancellation/recreation
            catch (e: Exception) {
                if (current(session, sequence)) {
                    if (confirmed) { recoveryRequired = false; listHealthy = false; message = "保存已确认，但刷新失败；请重新读取，勿再保存。" }
                    else { if (e !is ManagementExecutionUnknown) { recoveryRequired = false; pendingProviderId = "" }; message = errorText(e) }
                }
            } finally { create?.clearSecret(); edit?.clearSecret(); if (submitted) key = ""; if (sequence == operationSequence) setBusy(false) }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("提供商", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && !busy, onClick = ::refresh) { Text(if (recoveryRequired) "核对保存结果" else "刷新") }
                Button(enabled = enabled && listHealthy && !busy && !operationBusy && !recoveryRequired, onClick = { newProvider() }) { Text("新增提供商") }
            }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            if (recoveryRequired) Text("有待核对保存结果（" + pendingProviderId + "），读取成功前不会开放新的写操作")
            OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("搜索名称或标识") }, modifier = Modifier.fillMaxWidth())
            val all = providers?.providers.orEmpty()
            val matches = all.filter { it.name.contains(search,true) || it.id.contains(search,true) }
            if (listHealthy && all.isEmpty()) Text("尚未添加提供商，点击新增并填写接口地址即可获取模型")
            else if (matches.isEmpty() && search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("没有匹配项，清除筛选") }
            matches.take(50).forEach { provider ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                    Text((if (provider.off) "已停用" else if (provider.noModels) "全部模型已禁用" else "已配置") + " · 选中 " + provider.chosen.size + " / 可见 " + provider.models.size, style = MaterialTheme.typography.bodySmall)
                    Text(if (provider.key.set) "密钥已配置；推理未验证" else "缺少凭据；推理未验证", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = enabled && !busy && !operationBusy && listHealthy && !recoveryRequired && provider.editable, onClick = {
                        val session = owner; val sequence = ++operationSequence; setBusy(true)
                        scope.launch { try {
                            val edit = withContext(Dispatchers.IO) { NativeCoreSession.providerClient().editable(provider.id) }
                            if (current(session, sequence)) {
                                // Show the base the core expects; a config saved with a full
                                // endpoint is repaired on the next save (avoids doubled path/404).
                                val shownBase = runCatching { ProviderBaseUrl.normalize(edit.chatUrl) }.getOrDefault(edit.chatUrl)
                                snapshot = edit; id = edit.id; name = edit.name; baseUrl = shownBase; key = ""; nameTouched = true; idTouched = true
                                localHttp = edit.chatUrl.startsWith("http://"); advanced = false; manual = false
                                policy = ProviderEditorPolicy(false, edit.modelIds, edit.noModels); selected = edit.modelIds
                                modelOptions = provider.models; manualText = selected.joinToString("\n"); message = ""; open = true
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (current(session, sequence)) message = errorText(e) }
                        finally { if (sequence == operationSequence) setBusy(false) } }
                    }) { Text(if (provider.editable) "编辑" else "此类型暂仅支持查看") }
                } }
            }
        }
    }
    if (open) Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy, onClick = ::close) { Text("返回") }
                Text(if (snapshot == null) "新增提供商" else "编辑提供商", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snapshot == null) Row {
                    TextButton(enabled = !busy, onClick = { newProvider("deepseek-api","DeepSeek API","https://api.deepseek.com") }) { Text("DeepSeek") }
                    TextButton(enabled = !busy, onClick = { newProvider("openai-api","OpenAI API","https://api.openai.com/v1") }) { Text("OpenAI") }
                }
                OutlinedTextField(name, { name = it; nameTouched = true }, enabled = !busy, singleLine = true, label = { Text("显示名称（可自动生成）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it }, enabled = !busy, singleLine = true, label = { Text("API Base URL") }, placeholder = { Text("https://api.example.com 或 .../v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                if (normalized.isNotEmpty()) Text("实际 Base URL：" + normalized, style = MaterialTheme.typography.bodySmall)
                else if (baseUrl.isNotBlank()) Text("地址尚不合法：请检查HTTPS、端口和路径，不能在URL里携带密钥。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(key, { key = it }, enabled = !busy, singleLine = true, label = { Text(if (snapshot == null) "API Key（目录公开时可暂留空）" else "新API Key（留空使用已存密钥）") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth())
                if (newOriginNeedsKey) Text("服务来源已改变，请明确填写新密钥；不会自动把旧密钥发到新地址。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏密钥" else "显示输入密钥") }
                Text(if (discoveryLoading) "正在自动获取模型目录…" else if (discoveryDone) "目录已读取，可直接勾选模型" else "地址/密钥输入停顿1秒后自动读取，不需要切换焦点，不发送推理", style = MaterialTheme.typography.bodySmall)
                if (discoveryError.isNotEmpty()) { Text(discoveryError, color = MaterialTheme.colorScheme.error); TextButton(onClick = { focus.clearFocus(); retryCatalog++ }) { Text("重试获取目录") } }
                Row {
                    TextButton(enabled = modelOptions.isNotEmpty(), onClick = { selected = policy.chooseAll(); selectionMutation++; manualText = selected.joinToString("\n") }) { Text("全选") }
                    TextButton(onClick = { selected = policy.chooseNone(); selectionMutation++; manualText = "" }) { Text("取消全选") }
                    Text("已选 " + selected.size)
                }
                OutlinedTextField(modelSearch, { modelSearch = it }, singleLine = true, label = { Text("搜索模型") }, modifier = Modifier.fillMaxWidth())
                val options = (modelOptions.map { it.id } + selected).distinct().filter { it.contains(modelSearch,true) }
                if (options.size > 100) Text("匹配 " + options.size + " 个模型，仅展示前100个；请搜索定位，选择不会丢失")
                options.take(100).forEach { model -> Row(Modifier.fillMaxWidth()) {
                    Checkbox(model in selected, enabled = !busy, onCheckedChange = { selected = policy.toggle(model,it); selectionMutation++; manualText = selected.joinToString("\n") })
                    val title = modelOptions.firstOrNull { it.id == model }?.name?.ifBlank { model } ?: model
                    Column { Text(title); if (title != model) Text(model, style = MaterialTheme.typography.bodySmall)
                        if (modelOptions.none { it.id == model }) Text("已保存的选择，当前目录未返回；不会自动删除", style = MaterialTheme.typography.bodySmall) }
                } }
                if (snapshot != null && selectedModelEdit == null) Text(if (snapshot?.noModels == true) "沿用已禁用模型" else if (snapshot?.modelIds?.isEmpty() == true) "沿用核心全部模型策略（未改选择）" else "保留原有模型选择", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置 / 目录失败后手动补充") }
                if (advanced) {
                    OutlinedTextField(id, { id = it; idTouched = true }, enabled = snapshot == null && !busy, singleLine = true, label = { Text("内部标识（自动生成，已有不能改）") }, modifier = Modifier.fillMaxWidth())
                    Row { Checkbox(localHttp, enabled = !busy, onCheckedChange = { localHttp = it }); Text("明确允许本机HTTP（外部仍须HTTPS）") }
                    Row { Checkbox(manual, enabled = !busy, onCheckedChange = { manual = it }); Text("手动补充模型ID（仅备选）") }
                    if (manual) OutlinedTextField(manualText, { manualText = it; selected = policy.manual(it.split(',', '\n').map(String::trim).filter(String::isNotEmpty)); selectionMutation++ }, label = { Text("已确认可用的模型ID，每行一个") }, modifier = Modifier.fillMaxWidth())
                }
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                Text("保存只更新配置，不验证推理。密钥不写日志；结果未知时先核对而不重发。", style = MaterialTheme.typography.bodySmall)
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled && !operationBusy && !busy && listHealthy && !recoveryRequired && normalized.isNotEmpty() && !newOriginNeedsKey && !discoveryLoading &&
                (snapshot != null || key.isNotBlank()) && (selected.isNotEmpty() || selectedModelEdit?.isEmpty() == true || (snapshot != null && selectedModelEdit == null)) && (snapshot != null || discoveryDone || manual),
                onClick = { focus.clearFocus(); if (selected.isEmpty() && selectedModelEdit?.isEmpty() == true) confirmDisable = true else submit() }) { Text(if (busy) "保存中…" else "保存") }
        } }
    }
    if (confirmDisable) AlertDialog(onDismissRequest = { confirmDisable = false }, title = { Text("禁用全部模型？") }, text = { Text("此操作会明确禁用该提供商的全部模型，不是获取目录失败。") },
        confirmButton = { TextButton(onClick = { confirmDisable = false; submit() }) { Text("确认禁用并保存") } }, dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("返回选择") } })
}
