package com.xs.chat.plugins

import android.app.Application
import com.xs.chat.data.AiModel
import com.xs.chat.data.Attachment
import com.xs.chat.data.AttachmentKind
import com.xs.chat.data.MediaGenerator
import java.io.File

/**
 * 内置图生视频/文生视频插件：以首帧图片（可选）+ 文字描述生成视频。
 * AGNES 接口：mode=text2video/image2video，duration 秒，size 可配置。
 */
object VideoPlugin {
    suspend fun generate(
        app: Application,
        model: AiModel,
        prompt: String,
        firstFrame: ByteArray?,
        size: String = "1152x768",
        durationSeconds: Int = 5,
        onProgress: ((Int) -> Unit)? = null
    ): Attachment {
        val result = MediaGenerator.generateVideo(model, prompt, firstFrame, size, durationSeconds, onProgress)
        val path = MediaGenerator.saveToFile(app, result, "mp4", "media")
            ?: throw RuntimeException("视频保存失败")
        return Attachment(
            kind = AttachmentKind.VIDEO,
            name = "ai_video_" + System.currentTimeMillis() + ".mp4",
            mimeType = "video/mp4",
            sizeBytes = File(path).length(),
            uri = "file://" + path,
            generated = true
        )
    }
}
