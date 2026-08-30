package com.xs.chat.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 联网搜索插件：实时联网搜索并返回结果摘要。
 * 数据源按网络环境依次尝试：
 * ① Bing RSS（format=rss 服务端渲染 XML，无 JS；国内网络 www/cn.bing.com 常被 ISP 劫持，
 *    优先用未被劫持的 global.bing.com，均失败再回退 www/cn）
 * ② DuckDuckGo Instant Answer API（无 key，海外可用，命中即时摘要直接返回）
 */
object WebSearchPlugin {
    private const val TIMEOUT_MS = 5000
    private const val MAX_RESULTS = 5

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext "请输入要搜索的内容"
        val rss = searchBingRss(q)
        if (!rss.isNullOrBlank()) return@withContext rss
        val ddg = runCatching { searchDuckDuckGo(q) }.getOrNull()
        if (!ddg.isNullOrBlank()) return@withContext ddg
        "❌ 联网搜索失败：暂时无法获取结果，请稍后重试"
    }

    /** Bing RSS：依次尝试多个域名（global 未被国内 DNS 劫持）。 */
    private fun searchBingRss(query: String): String? {
        for (host in listOf("global.bing.com", "www.bing.com", "cn.bing.com")) {
            val url = "https://$host/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=rss"
            val xml = httpGet(url) ?: continue
            val sb = StringBuilder("🔍 搜索：$query")
            var count = 0
            val itemRe = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            for (item in itemRe.findAll(xml)) {
                if (count >= MAX_RESULTS) break
                val block = item.groupValues[1]
                val title = tag(block, "title") ?: continue
                val link = tag(block, "link") ?: continue
                val snippet = tag(block, "description") ?: ""
                sb.append("\n\n").append(title)
                if (link.isNotBlank()) sb.append("\n").append(link)
                if (snippet.isNotBlank()) sb.append("\n").append(snippet.take(300))
                count++
            }
            if (count > 0) return sb.toString()
        }
        return null
    }

    /** 从 RSS item 里取单标签内容（去 CDATA / HTML 实体）。 */
    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return stripHtml(m.groupValues[1]).trim().ifEmpty { null }
    }

    /** DuckDuckGo Instant Answer：有摘要直接返回。 */
    private fun searchDuckDuckGo(query: String): String? {
        val url = "https://api.duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&no_html=1&skip_disambig=1"
        val body = httpGet(url) ?: return null
        val json = JSONObject(body)
        val abstractText = json.optString("AbstractText").trim()
        val abstractUrl = json.optString("AbstractURL").trim()
        if (abstractText.isNotEmpty()) {
            return "🔍 搜索：$query\n\n$abstractText\n\n来源：$abstractUrl"
        }
        // RelatedTopics 摘要
        val topics = json.optJSONArray("RelatedTopics")
        if (topics != null && topics.length() > 0) {
            val sb = StringBuilder("🔍 搜索：$query\n")
            var n = 0
            for (i in 0 until topics.length()) {
                if (n >= MAX_RESULTS) break
                val t = topics.optJSONObject(i)
                val text = t?.optString("Text") ?: continue
                val firstUrl = t?.optString("FirstURL") ?: continue
                if (text.isNotBlank()) {
                    sb.append("\n• ").append(text.take(200))
                    if (firstUrl.isNotBlank()) sb.append("\n  ").append(firstUrl)
                    n++
                }
            }
            return if (n > 0) sb.toString() else null
        }
        return null
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) XSChat/1.2")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (conn.responseCode !in 200..299) return null
            val input = conn.inputStream ?: return null
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var n: Int
            while (input.read(chunk).also { n = it } > 0) buf.write(chunk, 0, n)
            input.close()
            conn.disconnect()
            String(buf.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun stripHtml(s: String): String {
        var out = s.trim()
        if (out.startsWith("<![CDATA[")) out = out.removePrefix("<![CDATA[").removeSuffix("]]>")
        return out.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
