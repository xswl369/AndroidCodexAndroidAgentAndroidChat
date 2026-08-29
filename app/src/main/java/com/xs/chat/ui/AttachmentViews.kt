package com.xs.chat.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xs.chat.data.Attachment
import com.xs.chat.data.AttachmentKind
import com.xs.chat.data.AttachmentStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 限制尺寸加载本地图片（file:// 或 content://）。 */
@Composable
fun rememberLocalBitmap(uri: String?, maxSize: Int = 1024): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            AttachmentStore.decodeBitmap(context, uri, maxSize)?.asImageBitmap()
        }
    }
    return state
}

/** 输入框上方待发送附件 chip。 */
@Composable
fun PendingAttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)) {
            when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    val bmp = rememberLocalBitmap(attachment.uri, 200)
                    if (bmp != null) {
                        Image(
                            bmp,
                            contentDescription = attachment.name,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                AttachmentKind.VIDEO -> Icon(Icons.Rounded.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                AttachmentKind.FILE -> Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.widthIn(max = 140.dp)) {
                Text(attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtSize(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "移除附件", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 消息气泡内的附件渲染（图片/视频缩略图，点击进 app 内查看器；文件走外部打开）。 */
@Composable
fun MessageAttachments(attachments: List<Attachment>) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        attachments.forEach { a ->
            when (a.kind) {
                AttachmentKind.IMAGE -> {
                    val bmp = rememberLocalBitmap(a.uri, 1200)
                    if (bmp != null) {
                        Image(
                            bmp,
                            contentDescription = a.name,
                            // 统一缩小为 180dp 方形缩略图，点击进 app 内查看器（可缩放/保存）
                            modifier = Modifier
                                .width(180.dp)
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewing = a },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AttachmentRow(a, context, Icons.Rounded.Image)
                    }
                }
                AttachmentKind.VIDEO -> {
                    val thumb = rememberVideoThumbnail(a.uri)
                    if (thumb != null) {
                        Box(
                            Modifier
                                .width(180.dp)
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewing = a }
                        ) {
                            Image(
                                thumb,
                                contentDescription = a.name,
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentScale = ContentScale.Crop
                            )
                            Icon(
                                Icons.Rounded.PlayCircle,
                                contentDescription = "点击播放",
                                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
                                modifier = Modifier.align(Alignment.Center).size(46.dp)
                            )
                        }
                    } else {
                        AttachmentRow(a, context, Icons.Rounded.VideoLibrary)
                    }
                }
                AttachmentKind.FILE -> AttachmentRow(a, context, Icons.Rounded.Description)
            }
        }
    }
    viewing?.let { MediaViewerDialog(it) { viewing = null } }
}

@Composable
private fun AttachmentRow(attachment: Attachment, context: Context, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { openAttachment(context, attachment) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    fmtSize(attachment.sizeBytes) + (if (attachment.kind == AttachmentKind.VIDEO) " · 点击播放" else " · 点击打开"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** 打开附件：file:// 走 FileProvider，content:// 直用，授予读取权限。 */
fun openAttachment(context: Context, attachment: Attachment) {
    val raw = Uri.parse(attachment.uri)
    val targetUri = if ("file".equals(raw.scheme, ignoreCase = true)) {
        val f = File(raw.path ?: "")
        if (f.exists()) {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        } else raw
    } else raw
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(targetUri, attachment.mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, attachment.name)) }
        .onFailure {
            Toast.makeText(context, "无法打开 ${attachment.name}：${it.message}", Toast.LENGTH_SHORT).show()
        }
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
