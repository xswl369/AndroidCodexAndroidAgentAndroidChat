package com.xs.chat.ui

import com.xs.chat.BuildConfig
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xs.chat.data.AiModel
import com.xs.chat.data.CallRole
import com.xs.chat.data.SettingsStore
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var apiKey by rememberSaveable(state.apiKey) { mutableStateOf(state.apiKey) }
    var temperature by rememberSaveable(state.temperature) { mutableStateOf(state.temperature) }
    var systemPrompt by rememberSaveable(state.systemPrompt) { mutableStateOf(state.systemPrompt) }
    var darkMode by rememberSaveable(state.darkMode) { mutableStateOf(state.darkMode) }
    var language by rememberSaveable(state.language) { mutableStateOf(state.language) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<AiModel?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var modelsExpanded by rememberSaveable { mutableStateOf(false) }
    var showApiNameDialog by remember { mutableStateOf(false) }
    var apisExpanded by remember { mutableStateOf(false) }
    var roleExpanded by remember { mutableStateOf(false) }
    var showRoleDialog by remember { mutableStateOf(false) }
    var memoryLimitInput by rememberSaveable(state.memoryLimit) { mutableStateOf(state.memoryLimit.toString()) }
    val lang = LocalLanguage.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Lang.t(lang, "settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = Lang.t(lang, "back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---------- 媒体生成参数（置顶） ----------
            MediaSettingsSection(state = state, vm = vm)
            Spacer(Modifier.height(24.dp))
            SectionTitle(Lang.t(lang, "call_role"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { roleExpanded = true },
                    enabled = state.callRoles.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    val active = state.callRoles.firstOrNull { it.id == state.callRoleId }
                    Text(
                        (active?.let { it.name + " · " + it.dialect }) ?: Lang.t(lang, "call_role"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showRoleDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "add"))
                }
                if (state.callRoles.firstOrNull { it.id == state.callRoleId }?.custom == true) {
                    IconButton(onClick = { vm.deleteCallRole(state.callRoleId) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = Lang.t(lang, "delete"))
                    }
                }
            }
            Box {
                DropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                    state.callRoles.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.name + " · " + role.dialect, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { roleExpanded = false; vm.selectCallRole(role.id) }
                        )
                    }
                }
            }
            state.callRoles.firstOrNull { it.id == state.callRoleId }?.let { r ->
                Spacer(Modifier.height(6.dp))
                Text(
                    r.voice + " · " + r.dialect,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (r.prompt.isNotBlank()) {
                    Text(
                        r.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                val roleId = r.id
                var speedText by remember(roleId) { mutableStateOf("%.1f".format(r.speed)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Lang.t(lang, "call_role_speed"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    TextField(
                        value = speedText,
                        onValueChange = { input ->
                            val clean = input.filter { it.isDigit() || it == '.' }.take(5)
                            speedText = clean
                            clean.toFloatOrNull()?.let { v -> vm.updateCallRoleSpeed(v.coerceIn(0.5f, 2.0f)) }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                            errorIndicatorColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.width(110.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionTitle(Lang.t(lang, "api_settings"))
            // 已保存的 API 合并进 API 设置：下拉选择 + 保存当前
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { apisExpanded = true },
                    enabled = state.apiProfiles.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (state.apiProfiles.isEmpty()) Lang.t(lang, "select_saved_api")
                        else Lang.t(lang, "saved_apis") + "（" + state.apiProfiles.size + "）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showApiNameDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "save_current_api"))
                }
            }
            Box {
                DropdownMenu(expanded = apisExpanded, onDismissRequest = { apisExpanded = false }) {
                    state.apiProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        profile.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            trailingIcon = {
                                IconButton(onClick = {
                                    apisExpanded = false
                                    vm.deleteApiProfile(profile.id)
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = Lang.t(lang, "delete"))
                                }
                            },
                            onClick = {
                                apisExpanded = false
                                vm.useApiProfile(profile.id)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    vm.saveSettings(it, apiKey, temperature, systemPrompt, darkMode)
                },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    vm.saveSettings(baseUrl, it, temperature, systemPrompt, darkMode)
                },
                label = { Text(Lang.t(lang, "api_key")) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showKey) "隐藏" else "显示"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(Lang.t(lang, "temp_label").format((temperature * 10).roundToInt() / 10f), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = temperature,
                onValueChange = {
                    temperature = it
                    vm.saveSettings(baseUrl, apiKey, it, systemPrompt, darkMode)
                },
                valueRange = 0f..2f
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = {
                    systemPrompt = it
                    vm.saveSettings(baseUrl, apiKey, temperature, it, darkMode)
                },
                label = { Text(Lang.t(lang, "system_prompt")) },
                placeholder = { Text("你是一个乐于助人的 AI 助手…") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.loadModelsFromServer() },
                enabled = !state.loadingModels,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.loadingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(Lang.t(lang, "load_models_btn"))
            }


            Spacer(Modifier.height(24.dp))
            SectionTitle(Lang.t(lang, "my_models"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Lang.t(lang, "model_count").format(state.models.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "add"))
                }
                IconButton(onClick = { modelsExpanded = !modelsExpanded }) {
                    Icon(
                        if (modelsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (modelsExpanded) Lang.t(lang, "collapse") else Lang.t(lang, "expand_all").format(state.models.size),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (state.models.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        Lang.t(lang, "no_models_hint"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                // 默认折叠：只展示当前选中的默认模型
                val shown = if (modelsExpanded) state.models else listOfNotNull(state.activeModel)
                shown.forEach { model ->
                    ModelRow(
                        model = model,
                        isActive = model.id == state.activeModel?.id,
                        onSelect = { vm.selectModel(model) },
                        onEdit = { editingModel = model },
                        onDelete = { vm.deleteModel(model.id) },
                        onSetDefault = { vm.setDefaultModel(model.id) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                if (!modelsExpanded && state.models.size > 1) {
                    TextButton(onClick = { modelsExpanded = true }) {
                        Text(Lang.t(lang, "expand_all").format(state.models.size))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(24.dp))
            SectionTitle(Lang.t(lang, "memory"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Lang.t(lang, "memory_limit"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = memoryLimitInput,
                    onValueChange = { memoryLimitInput = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                val limitMsg = Lang.t(lang, "saved_ok")
                TextButton(onClick = {
                    val n = memoryLimitInput.toIntOrNull()
                    if (n != null && n > 0) {
                        vm.updateMemoryLimit(n)
                        Toast.makeText(context, limitMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, Lang.t(lang, "memory_limit_invalid"), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(Lang.t(lang, "confirm_change")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Lang.t(lang, "memory_count").format(state.memoryLog.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                val clearMsg = Lang.t(lang, "memory_clear_done")
                OutlinedButton(
                    onClick = {
                        vm.clearMemory()
                        Toast.makeText(context, clearMsg, Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.memoryLog.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Lang.t(lang, "memory_clear"))
                }
            }
            if (state.memoryLog.isEmpty()) {
                Text(
                    Lang.t(lang, "memory_empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.memoryLog.take(50).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionTitle(Lang.t(lang, "appearance"))
            ThemeOption(Lang.t(lang, "follow_system"), "system", darkMode, { darkMode = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, it) })
            ThemeOption(Lang.t(lang, "light_mode"), "light", darkMode, { darkMode = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, it) })
            ThemeOption(Lang.t(lang, "dark_mode"), "dark", darkMode, { darkMode = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, it) })
            Spacer(Modifier.height(24.dp))
            SectionTitle(Lang.t(lang, "language"))
            ThemeOption("中文", "zh", language, { language = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, darkMode, language = it) }, Icons.Rounded.Language)
            ThemeOption("English", "en", language, { language = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, darkMode, language = it) }, Icons.Rounded.Language)
            ThemeOption(Lang.t(lang, "follow_system"), "system", language, { language = it; vm.saveSettings(baseUrl, apiKey, temperature, systemPrompt, darkMode, language = it) }, Icons.Rounded.Language)

            Spacer(Modifier.height(24.dp))
            SectionTitle(Lang.t(lang, "about"))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("XS Chat v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "兼容 OpenAI Chat Completions API，支持任意模型服务：OpenAI、DeepSeek、通义千问、智谱 GLM、Ollama 等。支持流式输出与 Markdown 渲染。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    editingModel?.let { model ->
        ModelEditorDialog(
            initial = model,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                vm.updateModel(updated)
                editingModel = null
            }
        )
    }
    if (showAddDialog) {
        ModelEditorDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { created ->
                vm.addModel(created)
                showAddDialog = false
            }
        )
    }
    if (showApiNameDialog) {
        ApiNameDialog(
            onDismiss = { showApiNameDialog = false },
            onConfirm = { name ->
                vm.saveApiProfile(name)
                showApiNameDialog = false
            }
        )
    }

    if (showRoleDialog) {
        CallRoleDialog(
            onDismiss = { showRoleDialog = false },
            onConfirm = { role -> vm.addCallRole(role) }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ThemeOption(label: String, value: String, current: String, onSelect: (String) -> Unit, icon: ImageVector = Icons.Rounded.Contrast) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Spacer(Modifier.width(8.dp))
        Icon(
            when (value) {
                "light" -> Icons.Rounded.LightMode
                "dark" -> Icons.Rounded.DarkMode
                else -> icon
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelRow(
    model: AiModel,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.isDefault) {
                    Text(
                        "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Text(
                model.modelId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                model.baseUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!model.isDefault) {
            TextButton(onClick = onSetDefault) { Text("设默认") }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Rounded.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ApiNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val lang = LocalLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(lang, "save_current_api")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(Lang.t(lang, "api_name_hint")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text(Lang.t(lang, "save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Lang.t(lang, "cancel")) }
        }
    )
}

@Composable
private fun CallRoleDialog(onDismiss: () -> Unit, onConfirm: (CallRole) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var voice by rememberSaveable { mutableStateOf("alloy") }
    var dialect by rememberSaveable { mutableStateOf("普通话") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var imageUri by rememberSaveable { mutableStateOf("") }
    var speedText by rememberSaveable { mutableStateOf("1.0") }
    val lang = LocalLanguage.current
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = runCatching {
                val dir = File(ctx.filesDir, "call_roles").apply { mkdirs() }
                val f = File(dir, "role_" + System.currentTimeMillis() + ".jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                "file://" + f.absolutePath
            }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Lang.t(lang, "call_role_add")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Lang.t(lang, "call_role_name")) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = { Text(Lang.t(lang, "call_role_voice")) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = dialect,
                    onValueChange = { dialect = it },
                    label = { Text(Lang.t(lang, "call_role_dialect")) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(Lang.t(lang, "call_role_prompt")) },
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Lang.t(lang, "call_role_speed"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    TextField(
                        value = speedText,
                        onValueChange = { input ->
                            speedText = input.filter { it.isDigit() || it == '.' }.take(5)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                            errorIndicatorColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.width(110.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("x", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(Lang.t(lang, "call_role_image"))
                    }
                    if (imageUri.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Lang.t(lang, "call_role_image_selected"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) {
                    onConfirm(
                        CallRole(
                            id = "custom_" + System.currentTimeMillis(),
                            name = n,
                            dialect = dialect.trim().ifBlank { "自定义" },
                            voice = voice.trim().ifBlank { "alloy" },
                            prompt = prompt.trim(),
                            emoji = "🎭",
                            custom = true,
                            imageUri = imageUri,
                            speed = speedText.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f
                        )
                    )
                    onDismiss()
                }
            }) { Text(Lang.t(lang, "ok")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Lang.t(lang, "cancel")) } }
    )
}

@Composable
private fun ModelEditorDialog(
    initial: AiModel?,
    onDismiss: () -> Unit,
    onSave: (AiModel) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var modelId by rememberSaveable { mutableStateOf(initial?.modelId ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by rememberSaveable { mutableStateOf(initial?.apiKey ?: "") }
    var showKey by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加模型" else "编辑模型") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    placeholder = { Text("如 gpt-4o-mini / deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（可留空）") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedId = modelId.trim()
                    if (trimmedId.isNotEmpty()) {
                        onSave(
                            AiModel(
                                id = initial?.id ?: "",
                                modelId = trimmedId,
                                name = name.trim().ifBlank { trimmedId },
                                baseUrl = baseUrl.trim().ifBlank { "https://api.openai.com/v1" },
                                apiKey = apiKey.trim(),
                                isDefault = initial?.isDefault ?: false
                            )
                        )
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}












/** 媒体生成参数区（置顶）：图片像素 / 视频分辨率 / 视频时长，输入框+常见格式选择+确认更改。 */
@Composable
private fun MediaSettingsSection(state: ChatUiState, vm: ChatViewModel) {
    val lang = LocalLanguage.current
    var imageSize by rememberSaveable(state.imageSize) { mutableStateOf(state.imageSize) }
    var videoSize by rememberSaveable(state.videoResolution) { mutableStateOf(state.videoResolution) }
    var videoDur by rememberSaveable(state.videoDuration) { mutableStateOf(state.videoDuration) }
    var feedback by remember { mutableStateOf("") }

    // 像素格式：WxH
    val imageOk = Regex("\\d+\\s*[xX×]\\s*\\d+").matches(imageSize.trim())
    // 时长：1-18 秒整数（v2.0 契约 num_frames 8n+1 且 ≤441，24fps 下最长为 18 秒）
    val durOk = videoDur.trim().toIntOrNull()?.let { it in 1..18 } == true
    // 分辨率：直接档位或像素，像素按高度映射 720P/960P/2K
    val videoSizeOk = normalizeVideoSize(videoSize) != null
    val context = LocalContext.current
    val confirm = {
        val norm = normalizeVideoSize(videoSize)
        if (imageOk && durOk && norm != null) {
            vm.updateMediaSettings(imageSize.trim(), norm, videoDur.trim())
            Toast.makeText(context, Lang.t(lang, "saved_ok"), Toast.LENGTH_SHORT).show()
            feedback = ""
        } else {
            feedback = Lang.t(lang, "media_param_invalid")
        }
    }

    SectionTitle(Lang.t(lang, "media_settings"))
    MediaParamRow(
        label = Lang.t(lang, "image_pixel"),
        value = imageSize,
        onValueChange = { imageSize = it; feedback = "" },
        presets = listOf("512x512", "768x768", "960x960", "1024x1024", "1152x1152", "1440x1440", "1536x1536", "2048x2048", "1024x768", "1280x720", "1536x1024", "1920x1080", "768x1024", "720x1280", "1080x1920"),
        onConfirm = confirm,
        valid = imageOk
    )
    Spacer(Modifier.height(10.dp))
    MediaParamRow(
        label = Lang.t(lang, "video_resolution"),
        value = videoSize,
        onValueChange = { videoSize = it; feedback = "" },
        presets = listOf("720P", "960P", "2K", "1280x720", "1280x960", "1920x1080", "2560x1440", "3840x2160", "720x1280", "1080x1920", "1440x2560"),
        onConfirm = confirm,
        valid = videoSizeOk
    )
    Spacer(Modifier.height(10.dp))
    MediaParamRow(
        label = Lang.t(lang, "video_duration"),
        value = videoDur,
        onValueChange = { videoDur = it; feedback = "" },
        presets = listOf("5", "8", "10", "15", "18"),
        suffix = Lang.t(lang, "seconds_suffix"),
        onConfirm = confirm,
        valid = durOk
    )
    if (feedback.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            feedback,
            style = MaterialTheme.typography.labelSmall,
            color = if (feedback == Lang.t(lang, "saved_ok")) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(14.dp))
}

/** 分辨率输入归一化：720P/960P/2K 原样返回；像素 WxH 按高度映射档位；非法返回 null。 */
private fun normalizeVideoSize(input: String): String? {
    val t = input.trim()
    when (t.uppercase()) {
        "720P" -> return "720P"
        "960P" -> return "960P"
        "2K" -> return "2K"
    }
    val m = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)").find(t) ?: return null
    val h = m.groupValues[2].toInt()
    return when {
        h <= 720 -> "720P"
        h <= 1080 -> "960P"
        else -> "2K"
    }
}

/** 单行参数：标签 + 下划线输入框 + 选择按钮（弹出常见参数）+ 确认更改按钮。 */
@Composable
private fun MediaParamRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    presets: List<String>,
    onConfirm: () -> Unit,
    valid: Boolean,
    suffix: String = ""
) {
    val lang = LocalLanguage.current
    var pickerOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                isError = !valid,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    errorIndicatorColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.width(110.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { pickerOpen = true }) {
                    Text(Lang.t(lang, "select_param"))
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    presets.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p + suffix) },
                            onClick = {
                                onValueChange(p)
                                pickerOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConfirm, enabled = valid) {
                Text(Lang.t(lang, "confirm_change"))
            }
        }
    }
}
