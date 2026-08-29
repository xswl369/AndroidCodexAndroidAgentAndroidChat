package com.xs.chat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * 附件本地化存储：系统选择器返回的 content:// 授权仅存活于应用进程，
 * 选择时即拷贝到私有目录，保证发送、持久化与重启后渲染均可用。
 */
object AttachmentStore {
    private const val MAX_IMAGE_CHAT_BYTES = 8 * 1024 * 1024
    private const val MAX_VIDEO_CHAT_BYTES = 25 * 1024 * 1024
    private const val MAX_TEXT_INLINE_BYTES = 300_000

    /** 拷贝选定附件到 filesDir/attachments，返回可持久化的 Attachment；失败返回 null。 */
    fun importToLocal(context: Context, picked: PickedAttachment): Attachment? {
        return runCatching {
            val uri = Uri.parse(picked.uri)
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val ext = extensionOf(picked.name, picked.mimeType)
            val target = File(dir, System.currentTimeMillis().toString() + "_" + sanitize(picked.name))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            } ?: return null
            Attachment(
                kind = when {
                    picked.mimeType.startsWith("image/") -> AttachmentKind.IMAGE
                    picked.mimeType.startsWith("video/") -> AttachmentKind.VIDEO
                    else -> AttachmentKind.FILE
                },
                name = picked.name.ifBlank { "附件$ext" },
                mimeType = picked.mimeType.ifBlank { "application/octet-stream" },
                sizeBytes = target.length(),
                uri = "file://" + target.absolutePath
            )
        }.getOrNull()
    }

    fun readBytes(context: Context, attachment: Attachment): ByteArray? {
        return runCatching {
            val uri = Uri.parse(attachment.uri)
            if ("file".equals(uri.scheme, ignoreCase = true)) {
                val f = File(uri.path ?: return null)
                if (f.length() > MAX_VIDEO_CHAT_BYTES) return null
                f.readBytes()
            } else {
                val len = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && c.getLong(i) > MAX_VIDEO_CHAT_BYTES) null else 1L
                }
                if (len == null) return null
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull()
    }

    /** 可内联为文本的附件才返回内容；否则 null。 */
    fun readText(context: Context, attachment: Attachment): String? {
        val bytes = readBytes(context, attachment) ?: return null
        if (bytes.size > MAX_TEXT_INLINE_BYTES) return null
        val isText = attachment.mimeType.startsWith("text/")
                || TEXT_EXTS.any { attachment.name.endsWith(it, ignoreCase = true) }
        if (!isText) return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    /** 保存修改后的文件到 filesDir/edited，返回绝对路径。 */
    fun saveEditedFile(context: Context, originalName: String, content: String): String {
        val dir = File(context.filesDir, "edited").apply { mkdirs() }
        val target = File(dir, System.currentTimeMillis().toString() + "_" + sanitize(originalName))
        target.writeText(content, Charsets.UTF_8)
        return target.absolutePath
    }

    fun queryName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: (uri.lastPathSegment ?: "附件")
    }

    fun queryMime(context: Context, uri: Uri): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** 限制尺寸解码本地/内容 URI 图片。 */
    fun decodeBitmap(context: Context, uriStr: String?, maxSize: Int = 1024): Bitmap? {
        if (uriStr.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriStr)
            val src: java.io.InputStream? = if ("file".equals(uri.scheme, ignoreCase = true)) {
                File(uri.path ?: return null).inputStream()
            } else context.contentResolver.openInputStream(uri)
            src?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }.getOrNull()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[^\w.\-一-鿿]"""), "_").take(60)

    private fun extensionOf(name: String, mime: String): String {
        val fromName = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 6 }
        if (fromName != null) return "." + fromName.lowercase()
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (fromMime.isNullOrBlank()) "" else "." + fromMime
    }

    private val TEXT_EXTS = listOf("txt", "md", "markdown", "json", "xml", "html", "htm", "csv", "log", "kt", "java", "py", "js", "ts", "css", "yml", "yaml", "ini", "cfg", "properties")
}
