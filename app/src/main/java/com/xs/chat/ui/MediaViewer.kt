package com.xs.chat.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xs.chat.data.Attachment
import com.xs.chat.data.AttachmentKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** app 内媒体查看器：图片可缩放/拖动，视频可播放/重播，支持保存与关闭。 */
@Composable
fun MediaViewerDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val videoHolder = remember { mutableStateOf<VideoView?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    val bmp = rememberLocalBitmap(attachment.uri, 4096)
                    if (bmp != null) ZoomableImage(bmp)
                    else Text("图片加载失败", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                AttachmentKind.VIDEO -> VideoViewer(attachment.uri, videoHolder)
                else -> Text(attachment.name, color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            ViewerTopBar(attachment, videoHolder.value, onDismiss)
        }
    }
}

/** 图片查看：双指缩放（0.2x-8x）+ 拖动平移，双击复位。 */
@Composable
private fun ZoomableImage(bmp: androidx.compose.ui.graphics.ImageBitmap) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.2f, 8f)
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
    ) {
        Image(
            bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }
}

/** 视频查看：双指缩放（0.2x-8x）+ 拖动平移，双击复位，支持重播（顶部栏 Replay 按钮）。 */
@Composable
private fun VideoViewer(uri: String, holder: androidx.compose.runtime.MutableState<VideoView?>) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.2f, 8f)
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
    ) {
        AndroidView(
            factory = {
                VideoView(context).apply {
                    setVideoURI(Uri.parse(uri))
                    holder.value = this
                    setOnPreparedListener { mp -> mp.isLooping = false; start() }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

/** 顶部操作栏：关闭 / 文件名 / 重播（视频）/ 保存。 */
@Composable
private fun ViewerTopBar(attachment: Attachment, videoView: VideoView?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Color(0x99000000))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
        }
        Text(
            attachment.name,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        if (attachment.kind == AttachmentKind.VIDEO && videoView != null) {
            IconButton(onClick = { videoView.seekTo(0); videoView.start() }) {
                Icon(Icons.Rounded.Replay, contentDescription = "重播", tint = Color.White)
            }
        }
        IconButton(onClick = { saveToStorage(context, attachment) }) {
            Icon(Icons.Rounded.Download, contentDescription = "保存", tint = Color.White)
        }
    }
}

/** 保存到系统相册/影片（API 29+ 免权限，MediaStore RELATIVE_PATH）。 */
fun saveToStorage(context: Context, attachment: Attachment) {
    try {
        val path = Uri.parse(attachment.uri).path ?: return
        val src = File(path)
        if (!src.exists()) {
            Toast.makeText(context, "源文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val isImage = attachment.kind == AttachmentKind.IMAGE
        val mime = attachment.mimeType.ifBlank { if (isImage) "image/png" else "video/mp4" }
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isImage) "Pictures/XS Chat" else "Movies/XS Chat")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val out = context.contentResolver.insert(collection, values)
            ?: throw RuntimeException("无法创建存储条目")
        context.contentResolver.openOutputStream(out)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(out, values, null, null)
        }
        Toast.makeText(context, "已保存到 ${if (isImage) "相册/XS Chat" else "影片/XS Chat"}", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 视频首帧缩略图（MediaMetadataRetriever 抽帧）。 */
@Composable
fun rememberVideoThumbnail(uri: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri == null) return@withContext null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(uri))
                mmr.getFrameAtTime(0)?.asImageBitmap()
            } catch (e: Exception) {
                null
            } finally {
                runCatching { mmr.release() }
            }
        }
    }
    return state
}
