package com.xs.chat.plugins

import com.xs.chat.data.SearchReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/** 单一搜索引擎：命中返回条目列表，失败/无命中返回 null 交由下一个引擎兜底。 */
private typealias SearchEngine = (String) -> List<SearchReference>?

/**
 * 联网搜索插件（元宝同款）：
 * ① 数据源按国内网络环境依次尝试：搜狗 → Bing RSS → Baidu → DuckDuckGo（海外兜底）；
 * ② 返回结构化结果：完整文本喂给模型（带 [来源N] 编号），结构化引用条目供 UI 渲染参考资料卡片；
 * ③ 搜索模式由 ChatViewModel 统一控制：0 关闭 / 1 自动 / 2 总是开启。
 */
object WebSearchPlugin {

    /** 联网搜索模式（元宝同款三态）。 */
    const val MODE_OFF = 0
    const val MODE_AUTO = 1
    const val MODE_ALWAYS = 2

    private const val TIMEOUT_MS = 5000
    private const val MAX_RESULTS = 5
    /** 只对前 2 条结果抓取正文（单条 3s 超时），避免串行抓取拖慢整体搜索。 */
    private const val BODY_FETCH_LIMIT = 2

    /** 一次完整搜索结果：text 供模型分析，refs 供 UI 渲染参考资料。 */
    data class SearchOutcome(val text: String, val refs: List<SearchReference>)

    suspend fun search(query: String): SearchOutcome? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        val engines = listOf<SearchEngine>(::searchSogou, ::searchBingRss, ::searchBaidu, ::searchDuckDuckGo)
        for (engine in engines) {
            val refs = runCatching { engine(q) }.getOrNull()
            if (!refs.isNullOrEmpty()) return@withContext SearchOutcome(format(query, refs), refs)
        }
        null
    }

    /** 搜狗（元宝同款搜索源）：解析移动版 vr 结果卡片，真实链接藏在 /link 重定向参数中。 */
    private fun searchSogou(query: String): List<SearchReference>? {
        val url = "https://www.sogou.com/web?query=" + encode(query)
        val html = httpGet(url) ?: return null
        if (html.contains("antispider") || html.contains("安全验证") || html.contains("请输入验证码")) return null
        val hits = mutableListOf<SearchReference>()
        val h3Re = Regex("<h3[^>]*>((?:(?!</h3>).)*?)</h3>", RegexOption.DOT_MATCHES_ALL)
        for (m in h3Re.findAll(html)) {
            if (hits.size >= MAX_RESULTS) break
            val block = m.groupValues[1]
            val a = Regex(""""<a[^>]*class="?resultLink"?[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(block) ?: continue
            val target = sogouTarget(a.groupValues[1]) ?: continue
            val title = stripHtml(a.groupValues[2]).trim()
            if (title.isEmpty() || title.contains("大家还在搜")) continue
            hits.add(SearchReference(title, target))
        }
        return hits.ifEmpty { null }
    }

    /** 从搜狗 /link 重定向链接中解出真实目标地址。 */
    private fun sogouTarget(href: String): String? {
        val real = Regex("""url=([^&"'\\s]+)""").find(href)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(real, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** Bing RSS：依次尝试多个域名（国内可达性从高到低），最稳。 */
    private fun searchBingRss(query: String): List<SearchReference>? {
        for (host in listOf("cn.bing.com", "www.bing.com", "bing.com", "global.bing.com")) {
            val url = "https://$host/search?q=" + encode(query) + "&format=rss"
            val xml = httpGet(url) ?: continue
            val hits = mutableListOf<SearchReference>()
            val itemRe = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            for (item in itemRe.findAll(xml)) {
                if (hits.size >= MAX_RESULTS) break
                val block = item.groupValues[1]
                val title = tag(block, "title") ?: continue
                val link = tag(block, "link") ?: continue
                hits.add(SearchReference(title, link, tag(block, "description") ?: ""))
            }
            if (hits.isNotEmpty()) return hits
        }
        return null
    }

    /** Baidu 兜底：解析常规网页结果页（国内网络无 key 可用）。 */
    private fun searchBaidu(query: String): List<SearchReference>? {
        val url = "https://www.baidu.com/s?wd=" + encode(query) + "&rn=10&ie=utf-8"
        val html = httpGet(url) ?: return null
        val re = Regex(
            """<h3[^>]*>\s*<a[^>]*href="([^"]*)"[^>]*>(.*?)</a>\s*</h3>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val hits = mutableListOf<SearchReference>()
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
            hits.add(SearchReference(title, link, snippet))
        }
        return hits.ifEmpty { null }
    }

    /** DuckDuckGo Instant Answer API：对海外网络兜底。 */
    private fun searchDuckDuckGo(query: String): List<SearchReference>? {
        val url = "https://api.duckduckgo.com/?q=" + encode(query) +
            "&format=json&no_html=1&skip_disambig=1"
        val body = httpGet(url) ?: return null
        val json = JSONObject(body)
        val hits = mutableListOf<SearchReference>()
        val abstractText = json.optString("AbstractText").trim()
        val abstractUrl = json.optString("AbstractURL").trim()
        if (abstractText.isNotEmpty() && abstractUrl.isNotEmpty()) {
            hits.add(SearchReference(
                json.optString("Heading").trim().ifBlank { "摘要" },
                abstractUrl,
                abstractText
            ))
        }
        val topics = json.optJSONArray("RelatedTopics")
        if (topics != null) {
            for (i in 0 until topics.length()) {
                if (hits.size >= MAX_RESULTS) break
                val o = topics.optJSONObject(i) ?: continue
                val text = o.optString("Text").trim()
                val firstUrl = o.optString("FirstURL").trim()
                if (text.isNotEmpty() && firstUrl.isNotEmpty()) {
                    hits.add(SearchReference(text.substringBefore(" '").take(80), firstUrl, text))
                }
            }
        }
        return hits.ifEmpty { null }
    }

    /** 序列化为喂给模型的编号文本：编号顺序与 refs 一致（UI 参考资料编号一一对应）。 */
    private fun format(query: String, refs: List<SearchReference>): String {
        val sb = StringBuilder("🔍 搜索：").append(query)
        refs.take(MAX_RESULTS).forEachIndexed { i, r ->
            sb.append("\n\n[来源").append(i + 1).append("] ").append(r.title)
            if (r.url.isNotBlank()) {
                sb.append("\n链接：").append(r.url)
                if (i < BODY_FETCH_LIMIT) fetchBody(r.url)?.let { sb.append("\n内容：").append(it) }
            }
            if (r.snippet.isNotBlank()) sb.append("\n摘要：").append(r.snippet.take(220))
        }
        return sb.toString()
    }

    /** 从 RSS item 里取单标签内容（去 CDATA / HTML 实体）。 */
    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return stripHtml(m.groupValues[1]).trim().ifEmpty { null }
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

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

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
            // 服务端按 UA 返回 gzip：不解压会读出乱码导致解析失败
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

    /** 清洗模型误输出的工具调用标记（如 <|tool_call>、function:web_search 等），避免原始语法泄漏到界面。 */
    fun stripToolMarkup(text: String): String {
        var t = text
        t = t.replace(Regex("(?:<\\|tool_calls>|<tool_call>)[\\s\\S]*?(?:</tool_call>|</tool_calls>|\\z)", RegexOption.IGNORE_CASE), "")
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
