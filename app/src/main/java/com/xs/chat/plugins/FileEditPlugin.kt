package com.xs.chat.plugins

import com.xs.chat.data.AiModel
import com.xs.chat.data.OpenAiApi
import com.xs.chat.data.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置文件修改插件：读取文本文件内容，让 AI 按指令改写，
 * 返回修改后的完整文件内容（去除 Markdown 围栏，并保留原始格式：
 * 换行符风格 / BOM / 尾部换行）。
 */
object FileEditPlugin {
    suspend fun edit(model: AiModel, instruction: String, fileName: String, content: String, onUsage: ((Usage) -> Unit)? = null): String {
        return withContext(Dispatchers.IO) {
            val api = OpenAiApi(model.baseUrl, model.apiKey, readTimeoutMs = 120_000)
            val system = "你是文件编辑助手。用户给出文件名、当前内容与修改要求。直接输出修改后的完整文件内容：" +
                "保持其余部分一字不改（包括缩进、空行、空格），只按需求修改；保持文件原有格式（换行符风格、BOM、尾部是否换行）不变。" +
                "只输出文件正文，禁止使用 Markdown 代码块围栏（如```），禁止添加任何解释、前缀或后缀。"
            val user = "文件名：$fileName\n修改要求：$instruction\n\n当前文件内容：\n$content"
            val raw = api.completeChat(model.modelId, system, listOf("user" to user), 0.2f, onUsage = onUsage)
            restoreFormat(content, stripFences(raw))
        }
    }

    private fun stripFences(text: String): String {
        var t = text
        // 去掉首行围栏（含语言标签）
        if (t.startsWith("```")) {
            val nl = t.indexOf('\n')
            t = if (nl >= 0) t.substring(nl + 1) else ""
        }
        // 去掉尾部围栏行，不裁剪正文自身的首尾空白
        t = t.trimEnd('\n', '\r')
        if (t.endsWith("```")) t = t.dropLast(3).trimEnd('\n', '\r')
        // 围栏后紧跟的空行是模型产物，去掉它
        if (t.startsWith("\n")) t = t.drop(1)
        return t + "\n"
    }

    /**
     * 恢复原始文件格式：换行符风格（CRLF/LF）、BOM、尾部换行状态。
     * 确保 AI 输出只改内容、不改格式。
     */
    private fun restoreFormat(original: String, edited: String): String {
        val lineSep = if (original.contains("\r\n")) "\r\n" else "\n"
        val hasBom = original.startsWith("\uFEFF")
        val trailingNewline = original.endsWith("\n")
        var text = edited.replace("\r\n", "\n")
        text = if (lineSep == "\r\n") text.replace("\n", "\r\n") else text
        if (trailingNewline) {
            if (!text.endsWith(lineSep)) text += lineSep
        } else {
            text = text.trimEnd('\n', '\r')
        }
        return (if (hasBom) "\uFEFF" else "") + text
    }
}

