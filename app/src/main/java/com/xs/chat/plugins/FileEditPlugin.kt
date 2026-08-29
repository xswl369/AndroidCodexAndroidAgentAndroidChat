package com.xs.chat.plugins

import com.xs.chat.data.AiModel
import com.xs.chat.data.OpenAiApi
import com.xs.chat.data.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置文件修改插件：读取文本文件内容，让 AI 按指令改写，
 * 返回修改后的完整文件内容（去除可能出现的 Markdown 围栏）。
 */
object FileEditPlugin {
    private const val MAX_FILE_CHARS = 200_000

    suspend fun edit(model: AiModel, instruction: String, fileName: String, content: String, onUsage: ((Usage) -> Unit)? = null): String {
        if (content.length > MAX_FILE_CHARS) throw RuntimeException("文件过大，仅支持 200KB 以内文本")
        return withContext(Dispatchers.IO) {
            val api = OpenAiApi(model.baseUrl, model.apiKey, readTimeoutMs = 120_000)
            val system = "你是文件编辑助手。用户给出文件名、当前内容与修改要求。直接输出修改后的完整文件内容：保持其余部分不变，只按需求修改。只输出文件正文，禁止使用 Markdown 代码块围栏（如```），禁止添加任何解释、前缀或后缀。"
            val user = "文件名：$fileName\n修改要求：$instruction\n\n当前文件内容：\n$content"
            val raw = api.completeChat(model.modelId, system, listOf("user" to user), 0.2f, onUsage = onUsage)
            stripFences(raw)
        }
    }

    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").substringAfter("\n", t.removePrefix("```")).trimStart()
        }
        if (t.endsWith("```")) t = t.removeSuffix("```").trimEnd()
        return t.trim() + "\n"
    }
}

