package com.xs.chat.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xs.chat.R
import com.xs.chat.data.Attachment
import com.xs.chat.data.CallMeta
import com.xs.chat.data.ChatMessage
import com.xs.chat.data.Role
import com.xs.chat.data.SearchReference
import com.xs.chat.plugins.WebSearchPlugin

/** 消息竖三点菜单项。 */
private enum class MsgAction { EDIT, TRANSLATE, SHARE, COPY, REGENERATE, DELETE }

/** 构建调用统计文案（Codex 风格）：输入/输出 token、耗时、调用次数。 */
private fun buildCallLabel(lang: String, meta: CallMeta): String {
    val base = Lang.t(lang, "call_stats").format(
        formatTokens(meta.promptTokens),
        formatTokens(meta.completionTokens),
        meta.durationMs / 1000.0
    )
    return if (meta.callCount > 1) base + Lang.t(lang, "call_more").format(meta.callCount) else base
}

private fun formatTokens(n: Long): String = when {
    n >= 1000 -> String.format("%.1fk", n / 1000.0)
    else -> n.toString()
}

/** 消息菜单：菜单项固定，展开状态由父级控制（竖三点按钮与长按共用）。 */
@Composable
private fun MessageMenu(
    items: List<Pair<MsgAction, String>>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAction: (MsgAction) -> Unit
) {
    Box {
        IconButton(onClick = { onOpenChange(true) }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            items.forEach { (action, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onOpenChange(false); onAction(action) }
                )
            }
        }
    }
}

@Composable
fun UserMessageBubble(
    content: String,
    attachments: List<Attachment>,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val lang = LocalLanguage.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (attachments.isNotEmpty()) {
                    MessageAttachments(attachments)
                    if (content.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (content.isNotBlank()) {
                    // 长按选中文字并弹出复制菜单（位置跟随长按处）
                    SelectionContainer {
                        Text(WebSearchPlugin.stripToolMarkup(content), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MessageMenu(
                items = listOf(
                    MsgAction.COPY to Lang.t(lang, "copy"),
                    MsgAction.EDIT to Lang.t(lang, "edit_message"),
                    MsgAction.TRANSLATE to Lang.t(lang, "translate_message"),
                    MsgAction.SHARE to Lang.t(lang, "share_message"),
                    MsgAction.DELETE to Lang.t(lang, "delete")
                ),
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                onAction = { action ->
                    when (action) {
                        MsgAction.COPY -> onCopy()
                        MsgAction.EDIT -> onEdit()
                        MsgAction.TRANSLATE -> onTranslate()
                        MsgAction.SHARE -> onShare()
                        else -> onDelete()
                    }
                }
            )
        }
    }
}

/** 加载桌面启动图标（adaptive-icon）并转为 Painter，供 AI 头像使用。 */
@Composable
private fun rememberAppIconPainter(): Painter {
    val context = LocalContext.current
    return remember(context) {
        val drawable = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, context.theme)
        val size = 108
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }
        BitmapPainter(bitmap.asImageBitmap())
    }
}

@Composable
fun AssistantMessageView(
    message: ChatMessage,
    isStreaming: Boolean,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRunCode: (lang: String, code: String) -> Unit = { _, _ -> },
    onCopyCode: (String) -> Unit = {}
) {
    val lang = LocalLanguage.current
    val primary = MaterialTheme.colorScheme.primary
    val textColor = if (message.error) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onBackground
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Image(
                painter = rememberAppIconPainter(),
                contentDescription = "AI",
                modifier = Modifier.size(30.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // 联网搜索状态胶囊（元宝同款）：「正在全网搜索… / 已找到 N 篇相关内容」
                message.searchMeta?.let { meta ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = primary,
                                maxLines = 2
                            )
                            if (isStreaming) {
                                Spacer(Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 长按选中文字并就地弹出复制菜单
                SelectionContainer {
                    MarkdownText(
                        markdown = WebSearchPlugin.stripToolMarkup(message.content),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                        modifier = Modifier.fillMaxWidth(),
                        onRunCode = onRunCode,
                        onCopyCode = onCopyCode
                    )
                }
                if (isStreaming) {
                    val progress = message.progress
                    if (progress != null) {
                        // 图片/视频生成：提示文字 + 进度条 + 跟随百分比
                        Column(Modifier.padding(top = 6.dp)) {
                            Text(
                                "生成中，请稍后",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.width(220.dp).height(4.dp)
                            )
                            Text(
                                "$progress%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                Lang.t(lang, "thinking"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (!message.attachments.orEmpty().isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    MessageAttachments(message.attachments.orEmpty())
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    message.callMeta?.let { meta ->
                        Text(
                            buildCallLabel(lang, meta),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    MessageMenu(
                        items = buildList {
                            add(MsgAction.COPY to Lang.t(lang, "copy"))
                            if (!isStreaming && message.content.isNotEmpty()) {
                                add(MsgAction.REGENERATE to Lang.t(lang, "regenerate"))
                            }
                            add(MsgAction.EDIT to Lang.t(lang, "edit_message"))
                            add(MsgAction.TRANSLATE to Lang.t(lang, "translate_message"))
                            add(MsgAction.SHARE to Lang.t(lang, "share_message"))
                            add(MsgAction.DELETE to Lang.t(lang, "delete"))
                        },
                        open = menuOpen,
                        onOpenChange = { menuOpen = it },
                        onAction = { action ->
                            when (action) {
                                MsgAction.COPY -> onCopy()
                                MsgAction.REGENERATE -> onRegenerate()
                                MsgAction.EDIT -> onEdit()
                                MsgAction.TRANSLATE -> onTranslate()
                                MsgAction.SHARE -> onShare()
                                MsgAction.DELETE -> onDelete()
                            }
                        }
                    )
                }
                if (!message.references.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SearchReferencesCard(message.references.orEmpty())
                }
            }
        }
    }
}

/** 联网搜索参考资料（元宝同款）：编号 [N] + 标题 + 域名，点击打开原文。 */
@Composable
private fun SearchReferencesCard(references: List<SearchReference>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) references else references.take(6)
    Column(Modifier.fillMaxWidth()) {
        Text(
            "参考资料（共 ${references.size} 条）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            visible.forEachIndexed { i, ref ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val uri = ref.url.trim()
                            if (uri.startsWith("http")) {
                                WebBrowserActivity.open(context, uri)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        "[${i + 1}]",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ref.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ref.domain.isNotBlank()) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                ref.domain + if (ref.snippet.isNotBlank()) " · " + ref.snippet.replace(Regex("\\s+"), " ").take(90) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.OpenInNew,
                        contentDescription = "打开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (i != visible.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
            if (references.size > 6) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        if (expanded) "收起" else "展开全部 ${references.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}







