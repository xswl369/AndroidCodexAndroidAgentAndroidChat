package com.xs.chat.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xs.chat.plugins.PluginRegistry

/** 插件管理页：内置 + 用户自建插件列表，可开关、可添加、可删除（仅用户自建）。 */
@Composable
fun PluginScreen(
    state: ChatUiState,
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(8.dp))
                Text("插件", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "右下角「+」上传脚本添加插件（.sh / .py / .js / .lua），脚本在手机本地执行，功能由脚本决定。内置插件不可删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val builtInIds = PluginRegistry.plugins.map { it.id }.toSet()
            state.plugins.forEach { plugin ->
                val enabled = state.enabledPlugins.contains(plugin.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(plugin.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (plugin.id in builtInIds) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "内置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            plugin.desc.ifBlank { "（无描述）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (plugin.isScript) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "📜 " + PluginRegistry.langLabel(plugin.lang).ifBlank { "未知语言" } +
                                    (if (plugin.deps.isNotBlank()) " · 依赖：" + plugin.deps else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        if (state.generatingPluginId == plugin.id) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "AI 正在完善定义…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (plugin.usage.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "🤖 " + plugin.usage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (plugin.id !in builtInIds) {
                        IconButton(onClick = { vm.removePlugin(plugin.id) }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "删除插件",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Switch(checked = enabled, onCheckedChange = { vm.togglePlugin(plugin.id, it) })
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(24.dp))
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "添加插件")
        }
    }

    if (showAdd) {
        AddScriptDialog(
            onAdd = { name, desc, deps, uri, fileName ->
                val err = vm.addScriptPlugin(name, desc, deps, uri, fileName)
                if (err == null) showAdd = false
                err
            },
            onDismiss = { showAdd = false }
        )
    }
}

//** 添加脚本插件弹窗：选择 .sh/.py/.js/.lua 脚本文件，脚本由 ScriptRunner 本地执行。返回错误文案（成功为 null）。 */
@Composable
private fun AddScriptDialog(
    onAdd: (name: String, desc: String, deps: String, uri: Uri?, fileName: String) -> String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var deps by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            val display = queryDisplayName(context, uri)
            fileName = display
            if (name.isBlank()) name = display.substringBeforeLast('.').take(20)
            error = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加脚本插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "上传脚本并在本机执行，覆盖 .sh / .py / .js / .lua；聊天输入「用插件名 + 任务」即可调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("插件名称（留空默认脚本文件名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (fileName.isBlank()) "选择脚本文件" else fileName)
                }
                if (fileName.isNotBlank()) {
                    val lang = PluginRegistry.langFor(fileName)
                    Text(
                        "语言：" + PluginRegistry.langLabel(lang).ifBlank { "不支持" } +
                            (if (lang == "py") "（需要设备安装 Python，如 Termux）" else "（本机直接执行）"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedTextField(
                    value = deps,
                    onValueChange = { deps = it.take(120) },
                    label = { Text("Python 依赖（可选，空格分隔，如 requests bs4）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it.take(120) },
                    label = { Text("插件描述（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (fileName.isBlank()) error = "请先选择脚本文件"
                else onAdd(name, desc, deps, pickedUri, fileName)?.let { error = it }
            }) {
                Text("确认添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 从 SAF 内容 Uri 读取文件名（不可用时退回 uri 尾部）。 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it.substringAfterLast('/') }
            }
        }
        uri.lastPathSegment?.substringAfterLast('/') ?: "script.txt"
    }.getOrDefault("script.txt")
}
