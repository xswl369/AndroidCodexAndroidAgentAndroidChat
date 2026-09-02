package com.xs.chat.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import android.util.Base64
import java.net.URLEncoder
import java.util.Locale

/** 媒体生成结果：base64 优先，url 兜底。 */
data class MediaResult(
    val url: String? = null,
    val base64: String? = null,
    val mimeType: String = "image/png"
)

/**
 * 内置媒体生成器（OpenAI 兼容 + AGNES 规范）：
 * - 文生图/图生图：POST images/generations
 * - 图生视频：POST videos -> 轮询 videos/{id}（mode=text2video/image2video，duration 秒，size 可配置）
 * 结果支持 url 与 b64_json 两种返回格式。
 */
object MediaGenerator {
    private const val CONNECT_TIMEOUT = 30_000
    private const val IMAGE_TIMEOUT = 3 * 60_000L
    private const val VIDEO_TIMEOUT = 10 * 60_000L
    private const val POLL_INTERVAL = 8_000L

    /** 视频/图片生成能力契约。 */
    private enum class GenVendor { AGNES_V25, AGNES_V2, OPENAI_COMPAT, GEMINI }

    suspend fun generateImage(
        model: AiModel,
        prompt: String,
        refImage: ByteArray?,
        size: String = "1024x1024"
    ): MediaResult = withContext(Dispatchers.IO) {
        // Gemini 走 generateContent；其余走 OpenAI 兼容 /images/generations（AGNES/Flux/DALL-E 等均适用）
        if (vendorOf(model) == GenVendor.GEMINI) return@withContext geminiImage(model, prompt, refImage, size)
        val body = JsonObject().apply {
            addProperty("model", model.modelId)
            addProperty("prompt", prompt)
            addProperty("n", 1)
            addProperty("size", size)
            if (refImage != null) addProperty("image", b64(refImage))
        }
        val resp = postJson(model, "images/generations", body.toString(), IMAGE_TIMEOUT)
        parseImageResult(resp)
    }

    /** 生成能力契约路由：AGNES 两类、Gemini、其余走 OpenAI 兼容（Sora/Veo 中转/可灵等）。 */
    private fun vendorOf(model: AiModel): GenVendor {
        val id = model.modelId.lowercase(Locale.ROOT)
        val base = model.baseUrl.lowercase(Locale.ROOT)
        return when {
            // agnes-video-v2.0 专属 ti2vid/keyframes 契约；其余 agnes-video-2.5* 走 v2.5 text/keyframe 契约
            id.contains("v2.0") && id.contains("agnes") -> GenVendor.AGNES_V2
            id.contains("agnes") || base.contains("agnes") -> GenVendor.AGNES_V25
            id.contains("gemini") || base.contains("generativelanguage") || base.contains("gemini") -> GenVendor.GEMINI
            else -> GenVendor.OPENAI_COMPAT
        }
    }

    /** Gemini（Imagen 系列）文生图/图生图：generateContent + responseModalities=[TEXT,IMAGE]。 */
    private suspend fun geminiImage(model: AiModel, prompt: String, refImage: ByteArray?, size: String): MediaResult {
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            if (refImage != null) {
                add(JsonObject().apply {
                    val data = JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", b64(refImage))
                    }
                    add("inline_data", data)
                })
            }
        }
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", parts)
                })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("TEXT"); add("IMAGE") })
                add("imageConfig", JsonObject().apply { addProperty("aspectRatio", geminiRatio(size)) })
            })
        }
        val resp = postJson(model, "v1beta/models/${model.modelId}:generateContent", body.toString(), IMAGE_TIMEOUT)
        val o = parseObject(resp) ?: throw ApiException("Gemini 图片接口返回异常: ${resp.take(200)}")
        return extractGeminiMedia(o) ?: throw ApiException("Gemini 图片接口未返回图片数据")
    }

    /** 图片尺寸 WxH → Gemini 宽高比档位。 */
    private fun geminiRatio(size: String): String {
        val m = Regex("(\\d+)\\s*[xX×]\\s*(\\d+)").find(size) ?: return "1:1"
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        return when {
            w > h -> "16:9"
            h > w -> "9:16"
            else -> "1:1"
        }
    }

    suspend fun generateVideo(
        model: AiModel,
        prompt: String,
        firstFrame: ByteArray?,
        size: String = "720P",
        seconds: Int = 5,
        onProgress: ((Int) -> Unit)? = null
    ): MediaResult = withContext(Dispatchers.IO) {
        // 智能调度：按模型特征路由到 AGNES 2.5 / AGNES v2.0 / Gemini Veo / OpenAI 兼容视频契约
        when (vendorOf(model)) {
            GenVendor.AGNES_V2 -> agnesSubmit(model, buildVideoBodyV2(model, prompt, firstFrame, size, seconds), onProgress)
            GenVendor.AGNES_V25 -> agnesSubmit(model, buildVideoBodyV25(model, prompt, firstFrame, seconds), onProgress)
            GenVendor.GEMINI -> geminiVideo(model, prompt, firstFrame, size, seconds)
            GenVendor.OPENAI_COMPAT -> openAiVideo(model, prompt, firstFrame, size, seconds, onProgress)
        }
    }

    /** AGNES 视频提交：POST /videos → 任务轮询（2.5 用 /agnesapi，v2.0 用 /videos/{id}）。 */
    private suspend fun agnesSubmit(model: AiModel, body: String, onProgress: ((Int) -> Unit)? = null): MediaResult {
        val resp = postJson(model, "videos", body, VIDEO_TIMEOUT)
        val o = parseObject(resp) ?: throw ApiException("视频接口返回异常: ${resp.take(200)}")
        extractVideoUrl(o)?.let { return MediaResult(url = it, mimeType = "video/mp4") }
        val videoId = extractVideoId(o) ?: throw ApiException("视频接口未返回任务ID: ${resp.take(300)}")
        return pollVideo(model, videoId, onProgress)
    }

    /** OpenAI 兼容视频（Sora/Veo 中转/可灵等）：POST /videos → 轮询 GET /videos/{id}。 */
    private suspend fun openAiVideo(model: AiModel, prompt: String, firstFrame: ByteArray?, size: String, seconds: Int, onProgress: ((Int) -> Unit)? = null): MediaResult {
        val base = model.baseUrl.trimEnd('/')
        val body = JsonObject().apply {
            addProperty("model", model.modelId)
            addProperty("prompt", prompt)
            // OpenAI 契约：720p/1080p；时长 5s/10s/15s（收敛到合法档）
            addProperty("size", if (size.trim().equals("2K", true)) "1080p" else "720p")
            addProperty("duration", seconds.coerceIn(5, 15).toString() + "s")
            if (firstFrame != null) {
                add("input_reference", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        addProperty("url", dataUrl(firstFrame, "image/jpeg"))
                    })
                })
            }
        }
        val resp = postJson(model, "videos", body.toString(), VIDEO_TIMEOUT)
        val o = parseObject(resp) ?: throw ApiException("视频接口返回异常: ${resp.take(200)}")
        extractVideoUrl(o)?.let { return MediaResult(url = it, mimeType = "video/mp4") }
        val id = extractVideoId(o) ?: throw ApiException("视频接口未返回任务ID: ${resp.take(300)}")
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT
        var lastProgress = 0
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val code = query("$base/videos/$id", model.apiKey)
            if (code in 200..299) {
                val ob = parseObject(readBody("$base/videos/$id", model.apiKey, code)) ?: continue
                // server progress may be 0-1 float/string/with percent, phase switch may reset: normalize + monotonic hold
                normalizedProgress(ob.get("progress"))?.let { p ->
                    if (p > lastProgress) { lastProgress = p; onProgress?.invoke(p) }
                }
                val status = videoStatus(ob)
                if (isDone(status) || ob.get("done")?.asBoolean == true) {
                    extractVideoUrl(ob)?.let { return MediaResult(url = it, mimeType = "video/mp4") }
                    throw ApiException("视频完成但未返回下载地址")
                }
                if (isFailed(status)) throw ApiException("视频生成失败: ${firstStr(ob, "error", "message") ?: status}")
            }
            delay(POLL_INTERVAL)
        }
        throw ApiException("视频生成超时（10 分钟）")
    }

    /** Gemini Veo 视频：generateContent 长任务 → 轮询 operations/{name} → inlineData。 */
    private suspend fun geminiVideo(model: AiModel, prompt: String, firstFrame: ByteArray?, size: String, seconds: Int): MediaResult {
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            if (firstFrame != null) {
                add(JsonObject().apply {
                    val data = JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", b64(firstFrame))
                    }
                    add("inline_data", data)
                })
            }
        }
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", parts)
                })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("VIDEO"); add("TEXT") })
                add("videoConfig", JsonObject().apply {
                    addProperty("resolution", if (size.trim().equals("2K", true)) "1080p" else "720p")
                    addProperty("aspectRatio", "16:9")
                })
            })
        }
        val resp = postJson(model, "v1beta/models/${model.modelId}:generateContent", body.toString(), VIDEO_TIMEOUT)
        val o = parseObject(resp) ?: throw ApiException("Gemini 视频接口返回异常: ${resp.take(200)}")
        // 同步返回视频 inlineData（部分模型立即返回）
        extractGeminiMedia(o)?.let { return it }
        // 长任务：返回 operation name 后轮询
        val op = firstStr(o, "name")?.takeIf { it.contains("operations", true) }
            ?: throw ApiException("Gemini 视频接口未返回任务: ${resp.take(300)}")
        val base = model.baseUrl.trimEnd('/')
        var cur = op
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val code = query("$base/v1beta/$cur", model.apiKey)
            if (code in 200..299) {
                val ob = parseObject(readBody("$base/v1beta/$cur", model.apiKey, code)) ?: continue
                if (ob.get("done")?.asBoolean == true) {
                    val err = ob.getAsJsonObject("error")
                    if (err != null) throw ApiException("Gemini 视频生成失败: ${firstStr(err, "message")}")
                    extractGeminiMedia(ob.getAsJsonObject("response"))?.let { return it }
                    extractGeminiMedia(ob)?.let { return it }
                    throw ApiException("Gemini 视频完成但未返回媒体数据")
                }
            }
            delay(POLL_INTERVAL)
        }
        throw ApiException("Gemini 视频生成超时（10 分钟）")
    }

    /** 从 Gemini generateContent 响应提取视频/图片 inlineData（兼容 inline_data 与 inlineData 两种键名）。 */
    private fun extractGeminiMedia(o: JsonObject?): MediaResult? {
        val candidates = o?.getAsJsonArray("candidates") ?: return null
        for (c in candidates) {
            val content = c.asJsonObject.getAsJsonObject("content") ?: continue
            val parts = content.getAsJsonArray("parts") ?: continue
            for (p in parts) {
                val d = p.asJsonObject.getAsJsonObject("inline_data") ?: p.asJsonObject.getAsJsonObject("inlineData")
                val b64 = d?.let { firstStr(it, "data") } ?: continue
                val mime = d?.let { firstStr(it, "mime_type", "mimeType") } ?: "video/mp4"
                return MediaResult(null, b64, mime)
            }
        }
        return null
    }

    /** agnes-video-2.5 系列：mode=text(文生视频)/keyframe(首帧图生视频)；num_frames/fps 为禁止字段。 */
    private fun buildVideoBodyV25(model: AiModel, prompt: String, firstFrame: ByteArray?, seconds: Int): String =
        JsonObject().apply {
            addProperty("model", model.modelId)
            addProperty("mode", if (firstFrame != null) "keyframe" else "text")
            addProperty("prompt", prompt)
            // 2.5 系列服务端仅支持 4-12 秒，超出时收敛到边界；分辨率实测仅接受 720P，强制该档避免失败
            addProperty("seconds", seconds.coerceIn(4, 12).toString())
            addProperty("size", "720P")
            if (firstFrame != null) addProperty("first_frame", dataUrl(firstFrame, "image/jpeg"))
        }.toString()

    /** agnes-video-v2.0：mode=ti2vid(文生视频)/keyframes(关键帧图生视频)，num_frames 8n+1 且 ≤441。 */
    private fun buildVideoBodyV2(model: AiModel, prompt: String, firstFrame: ByteArray?, size: String, seconds: Int): String {
        // 官方契约（agnes-video-v2.0）：8n+1 帧数（≤441），width/height 可选默认 1152x768，mode=ti2vid/keyframes
        val numFrames = (seconds.coerceIn(1, 18) * 24).let { n -> ((n + 7) / 8) * 8 + 1 }.coerceAtMost(441)
        val (w, h) = v2Resolution(size)
        val body = JsonObject().apply {
            addProperty("model", model.modelId)
            addProperty("prompt", prompt)
            addProperty("mode", "ti2vid")
            addProperty("width", w)
            addProperty("height", h)
            addProperty("num_frames", numFrames)
            addProperty("frame_rate", 24)
            if (firstFrame != null) {
                // 图生视频：单图 data URL（关键帧多图 extra_body.image 数组暂由单图场景覆盖）
                addProperty("image", dataUrl(firstFrame, "image/jpeg"))
            }
        }
        return body.toString()
    }

    /** v2.0 分辨率档位 → 像素（服务端会标准化到 480p/720p/1080p 档位）。 */
    private fun v2Resolution(size: String): Pair<Int, Int> = when (size.trim().uppercase(Locale.ROOT)) {
        "720P" -> 1280 to 720
        "960P" -> 1280 to 960
        "2K" -> 1920 to 1080
        else -> 1152 to 768
    }

    /** 将生成结果保存为本地文件（base64 优先，URL 下载兜底）；含网络下载，须在 IO 线程执行，失败抛出真实原因。 */
    suspend fun saveToFile(app: android.app.Application, result: MediaResult, ext: String, dirName: String): String? =
        withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, dirName).apply { mkdirs() }
            val target = File(dir, System.currentTimeMillis().toString() + "." + ext)
            if (result.base64 != null) {
                target.writeBytes(Base64.decode(result.base64, Base64.NO_WRAP))
            } else {
                val url = result.url ?: throw ApiException("接口未返回媒体数据（url 为空）")
                downloadTo(url, target, retries = 2)
            }
            target.absolutePath
        }

    /** AGNES 异步视频任务轮询：2.5 系列用 /agnesapi?video_id=，v2.0 用 /videos/{id} 等候选。 */
    private suspend fun pollVideo(model: AiModel, videoId: String, onProgress: ((Int) -> Unit)? = null): MediaResult {
        val base = model.baseUrl.trimEnd('/')
        val isV2 = model.modelId.contains("v2.0", ignoreCase = true)
        val urls = if (isV2) {
            listOf(
                "$base/agnesapi?video_id=${enc(videoId)}&model_name=${enc(model.modelId)}",
                "$base/videos/$videoId",
                "$base/tasks/$videoId"
            )
        } else {
            listOf("$base/agnesapi?video_id=${enc(videoId)}&model_name=${enc(model.modelId)}")
        }
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT
        var lastProgress = 0
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            for (url in urls) {
                runCatching {
                    val code = query(url, model.apiKey)
                    if (code !in 200..299) return@runCatching
                    val body = readBody(url, model.apiKey, code)
                    val o = parseObject(body) ?: return@runCatching
                    // multiple candidate endpoints may return different phase progress; only take monotonic max
                    normalizedProgress(o.get("progress"))?.let { p ->
                        if (p > lastProgress) { lastProgress = p; onProgress?.invoke(p) }
                    }
                    val status = videoStatus(o)
                    if (isDone(status)) {
                        extractVideoUrl(o)?.let { return MediaResult(url = it, mimeType = "video/mp4") }
                        throw ApiException("视频完成但未返回下载地址")
                    }
                    if (isFailed(status)) {
                        throw ApiException("视频生成失败: ${firstStr(o, "error", "message") ?: status}")
                    }
                }
            }
            delay(POLL_INTERVAL)
        }
        throw ApiException("视频生成超时（10 分钟）")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 服务端 progress 归一化：兼容 0-100 整数、0-1 小数、带 % 字符串；非法值返回 null。 */
    private fun normalizedProgress(el: JsonElement?): Int? {
        if (el == null || !el.isJsonPrimitive) return null
        val raw = el.asString.trim().removeSuffix("%")
        val v = raw.toDoubleOrNull() ?: return null
        val pct = if (v in 0.0..1.0) v * 100 else v
        return pct.toInt().coerceIn(0, 100)
    }

    private fun videoStatus(o: JsonObject): String {
        val out = o.getAsJsonObject("output")
        return listOf("status", "state", "task_status")
            .mapNotNull { k -> out?.get(k) ?: o.get(k) }
            .firstNotNullOfOrNull { e -> str(e) }
            ?: ""
    }

    private fun isDone(s: String) = s.isBlank().not() && listOf(
        "success", "succeeded", "SUCCESS", "completed", "done", "complete", "finished", "SUCCEEDED"
    ).any { it.equals(s, ignoreCase = true) }

    private fun isFailed(s: String) = listOf("failed", "FAILED", "error", "ERROR", "UNKNOWN")
        .any { it.equals(s, ignoreCase = true) }

    private fun extractVideoId(o: JsonObject): String? {
        val data = o.getAsJsonArray("data")
        if (data != null && data.size() > 0) {
            firstStr(data.get(0).asJsonObject, "video_id", "id", "task_id")?.let { return it }
        }
        return firstStr(o, "video_id", "id", "task_id", "generation_id", "request_id")
    }

    private fun extractVideoUrl(o: JsonObject): String? {
        val out = o.getAsJsonObject("output")
        firstStr(out, "video_url", "url", "result_url")?.let { return it }
        // AGNES：metadata.url
        val meta = o.getAsJsonObject("metadata")
        firstStr(meta, "url", "video_url")?.let { return it }
        // AGNES：videos[].url
        val videos = o.getAsJsonArray("videos")
        if (videos != null && videos.size() > 0) {
            firstStr(videos.get(0).asJsonObject, "url", "video_url")?.let { return it }
        }
        val data = o.getAsJsonArray("data")
        if (data != null && data.size() > 0) {
            firstStr(data.get(0).asJsonObject, "url", "video_url")?.let { return it }
        }
        return firstStr(o, "video_url", "url", "result_url", "result")
    }

    private fun parseImageResult(resp: String): MediaResult {
        val o = parseObject(resp) ?: throw ApiException("图片接口返回异常: ${resp.take(200)}")
        val data = o.getAsJsonArray("data")
        if (data != null && data.size() > 0) {
            val d = data.get(0).asJsonObject
            val url = firstStr(d, "url", "image_url", "b64_url")
            val b64 = firstStr(d, "b64_json", "b64", "base64")
            if (url != null || b64 != null) return MediaResult(url, b64, "image/png")
        }
        val out = o.getAsJsonObject("output")
        val results = out?.getAsJsonArray("results")
        if (results != null && results.size() > 0) {
            val r = results.get(0).asJsonObject
            val url = firstStr(r, "url", "image_url")
            if (url != null) return MediaResult(url, null, "image/png")
        }
        val url = firstStr(o, "url", "image_url", "result_url")
        val b64 = firstStr(o, "b64_json", "b64", "data")
        return MediaResult(url, b64, "image/png")
    }

    private fun postJson(model: AiModel, endpoint: String, body: String, timeoutMs: Long): String {
        val url = model.baseUrl.trimEnd('/') + "/" + endpoint.trimStart('/')
        return withConn(url, model.apiKey, timeoutMs) { c ->
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(readError(c), code)
            c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    private fun query(url: String, apiKey: String): Int =
        withConn(url, apiKey, 30_000L) { c ->
            c.requestMethod = "GET"
            c.responseCode
        }

    private fun readBody(url: String, apiKey: String, code: Int): String =
        withConn(url, apiKey, 30_000L) { c ->
            if (code in 200..299) c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            else c.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        }

    /** 下载媒体文件，失败自动重试（半成品文件先清理）。DNS 解析失败由 withConn 自动走 DoH 兜底。 */
    private fun downloadTo(url: String, target: File, retries: Int = 0) {
        var last: Exception? = null
        repeat(retries + 1) {
            try {
                withConn(url, "", 180_000L) { c ->
                    val code = c.responseCode
                    if (code !in 200..299) throw ApiException("下载失败 HTTP $code")
                    c.inputStream.use { input -> FileOutputStream(target).use { out -> input.copyTo(out) } }
                }
                return
            } catch (e: Exception) {
                last = e
                target.delete()
            }
        }
        throw last ?: ApiException("下载失败")
    }

    /**
     * 统一网络入口：系统 DNS 解析失败（UnknownHostException，如
     * 「Unable to resolve host platform-outputs.agnes-ai.space」）时，用 DoH
     * （阿里/DNSPod/Cloudflare/Google 多源）解析出 IP 后按 IP 直连重试，
     * 同时保留原 Host 头与 TLS 证书校验（按原域名验证），规避系统 DNS 解析失败。
     */
    private fun <T> withConn(url: String, apiKey: String, timeoutMs: Long, block: (HttpURLConnection) -> T): T {
        var c: HttpURLConnection? = null
        try {
            c = openConn(url, apiKey, timeoutMs)
            return block(c)
        } catch (e: UnknownHostException) {
            val host = hostOf(url)
            val ip = resolveViaDoh(host) ?: throw e
            c?.disconnect()
            c = openConn(ipUrlOf(url, ip), apiKey, timeoutMs, originalHost = host)
            return block(c)
        } finally {
            c?.disconnect()
        }
    }

    private fun openConn(url: String, apiKey: String, timeoutMs: Long, originalHost: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT
        c.readTimeout = timeoutMs.toInt()
        c.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        if (originalHost != null) {
            c.setRequestProperty("Host", originalHost)
            if (c is HttpsURLConnection) {
                c.hostnameVerifier = HostnameVerifier { _, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session)
                }
            }
        }
        return c
    }

    private fun hostOf(url: String): String = URL(url).host

    /** 原 URL 的主机名替换为 IP（保留协议/端口/路径/查询串）。 */
    private fun ipUrlOf(url: String, ip: String): String {
        val u = URL(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        return "${u.protocol}://$ip$port${u.file}"
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** DoH 多源解析：返回首个 IPv4 地址，全部失败返回 null。 */
    private fun resolveViaDoh(host: String): String? {
        val endpoints = listOf(
            "https://dns.alidns.com/resolve",
            "https://doh.pub/dns-query",
            "https://1.1.1.1/dns-query",
            "https://cloudflare-dns.com/dns-query",
            "https://8.8.8.8/resolve",
            "https://dns.google/resolve"
        )
        for (ep in endpoints) {
            try {
                val c = URL("$ep?type=A&name=${enc(host)}").openConnection() as HttpURLConnection
                c.connectTimeout = CONNECT_TIMEOUT
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/dns-json")
                try {
                    if (c.responseCode in 200..299) {
                        val o = parseObject(c.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
                        val arr = o?.getAsJsonArray("Answer")
                        if (arr != null) {
                            for (i in 0 until arr.size()) {
                                val e = arr.get(i)
                                if (!e.isJsonObject) continue
                                val data = e.asJsonObject.get("data")?.takeIf { it.isJsonPrimitive }?.asString
                                if (data != null && IPV4.matches(data)) return data
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseObject(body: String): JsonObject? = runCatching {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun firstStr(o: JsonObject?, vararg keys: String): String? {
        if (o == null) return null
        for (k in keys) {
            o.get(k)?.let { e -> str(e)?.let { return it } }
        }
        return null
    }

    private fun str(e: JsonElement): String? = when {
        e.isJsonNull -> null
        e.isJsonPrimitive -> e.asString.takeIf { it.isNotBlank() }
        e.isJsonObject -> firstStr(e.asJsonObject, "url", "content", "message")
        else -> null
    }

    private fun readError(c: HttpURLConnection): String {
        return try {
            val body = c.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val msg = runCatching {
                JsonParser.parseString(body).asJsonObject
                    .getAsJsonObject("error")?.get("message")?.asString ?: body
            }.getOrDefault(body)
            "HTTP ${c.responseCode}: ${msg.take(400)}"
        } catch (e: Exception) {
            "HTTP ${c.responseCode}"
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun dataUrl(bytes: ByteArray, mime: String): String = "data:$mime;base64," + b64(bytes)
}
