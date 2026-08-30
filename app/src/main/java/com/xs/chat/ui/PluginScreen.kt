package com.xs.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(8.dp))
            Text("插件", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加插件")
            }
        }
        Text(
            "插件可随时开关；「添加插件」可登记自建插件（含 id / 名称 / 描述），AI 写好后在 PluginRegistry 注册即可接入路由。内置插件不可删除。",
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
                        plugin.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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

    if (showAdd) {
        AddPluginDialog(
            onAdd = { id, name, desc ->
                val ok = vm.addPlugin(id, name, desc)
                if (ok) showAdd = false
                ok
            },
            onDismiss = { showAdd = false }
        )
    }
}

/** 添加插件弹窗：id / 名称 / 描述。返回是否成功（失败时弹窗内提示原因）。 */
@Composable
private fun AddPluginDialog(
    onAdd: (id: String, name: String, desc: String) -> Boolean,
    onDismiss: () -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加插件") },
        text = {
            Column {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(32) },
                    label = { Text("插件 ID（英文，如 weather_check）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("插件名称（如 天气查询）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it.take(120) },
                    label = { Text("插件描述（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (id.length < 2) error = "插件 ID 至少 2 位（小写字母 / 数字 / 下划线）"
                else if (name.isBlank()) error = "请填写插件名称"
                else if (!onAdd(id, name, desc)) error = "ID 已存在或格式不对，请更换"
            }) { Text("确认添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
