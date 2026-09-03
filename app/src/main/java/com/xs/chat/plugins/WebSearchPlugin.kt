package com.xs.chat.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * 联网搜索插件：实时联网搜索并返回结果摘要。
 * 数据源按国内网络环境依次尝试：
 * ① Bing RSS（cn.bing.com 优先，format=rss 服务端渲染 XML，国内可达性最稳）
 * ② Baidu 网页结果页（国内可达、无需 key）
 * ③ DuckDuckGo Instant Answer API（海外网络兜底）
 */
object WebSearchPlugin {
    private const val TIMEOUT_MS = 5000
    private const val MAX_RESULTS = 5

    /** 单条搜索结果：标题 / 链接 / 摘要。 */
    private data class Hit(val title: String, val link: String, val snippet: String)

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext "请输入要搜索的内容"
        searchBingRss(q)?.let { return@withContext it }
        runCatching { searchBaidu(q) }.getOrNull()?.let { return@withContext it }
        runCatching { searchDuckDuckGo(q) }.getOrNull()?.let { return@withContext it }
        "❌ 联网搜索失败：暂时无法获取结果，请稍后重试"
    }

    /** Bing RSS：依次尝试多个域名（国内可达性从高到低）。 */
    private fun searchBingRss(query: String): String? {
        for (host in listOf("cn.bing.com", "www.bing.com", "bing.com", "global.bing.com")) {
            val url = "https://$host/search?q=" + URLEncoder.encode(query, "UTF-8") + "&format=rss"
            val xml = httpGet(url) ?: continue
            val hits = mutableListOf<Hit>()
            val itemRe = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            for (item in itemRe.findAll(xml)) {
                if (hits.size >= MAX_RESULTS) break
                val block = item.groupValues[1]
                val title = tag(block, "title") ?: continue
                val link = tag(block, "link") ?: continue
                hits.add(Hit(title, link, tag(block, "description") ?: ""))
            }
            if (hits.isNotEmpty()) return formatHits(query, hits)
        }
        return null
    }

    /** 从 RSS item 里取单标签内容（去 CDATA / HTML 实体）。 */
    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return stripHtml(m.groupValues[1]).trim().ifEmpty { null }
    }

    /** Baidu 兜底：解析常规网页结果页（国内网络无 key 可用）。 */
    private fun searchBaidu(query: String): String? {
        val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8") + "&rn=10&ie=utf-8"
        val html = httpGet(url) ?: return null
        val re = Regex(
            """<h3[^>]*>\s*<a[^>]*href="([^"]*)"[^>]*>(.*?)</a>\s*</h3>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val hits = mutableListOf<Hit>()
        for (m in re.findAll(html)) {
            if (hits.size >= MAX_RESULTS) break
            val link = m.groupValues[1].trim().substringBefore("&")
            if (link.isBlank() || link.startsWith("javascript:")) continue
            val title = stripHtml(m.groupValues[2]).trim()
            if (title.isEmpty()) continue
            // 摘要：取该结果块前 1600 字符，剥掉标签后摘取标题之后的文字
            val cleaned = stripHtml(html.substring(m.range.first).take(1600))
                .replace(Regex("""\s+"""), " ").trim()
            val idx = cleaned.indexOf(title)
            val snippet = (if (idx >= 0) cleaned.substring(idx + title.length) else cleaned)
                .trim().take(200)
            hits.add(Hit(title, link, snippet))
        }
        if (hits.isNotEmpty()) return formatHits(query, hits)
        return null
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
            return """
🔍 搜索：$query

[来源1] $abstractText

来源：$abstractUrl"""
        }
        val topics = json.optJSONArray("RelatedTopics")
        if (topics != null && topics.length() > 0) {
            val sb = StringBuilder("🔍 搜索：$query")
            var n = 0
            for (i in 0 until topics.length()) {
                if (n >= MAX_RESULTS) break
                val t = topics.optJSONObject(i)
                val text = t?.optString("Text") ?: continue
                val firstUrl = t?.optString("FirstURL") ?: continue
                if (text.isNotBlank()) {
                    sb.append("""

[来源${n + 1}] """).append(text.take(200))
                    if (firstUrl.isNotBlank()) sb.append("""

链接：""").append(firstUrl)
                    n++
                }
            }
            return if (n > 0) sb.toString() else null
        }
        return null
    }

    /** 统一序列化：带 [来源N] 编号，并对头部结果抓取正文供模型引用。 */
    private fun formatHits(query: String, hits: List<Hit>): String {
        val sb = StringBuilder("🔍 搜索：").append(query)
        hits.take(MAX_RESULTS).forEachIndexed { i, h ->
            sb.append("\n\n[来源").append(i + 1).append("] ").append(h.title)
            if (h.link.isNotBlank()) {
                sb.append("\n链接：").append(h.link)
                val body = fetchBody(h.link)
                if (body != null) sb.append("\n正文摘要：").append(body)
            }
            if (h.snippet.isNotBlank()) sb.append("\n摘要：").append(h.snippet.take(220))
        }
        return sb.toString()
    }

    /** 抓取正文：剥离 script/style/nav/footer 等噪声后取前 220 字（3s 超时，失败静默）。 */
    private fun fetchBody(url: String): String? {
        if (!url.startsWith("http")) return null
        val html = httpGet(url, 3_000) ?: return null
        if (html.length > 200_000) return null
        val text = stripHtml(
            html.replace(Regex("""(?is)<(script|style|noscript|nav|header|footer|aside)[^>]*>.*?</\1>"""), " ")
        )
        if (text.isBlank()) return null
        return text.replace(Regex("""\s+"""), " ").trim().take(220)
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = TIMEOUT_MS): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; xs-chat) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept", "application/rss+xml;q=0.9, application/xml;q=0.8, text/html;q=0.7, */*;q=0.5")
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream ?: return null
            // 服务端会按 UA 返回 gzip：不解压会读出乱码导致解析失败
            val input = if (conn.getHeaderField("Content-Encoding").orEmpty().contains("gzip", ignoreCase = true)) {
                GZIPInputStream(raw)
            } else raw
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var n: Int
            while (input.read(chunk).also { n = it } > 0) buf.write(chunk, 0, n)
            input.close()
            raw.close()
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
            .replace("&quot;", "\u0022").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ").trim()
    }

    /** 清洗模型误输出的工具调用标记（如 <|tool_call>、function:web_search 等），避免原始语法泄露到界面。 */
    fun stripToolMarkup(text: String): String {
        var t = text
        t = t.replace(Regex("<\\|tool_calls>[\\s\\S]*?(</\\|tool_calls>|\\z)", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<\\|tool_call>[\\s\\S]*?(</\\|tool_call>|\\z)", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<tool_call>[\\s\\S]*?(</tool_call>|\\z)", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<\\|?/?tool_calls?>[^>]*>", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<\\|?/?parameter[^>]*>", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("function\\s*:\\s*\\|?\\s*web_search", RegexOption.IGNORE_CASE), "")
        return t.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** 从模型输出中提取其联网搜索 query（模型原生工具调用语法）。 */
    fun extractToolSearchQuery(text: String): String? {
        val idx = text.indexOf("web_search", ignoreCase = true)
        if (idx < 0) return null
        val tail = text.substring(idx).take(600)
        Regex("\"query\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(tail)
            ?.let { return it.groupValues[1].trim().take(60).ifEmpty { null } }
        return Regex("[\"']([^\"']{2,80})", RegexOption.IGNORE_CASE).find(tail)
            ?.groupValues?.get(1)?.trim()?.take(60)?.takeIf { it.isNotBlank() }
    }
}
