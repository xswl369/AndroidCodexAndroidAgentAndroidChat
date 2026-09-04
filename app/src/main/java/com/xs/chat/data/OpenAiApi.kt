package com.xs.chat.data

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class ApiException(message: String, val statusCode: Int = 0) : Exception(message)

/**
 * OpenAI 兼容 HTTP 客户端：支持 SSE 流式输出与模型列表拉取。
 * 任意符合 /chat/completions 规范的服务（OpenAI/DeepSeek/Qwen/GLM/Ollama 等）均可接入。
 */
class OpenAiApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 5 * 60_000
) {
    private companion object {
        private const val DIAG_TAG = "OpenAiStreamDiag"
    }

    @Volatile
    private var cancelled = false

    private var conn: HttpURLConnection? = null

    /** 立即中断当前请求（通过断开连接使阻塞读返回）。 */
    fun cancel() {
        cancelled = true
        conn?.disconnect()
    }

    fun listModels(): List<ServerModelInfo> {
        val c = open("GET", endpoint("/models"))
        return try {
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            val body = bodyReader(c).use { it.readText() }
            val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data") ?: return emptyList()
            data.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    ServerModelInfo(
                        id = o.get("id")?.asString ?: return@runCatching null,
                        ownedBy = o.get("owned_by")?.asString ?: "",
                        createdAt = o.get("created")?.asLong ?: 0
                    )
                }.getOrNull()
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * 流式对话。逐段回调 [onDelta]；返回 true 表示正常结束，false 表示被取消。
     */
    fun streamChat(
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        temperature: Float,
        reasoningEffort: String? = null,
        attachmentParts: Map<String, List<ContentPart>> = emptyMap(),
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)? = null
    ): Boolean {
        cancelled = false
        val c = open("POST", endpoint("/chat/completions"))
        // SSE 专用头：防止中间层缓冲导致首 token 延迟
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Cache-Control", "no-cache")
        var keepAlive = false
        try {
            val req = JsonObject()
            req.addProperty("model", model)
            req.addProperty("stream", true)
            req.addProperty("temperature", temperature)
            addReasoningEffort(req, reasoningEffort)
            val streamOpts = JsonObject()
            streamOpts.addProperty("include_usage", true)
            req.add("stream_options", streamOpts)
            val arr = com.google.gson.JsonArray()
            if (!systemPrompt.isNullOrBlank()) arr.add(jsonMessage("system", systemPrompt))
            messages.forEach { msg ->
                val parts = attachmentParts[msg.id]
                if (parts.isNullOrEmpty()) {
                    arr.add(jsonMessage(msg.role.name.lowercase(), msg.content))
                } else {
                    val obj = JsonObject()
                    obj.addProperty("role", msg.role.name.lowercase())
                    val content = com.google.gson.JsonArray()
                    parts.forEach { content.add(partJson(it)) }
                    obj.add("content", content)
                    arr.add(obj)
                }
            }
            req.add("messages", arr)

            c.outputStream.use {
                it.write(req.toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }

            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            val msgCount = req.getAsJsonArray("messages")?.size() ?: 0
            Log.w(DIAG_TAG, "code=$code model=$model msgs=$msgCount promptChars=${req.toString().length}")

            val reader = bodyReader(c)
            var sawContent = false
            var toolQuery: String? = null
            var reasoningChars = 0
            while (true) {
                if (cancelled) return false
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                val obj = try {
                    JsonParser.parseString(payload).asJsonObject
                } catch (e: Exception) {
                    null
                }
                val deltaObj = try {
                    obj?.getAsJsonArray("choices")
                        ?.takeIf { it.size() > 0 }
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                } catch (e: Exception) {
                    null
                }
                // content 兼容 string 与 JSON 数组分片：JSON 数组用 asString 会整片抛错，导致整条回复被丢
                val content = extractContent(deltaObj?.get("content"))
                // 只认实质性正文：思考型模型常只回空白行+reasoning，空白误算正文会导致气泡“有内容且空白”
                if (content.isNotBlank()) {
                    sawContent = true
                    onDelta(content)
                }
                // 部分网关把正文流在 reasoning_content（思考型模型/输出额度被思考占满），累积备用
                reasoningChars += extractContent(deltaObj?.get("reasoning_content")).length
                // 原生 tool_calls（部分网关思考型模型用）：正文为空时转内置搜索流程
                extractToolCallQuery(deltaObj)?.takeIf { it.isNotBlank() }?.let { toolQuery = it }
                // 携带 usage 的 chunk（通常为最后一个）：无论是否带 choices 都解析
                obj?.get("usage")?.let { u ->
                    onUsage?.invoke(parseUsage(u))
                }
            }
            runCatching { reader.close() }
            keepAlive = true
            if (!sawContent) {
                if (toolQuery != null) {
                    Log.w(DIAG_TAG, "empty content but tool_call query=$toolQuery")
                    onDelta("function:web_search(\"query\": \"$toolQuery\")")
                }
            }
            Log.w(DIAG_TAG, "done sawContent=$sawContent reasoningChars=$reasoningChars tool=${toolQuery != null}")
            return true
        } finally {
            if (!keepAlive) c.disconnect()
        }
    }

    /**
     * 非流式补全：一次性返回完整文本（用于文件编辑等内置插件）。
     * [imageDataUrl] 非空时，最后一条 user 消息附带一张图片（data URL，如 "data:image/jpeg;base64,..."），
     * 用于设备控制 Agent 的视觉读屏。
     */
    fun completeChat(
        model: String,
        systemPrompt: String?,
        userMessages: List<Pair<String, String>>,
        temperature: Float,
        imageDataUrl: String? = null,
        onUsage: ((Usage) -> Unit)? = null
    ): String {
        cancelled = false
        val c = open("POST", endpoint("/chat/completions"))
        var keepAlive = false
        return try {
            val req = JsonObject()
            req.addProperty("model", model)
            req.addProperty("stream", false)
            req.addProperty("temperature", temperature)
            val arr = com.google.gson.JsonArray()
            if (!systemPrompt.isNullOrBlank()) arr.add(jsonMessage("system", systemPrompt))
            userMessages.forEach { (role, content) ->
                if (imageDataUrl != null && role == "user") {
                    val obj = JsonObject()
                    obj.addProperty("role", role)
                    val contentArr = com.google.gson.JsonArray()
                    val textPart = JsonObject()
                    textPart.addProperty("type", "text")
                    textPart.addProperty("text", content)
                    val imgPart = JsonObject()
                    imgPart.addProperty("type", "image_url")
                    val imgObj = JsonObject()
                    imgObj.addProperty("url", imageDataUrl)
                    imgPart.add("image_url", imgObj)
                    contentArr.add(textPart)
                    contentArr.add(imgPart)
                    obj.add("content", contentArr)
                    arr.add(obj)
                } else {
                    arr.add(jsonMessage(role, content))
                }
            }
            req.add("messages", arr)
            c.outputStream.use {
                it.write(req.toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            val body = bodyReader(c).use { it.readText() }
            val root = JsonParser.parseString(body).asJsonObject
            val result = root
                .getAsJsonArray("choices")?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: throw ApiException("接口未返回内容")
            root.get("usage")?.let { onUsage?.invoke(parseUsage(it)) }
            keepAlive = true
            result
        } finally {
            if (!keepAlive) c.disconnect()
        }
    }

    private fun parseUsage(u: com.google.gson.JsonElement): Usage = try {
        val o = u.asJsonObject
        Usage(
            promptTokens = o.get("prompt_tokens")?.asLong ?: 0,
            completionTokens = o.get("completion_tokens")?.asLong ?: 0
        )
    } catch (e: Exception) {
        Usage()
    }

    private fun partJson(part: ContentPart): JsonObject = JsonObject().apply {
        addProperty("type", part.type)
        when (part.type) {
            "text" -> addProperty("text", part.text)
            "image_url" -> {
                val o = JsonObject()
                o.addProperty("url", part.dataUrl)
                add("image_url", o)
            }
            "input_video" -> {
                val o = JsonObject()
                o.addProperty("url", part.dataUrl)
                add("video_url", o)
            }
            "file" -> {
                val o = JsonObject()
                o.addProperty("url", part.dataUrl)
                if (part.fileName.isNotBlank()) o.addProperty("filename", part.fileName)
                add("file", o)
            }
        }
    }

    /** 语音转文字（OpenAI 兼容 /audio/transcriptions，Whisper 风格），PCM 自动封装为 WAV。 */
    fun transcribe(audio: ByteArray, language: String? = "zh"): String {
        val wav = wavWithHeader(audio)
        val boundary = "----XS" + System.currentTimeMillis()
        val c = URL(endpoint("/audio/transcriptions")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        conn = c
        val body = ByteArrayOutputStream()
        fun part(name: String, value: String) {
            body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
        }
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray())
        body.write(wav)
        body.write("\r\n".toByteArray())
        part("model", "whisper-1")
        if (!language.isNullOrBlank()) part("language", language)
        body.write("--$boundary--\r\n".toByteArray())
        return try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            val text = bodyReader(c).use { it.readText() }
            runCatching { JsonParser.parseString(text).asJsonObject.get("text")?.asString }
                .getOrNull()?.trim().orEmpty()
        } finally {
            c.disconnect()
        }
    }

    /** 文字转语音（OpenAI 兼容 /audio/speech），返回合成音频字节（mp3）。 */
    fun speak(text: String, voice: String): ByteArray {
        val req = JsonObject()
        req.addProperty("model", "tts-1")
        req.addProperty("input", text)
        req.addProperty("voice", voice)
        val c = open("POST", endpoint("/audio/speech"))
        return try {
            c.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            val out = ByteArrayOutputStream()
            val raw = c.inputStream
            val stream = if (isGzip(c)) GZIPInputStream(raw) else raw
            stream.use { it.copyTo(out) }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** 将裸 PCM（16bit 单声道）封装为 WAV。 */
    private fun wavWithHeader(pcm: ByteArray, sampleRate: Int = 44100): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val header = ByteArray(44)
        fun putStr(offset: Int, str: String) {
            for (i in str.indices) header[offset + i] = str[i].code.toByte()
        }
        fun putInt(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = ((v shr 8) and 0xff).toByte()
            header[offset + 2] = ((v shr 16) and 0xff).toByte()
            header[offset + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + pcm.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, channels * bits / 8)
        putShort(34, bits)
        putStr(36, "data")
        putInt(40, pcm.size)
        return header + pcm
    }

    /** 提取流式文本：兼容字符串或 JSON 数组（含 {type:"text",text:"..."} 分片）。 */
    private fun extractContent(el: JsonElement?): String = when {
        el == null || el.isJsonNull -> ""
        el.isJsonPrimitive -> el.asString
        el.isJsonArray -> buildString {
            val arr = el.asJsonArray
            for (i in 0 until arr.size()) {
                val part = arr.get(i)
                when {
                    part.isJsonPrimitive -> append(part.asString)
                    part.isJsonObject && part.asJsonObject.get("text")?.isJsonPrimitive == true ->
                        append(part.asJsonObject.get("text").asString)
                }
            }
        }
        else -> ""
    }

    /** 从原生 tool_calls 增量里提取搜索 query（function 名含 search 即视为搜索工具）。 */
    private fun extractToolCallQuery(deltaObj: JsonObject?): String? {
        val arr = try {
            deltaObj?.getAsJsonArray("tool_calls")
        } catch (e: Exception) {
            null
        } ?: return null
        for (i in 0 until arr.size()) {
            val fn = try {
                arr.get(i).asJsonObject.getAsJsonObject("function")
            } catch (e: Exception) {
                null
            } ?: continue
            val name = runCatching { fn.get("name")?.asString }.getOrNull().orEmpty().lowercase()
            if (name.isNotBlank() && !name.contains("search")) continue
            val args = runCatching { fn.get("arguments")?.asString }.getOrNull()
            if (args.isNullOrBlank() && name.isBlank()) continue
            val argsObj = runCatching { JsonParser.parseString(args).asJsonObject }.getOrNull()
            val q = argsObj?.get("query")?.asString ?: argsObj?.get("q")?.asString
            if (args != null && argsObj == null) return args.trim().take(80)
            if (q != null && q.isNotBlank()) return q.trim().take(80)
        }
        return null
    }

    /** Codex 同款思考深度：low/medium/high/xhigh 映射为 reasoning_effort 参数；auto/关闭 不传，兼容不支持该参数的模型。 */
    private fun addReasoningEffort(req: JsonObject, effort: String?) {
        val value = effort?.lowercase()?.trim()
        if (value in setOf("low", "medium", "high", "xhigh")) {
            req.addProperty("reasoning_effort", value)
        }
    }

    private fun jsonMessage(role: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    private fun open(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Accept-Encoding", "gzip")
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
        }
        conn = c
        return c
    }

    private fun bodyReader(c: HttpURLConnection): BufferedReader {
        val raw = c.inputStream
        val stream = if (isGzip(c)) GZIPInputStream(raw) else raw
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
    }

    private fun isGzip(c: HttpURLConnection): Boolean =
        c.getHeaderField("Content-Encoding")?.lowercase()?.contains("gzip") == true

    private fun readError(c: HttpURLConnection): String {
        return try {
            val err = c.errorStream
            val body = err?.let {
                if (isGzip(c)) GZIPInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { r -> r.readText() }
                else it.bufferedReader(StandardCharsets.UTF_8).use { r -> r.readText() }
            }.orEmpty()
            val msg = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .getAsJsonObject("error")?.get("message")?.asString ?: body
            }.getOrDefault(body)
            "HTTP ${c.responseCode}: ${msg.take(400)}"
        } catch (e: Exception) {
            "HTTP ${c.responseCode}"
        }
    }
}


