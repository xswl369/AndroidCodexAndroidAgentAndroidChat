package com.xs.chat.plugins

import android.app.Application
import com.xs.chat.data.AiModel
import com.xs.chat.data.Attachment
import com.xs.chat.data.AttachmentKind
import com.xs.chat.data.MediaGenerator
import com.xs.chat.data.MediaResult
import java.io.File

/**
 * 内置生图插件：文生图 / 图生图。
 * 调用 OpenAI 兼容 images/generations，结果保存到本地。
 */
object ImagePlugin {
    suspend fun generate(
        app: Application,
        model: AiModel,
        prompt: String,
        refImage: ByteArray?,
        size: String = "1024x1024"
    ): Attachment {
        val result: MediaResult = MediaGenerator.generateImage(model, prompt, refImage, size)
        val path = MediaGenerator.saveToFile(app, result, "png", "media")
            ?: throw RuntimeException("生成结果保存失败")
        return Attachment(
            kind = AttachmentKind.IMAGE,
            name = "ai_image_" + System.currentTimeMillis() + ".png",
            mimeType = "image/png",
            sizeBytes = File(path).length(),
            uri = "file://" + path,
            generated = true
        )
    }
}
