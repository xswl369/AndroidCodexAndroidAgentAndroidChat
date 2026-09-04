package com.xs.chat.plugins

import android.util.Log
import com.xs.chat.data.SearchReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.zip.GZIPInputStream

/** 单一搜索引擎：命中返回条目列表，失败/无命中返回 null 交由下一个引擎兜底。 */
private typealias SearchEngine = (String) -> List<SearchReference>?

/**
 * 联网搜索插件（元宝同款）：
 * ① 数据源按网络环境依次尝试：Google → 搜狗 → Bing RSS → Baidu → DuckDuckGo（海外兜底）；
 * ② 返回结构化结果：完整文本喂给模型（带 [来源N] 编号），结构化引用条目供 UI 渲染参考资料卡片；
 * ③ 搜索模式由 ChatViewModel 统一控制：0 关闭 / 1 自动 / 2 总是开启。
 * ④ 内置直答与站点爬虫：农历/黄历/人民网/历史上的今天（百度百科接口）/球迷屋赛程（指定站点直接抓取）。
 */
object WebSearchPlugin {

    private const val TAG = "WebSearchPlugin"

    /** 联网搜索模式（元宝同款三态）。 */
    const val MODE_OFF = 0
    const val MODE_AUTO = 1
    const val MODE_ALWAYS = 2

    private const val TIMEOUT_MS = 5000
    /** Google 直连国内常不通（超时即自动降级到后续引擎），给更短超时避免拖慢整轮搜索。 */
    private const val GOOGLE_TIMEOUT_MS = 4000
    /** 条数不再固定：引擎最多取 50 条（防御性上限），全部进入参考资料与模型上下文。 */
    private const val MAX_RESULTS = 50
    /** 前 15 条优先抓正文（并发执行），其余保留标题+摘要；摘要/正文字符放宽到接近全文。 */
    private const val BODY_FETCH_LIMIT = 15
    private const val SNIPPET_LIMIT = 300
    private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; xs-chat) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 一次完整搜索结果：text 供模型分析，refs 供 UI 渲染参考资料。 */
    data class SearchOutcome(val text: String, val refs: List<SearchReference>)

    suspend fun search(query: String): SearchOutcome? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        builtInQuery(q)?.let { return@withContext it }
        // 查询变体：先精简后原文（整句盲搜命中率低，精简版优先）
        val variants = queryVariants(q)
        val engines = listOf(
            "Google" to ::searchGoogle, "Sogou" to ::searchSogou, "Bing" to ::searchBingRss,
            "Baidu" to ::searchBaidu, "DuckDuckGo" to ::searchDuckDuckGo
        )
        val failures = mutableListOf<String>()
        for ((name, engine) in engines) {
            for (v in variants) {
                val refs = runCatching { engine(v) }.getOrNull()?.let { dedupe(it) }
                if (!refs.isNullOrEmpty()) return@withContext SearchOutcome(format(v, refs), refs)
            }
            failures.add(name)
        }
        Log.w(TAG, "all engines failed: ${failures.joinToString(" / ")} query=$q")
        null
    }

    /** 跨引擎/页内去重：同 URL 只保留第一条（优先带摘要的）。 */
    private fun dedupe(refs: List<SearchReference>): List<SearchReference> {
        val seen = HashSet<String>()
        return refs.filter { r ->
            val key = r.url.lowercase(Locale.ROOT).substringBefore('#').removeSuffix("/")
            key.isBlank() || seen.add(key)
        }
    }

    /** 查询变体：先精简（去客套/填充/汇总类词），再回退原文，最多 2 条。 */
    private fun queryVariants(raw: String): List<String> {
        val clean = stripQueryNoise(raw)
        return listOf(clean, raw).distinct().filter { it.isNotBlank() }
    }

    /** 提炼搜索关键词：去掉“帮我/请/汇总下/整理一下”等口语填充词，避免整句盲搜。 */
    private fun stripQueryNoise(q: String): String {
        var t = q.trim()
        // 去掉首部客套与动词引导
        t = Regex("^(?:请帮我|帮忙|帮我?|麻烦|请|请问|咱们|给我)?(?:联网搜索|搜索一下|搜索|搜一下|搜|查找一下|查找|查一下|查查|查|帮我搜|帮我查|看看|看一下|看)[:：,，\\s]*").replaceFirst(t, "")
        // 去掉尾部收尾与汇总/总结类词
        t = t.replace(Regex("(汇总一下|汇总|总结一下|总结|归纳一下|归纳|整理一下|整理|收集一下|介绍一下|汇报一下|说说|一下|好不好|谢谢|吧|呢|呀|嘛|哦|啊|的)$"), "")
        // 去掉句中口语填充词
        t = t.replace(Regex("(汇总一下|汇总下|总结一下|总结下|归纳一下|整理一下|帮我|请|麻烦|顺便|给我)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return t.ifBlank { q.trim() }
    }

    /** Google（优先尝试，国内直连常不通会超时自动回退）：解析 /url?q= 真实链接 + h3 标题。 */
    private fun searchGoogle(query: String): List<SearchReference>? {
        val url = "https://www.google.com/search?q=" + encode(query) + "&num=20&hl=zh-CN"
        val html = httpGet(url, GOOGLE_TIMEOUT_MS, DESKTOP_UA) ?: return null
        if (html.contains("unusual traffic") || html.contains("not a robot") ||
            html.contains("captcha") || html.contains("请输入验证码") || !html.contains("<h3")
        ) return null
        val hits = mutableListOf<SearchReference>()
        val seenHref = HashSet<String>()
        val blockRe = Regex("<a[^>]+href=\"/url\\?q=([^&\"']+)[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        for (m in blockRe.findAll(html)) {
            if (hits.size >= MAX_RESULTS) break
            val target = m.groupValues[1].replace("&amp;", "&")
            if (!seenHref.add(target)) continue
            val title = stripHtml(m.groupValues[2]).trim()
            if (!target.startsWith("https://") && !target.startsWith("http://")) continue
            if (title.isEmpty() || title.contains("Google 翻译")) continue
            hits.add(SearchReference(title.take(80), target))
        }
        return hits.ifEmpty { null }
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
            val a = Regex("<a[^>]*class=\"?resultLink\"?[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
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
        val url = "https://www.baidu.com/s?wd=" + encode(query) + "&rn=50&ie=utf-8"
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

    /** 序列化为喂给模型的编号文本：编号顺序与 refs 一致（UI 参考资料编号一一对应），全部条目录入。 */
    private suspend fun format(query: String, refs: List<SearchReference>): String = coroutineScope {
        // 正文抓取：前 BODY_FETCH_LIMIT 条按 4 条一组并发（单条 3s 超时，失败静默）
        val bodies: Map<String, String> = refs
            .take(BODY_FETCH_LIMIT)
            .chunked(4)
            .flatMap { batch ->
                batch.map { ref -> async(Dispatchers.IO) { ref.url to (fetchBody(ref.url) ?: "") } }
                    .map { it.await() }
            }
            .filter { it.second.isNotBlank() }
            .toMap()
        val sb = StringBuilder("🔍 搜索：").append(query)
        refs.forEachIndexed { i, r ->
            sb.append("\n\n[来源").append(i + 1).append("] ").append(r.title)
            if (r.url.isNotBlank()) {
                sb.append("\n链接：").append(r.url)
                bodies[r.url]?.let { sb.append("\n内容：").append(it) }
            }
            if (r.snippet.isNotBlank()) sb.append("\n摘要：").append(r.snippet.take(SNIPPET_LIMIT))
        }
        sb.toString()
    }

    /** 从 RSS item 里取单标签内容（去 CDATA / HTML 实体）。 */
    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return stripHtml(m.groupValues[1]).trim().ifEmpty { null }
    }

    /** 抓取正文：剥离 script/style/nav/footer 等噪声后取前 400 字（3s 超时，失败静默）。 */
    private fun fetchBody(url: String): String? {
        if (!url.startsWith("http")) return null
        // 搜索引擎跳转链接（百度 /link?url=、搜狗 /link）实为重定向页，抓正文无意义，直接跳过
        if (url.contains("/link?url=")) return null
        val html = httpGet(url, 3_000) ?: return null
        if (html.length > 200_000) return null
        val text = stripHtml(
            html.replace(Regex("""(?is)<(script|style|noscript|nav|header|footer|aside)[^>]*>.*?</\1>"""), " ")
        )
        if (text.isBlank()) return null
        return text.replace(Regex("""\s+"""), " ").trim().take(400)
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(urlStr: String, timeoutMs: Int = TIMEOUT_MS, ua: String = MOBILE_UA): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", ua)
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

    // ============ 内置查询（农历 / 黄历 / 人民日报，不依赖搜索引擎） ============

    private data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

    private val LUNAR_MONTH = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val LUNAR_DAY = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )
    private val GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    /** 农历数据表（solarlunar 同源，1900-2100 共 201 年）：低 4 位=闰月数，bit16=闰月 30 天，其余位=30 天月。 */
    private val LUNAR_INFO = intArrayOf(+        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a4d0, 0x0d150, 0x0f252,
        0x0d520
    )

    /** 内置查询路由：命中「农历/黄历/人民日报」直接出结果，不再走搜索引擎。 */
    private fun builtInQuery(query: String): SearchOutcome? {
        val q = query.trim()
        val lower = q.lowercase(Locale.ROOT)
        // 球迷屋：指定站点直接爬赛程，不依赖搜索引擎
        if (lower.contains("球迷屋") || lower.contains("qiumiwu")) {
            return qiumiwuOutcome(q)
        }
        // 历史上的今天：内置百度百科数据（优先于日期快答，防「9月4日历史上的今天」被误吞）
        val hasDate = Regex("""\d{1,2}\s*月\s*\d{1,2}""").containsMatchIn(q)
        if (lower.contains("历史上的今天") ||
            lower.contains("大事记") ||
            (lower.contains("历史") && (lower.contains("今天") || hasDate))
        ) {
            return historyOutcome(q)
        }
        // 日期类问题（今天几月几号/今天星期几/现在日期）：内置直接回答，绝不走搜索引擎
        if (lower.length <= 16 && !lower.contains("历史") && !lower.contains("大事记") && (lower.contains("今天") || lower.contains("日期") ||
            lower.contains("几月几号") || lower.contains("几号") ||
            lower.contains("星期几") || lower.contains("周几") || lower.contains("礼拜几"))
        ) {
            return lunarOutcome("农历今天")
        }
        if (lower.contains("农历") || lower.contains("阴历") || lower.contains("旧历") || lower.contains("老历")) {
            return lunarOutcome(q)
        }
        if (lower.contains("黄历") || lower.contains("宜忌") || lower.contains("吉日")) {
            return huangliOutcome()
        }
        if (lower.contains("人民日报") || lower.contains("新闻") || lower.contains("热点") ||
            lower.contains("热搜") || lower.contains("热门") || lower.contains("头条") || lower.contains("要闻")
        ) {
            return peopleDailyOutcome()
        }
        return null
    }

    /** 历史上的今天：百度百科 eventsOnHistory 月接口（{month}.json），按日过滤取前 10 条，当月进程内缓存。 */
    private var historyCache: Pair<Int, JSONObject>? = null

    private fun historyOutcome(query: String): SearchOutcome? {
        val today = Calendar.getInstance()
        val target = parseSolarDate(query, today) ?: today
        val month = target.get(Calendar.MONTH) + 1
        val day = target.get(Calendar.DAY_OF_MONTH)
        val cached = historyCache
        val json = if (cached != null && cached.first == month) cached.second
        else runCatching {
            val text = httpGet(
                "https://baike.baidu.com/cms/home/eventsOnHistory/%02d.json".format(month),
                6_000, DESKTOP_UA
            ) ?: return null
            JSONObject(text).optJSONObject("%02d".format(month)) ?: return null
        }.getOrNull() ?: return null
        historyCache = month to json
        val dayKey = "%02d%02d".format(month, day)
        val list: JSONArray = json.optJSONArray(dayKey) ?: return null
        if (list.length() == 0) return null
        val sb = StringBuilder("📜 ").append(month).append("月").append(day).append("日·历史上的今天：")
        val refs = mutableListOf<SearchReference>()
        val n = minOf(list.length(), 10)
        for (i in 0 until n) {
            val o = list.optJSONObject(i) ?: continue
            val year = o.optString("year")
            val title = stripHtml(o.optString("title")).trim()
            if (title.isEmpty()) continue
            val desc = stripHtml(o.optString("desc")).replace(Regex("\\s+"), " ").trim().take(80)
            val link = o.optString("link").ifBlank { "https://baike.baidu.com/calendar/" }
            sb.append("\n").append(refs.size + 1).append(". ").append(year).append("年 · ").append(title)
            if (desc.isNotBlank()) sb.append("：").append(desc)
            refs.add(SearchReference("${year}年 $title", link))
        }
        if (refs.isEmpty()) return null
        sb.append("\n（数据来源：百度百科·历史上的今天）")
        return SearchOutcome(sb.toString(), refs)
    }

    /** 球迷屋：直接抓取赛程列表页（今日场次/比分/状态），用户明确指定该站时优先于搜索引擎。 */
    private fun qiumiwuOutcome(query: String): SearchOutcome? {
        val lower = query.lowercase(Locale.ROOT)
        val sportPath = when {
            lower.contains("篮球") || lower.contains("nba") || lower.contains("cba") -> "lanqiu"
            else -> "zuqiu"
        }
        val html = httpGet("https://www.qiumiwu.com/game/$sportPath", 8_000, DESKTOP_UA) ?: return null
        val dayBlock = Regex(
            "<details class=\"fixture__details fixture__details--today\"[^>]*>(.*?)</details>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return null
        val summary = Regex("<summary[^>]*>(.*?)</summary>", RegexOption.DOT_MATCHES_ALL)
            .find(dayBlock)?.groupValues?.get(1)?.let { stripHtml(it) }?.trim() ?: "今日赛程"
        val sb = StringBuilder(if (sportPath == "lanqiu") "🏀 球迷屋·" else "⚽ 球迷屋·").append(summary)
        val refs = mutableListOf<SearchReference>()
        // 每场比赛一个 fixture__list 外层块（内含 data/score/extra），用前瞻切块避免非贪婪提前收尾
        val rowRe = Regex(
            "<div class=\"fixture__list\"[^>]*>(.*?)(?=<div class=\"fixture__list\"|</section>)",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in rowRe.findAll(dayBlock)) {
            val row = m.groupValues[1]
            val league = Regex("fixture__list__league\">([^<]+)<").find(row)?.groupValues?.get(1)?.trim().orEmpty()
            val time = Regex("fixture__list__time\">\\s*([^<]+?)\\s*</span>").find(row)?.groupValues?.get(1)?.trim().orEmpty()
            val status = Regex("fixture__list__status\">([^<]+)<").find(row)?.groupValues?.get(1)?.trim().orEmpty()
            val teams = Regex("fixture__list__team\"><span>([^<]+)</span>")
                .findAll(row).map { it.groupValues[1].trim() }.toList()
            val scores = Regex("fixture__list__score__text\"[^>]*>\\s*([^<]+?)\\s*</span>")
                .findAll(row).map { it.groupValues[1].trim() }.toList()
            if (teams.size < 2) continue
            val link = Regex("href=\"(/game/stat-[^\"]+)\"").find(row)?.groupValues?.get(1)
            val score = if (scores.size >= 2) " ${scores[0]} - ${scores[1]}" else ""
            val line = "${league} ${time} ${teams[0]} vs ${teams[1]}$score（$status）".trim()
            sb.append("\n").append(refs.size + 1).append(". ").append(line)
            refs.add(SearchReference(line, "https://www.qiumiwu.com" + (link ?: "/game/$sportPath")))
        }
        return if (refs.isEmpty()) null else SearchOutcome(sb.toString(), refs)
    }

    /** 农历查询：支持「农历今天/农历2026年9月4日/9月4日」等，输出干支纪年+生肖+农历月日。 */
    private fun lunarOutcome(query: String): SearchOutcome? {
        val today = Calendar.getInstance()
        val target = parseSolarDate(query, today) ?: today
        val y = target.get(Calendar.YEAR)
        if (y !in 1900..2100) return null
        val lunar = solarToLunar(y, target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH)) ?: return null
        val week = arrayOf("周六", "周日", "周一", "周二", "周三", "周四", "周五")[target.get(Calendar.DAY_OF_WEEK)]
        val ganIdx = ((y - 4) % 10 + 10) % 10
        val zhiIdx = ((y - 4) % 12 + 12) % 12
        val monthCn = (if (lunar.leap) "闰" else "") + LUNAR_MONTH[lunar.month - 1]
        val text = buildString {
            append("📅 ").append(y).append("年")
                .append(target.get(Calendar.MONTH) + 1).append("月")
                .append(target.get(Calendar.DAY_OF_MONTH)).append("日（").append(week).append("）")
            append("\n农历：").append(GAN[ganIdx]).append(ZHI[zhiIdx])
                .append("年（属").append(ZODIAC[zhiIdx]).append("） ")
                .append(monthCn).append("月").append(LUNAR_DAY[lunar.day - 1])
        }
        return SearchOutcome(text, emptyList())
    }

    /** 解析查询中的公历日期：2026年9月4日 / 9月4日，失败返回 null（按今天）。 */
    private fun parseSolarDate(text: String, today: Calendar): Calendar? {
        val full = Regex("""(20\d{2})\s*年?[-/.]?\s*(\d{1,2})\s*月?[-/.]?\s*(\d{1,2})\s*日?""").find(text)
        if (full != null) {
            val c = Calendar.getInstance()
            c.clear()
            c.set(full.groupValues[1].toInt(), full.groupValues[2].toInt() - 1, full.groupValues[3].toInt())
            return c
        }
        val md = Regex("""(\d{1,2})\s*月\s*(\d{1,2})[日号]?""").find(text)
        if (md != null) {
            val c = Calendar.getInstance()
            c.clear()
            c.set(today.get(Calendar.YEAR), md.groupValues[1].toInt() - 1, md.groupValues[2].toInt())
            return c
        }
        return null
    }

    /** 公历 → 农历（1900-2100），返回农历年/月/日及是否闰月。 */
    private fun solarToLunar(y: Int, m: Int, d: Int): LunarDate? {
        val epoch = Calendar.getInstance().apply { clear(); set(1900, 0, 31, 0, 0, 0) }
        val target = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 0, 0, 0) }
        val millis = target.timeInMillis - epoch.timeInMillis
        if (millis < 0) return null
        var offset = (millis / 86_400_000L).toInt()
        var year = 1900
        while (true) {
            val size = lunarYearDays(year)
            if (offset < size) break
            offset -= size
            year++
            if (year > 2100) return null
        }
        val leap = leapMonthOf(year)
        val months = ArrayList<Int>(13)
        for (i in 1..12) {
            months.add(monthDaysOf(year, i))
            if (leap == i) months.add(leapDaysOf(year))
        }
        var mi = 0
        while (mi < months.size - 1 && offset >= months[mi]) {
            offset -= months[mi]
            mi++
        }
        return LunarDate(year, mi + 1, offset + 1, leap > 0 && mi == leap)
    }

    private fun leapMonthOf(year: Int): Int = LUNAR_INFO[year - 1900] and 0xF

    private fun leapDaysOf(year: Int): Int =
        if (leapMonthOf(year) == 0) 0 else if (LUNAR_INFO[year - 1900] and 0x10000 != 0) 30 else 29

    private fun monthDaysOf(year: Int, month: Int): Int =
        if (LUNAR_INFO[year - 1900] and (0x10000 shr month) != 0) 30 else 29

    private fun lunarYearDays(year: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if (LUNAR_INFO[year - 1900] and i != 0) sum += 1
            i = i shr 1
        }
        return sum + leapDaysOf(year)
    }

    /** 黄历（今日宜忌）：抓取 huangli.com 在线数据，失败返回 null。 */
    private fun huangliOutcome(): SearchOutcome? {
        // 移动 UA 会被重定向成精简版页面（缺宜/忌/干支），必须用桌面版拿完整版
        val html = httpGet("https://www.huangli.com/", 6_000, DESKTOP_UA) ?: return null
        val gz = Regex("""class="gz">([^<]+)</span>""").findAll(html).map { it.groupValues[1] }.toList()
        val zodiac = Regex("""class="zodiac">([^<]+)</span>""").findAll(html).map { it.groupValues[1] }.toList()
        val nayin = Regex("""class="nayin">([^<]+)</span>""").findAll(html).map { it.groupValues[1] }.toList()
        val yi = Regex("""href="/hdjr/yi/[^"]{1,40}">([^<]+)</a>""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val ji = Regex("""href="/hdjr/ji/[^"]{1,40}">([^<]+)</a>""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        if (yi.isEmpty() && ji.isEmpty()) return null
        val dateText = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日""").find(html)?.let { m ->
            "${m.groupValues[1]}年${m.groupValues[2]}月${m.groupValues[3]}日"
        } ?: "今日"
        val sb = StringBuilder("📅 黄历（").append(dateText).append("）")
        if (gz.size >= 3) {
            sb.append("\n干支：")
            for (i in 0..2) {
                if (i > 0) sb.append("  ")
                sb.append(gz[i])
                if (i < zodiac.size) sb.append("（").append(zodiac[i]).append("）")
                if (i < nayin.size) sb.append("·").append(nayin[i])
                sb.append(if (i == 0) "年" else if (i == 1) "月" else "日")
            }
        }
        if (yi.isNotEmpty()) sb.append("\n✅ 宜：").append(yi.joinToString("、"))
        if (ji.isNotEmpty()) sb.append("\n❌ 忌：").append(ji.joinToString("、"))
        return SearchOutcome(
            sb.toString(),
            listOf(SearchReference("黄历网", "https://www.huangli.com/", "数据来源：huangli.com"))
        )
    }

    /** 人民日报新闻：人民网 RSS 多频道（时政/国际/社会/财经/法治），最多 8 条。 */
    private fun peopleDailyOutcome(): SearchOutcome? {
        val channels = listOf(
            "politics" to "时政", "world" to "国际", "society" to "社会",
            "finance" to "财经", "legal" to "法治"
        )
        val refs = mutableListOf<SearchReference>()
        for ((code, name) in channels) {
            val xml = httpGet("http://www.people.com.cn/rss/$code.xml") ?: continue
            var per = 0
            for (item in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                if (per >= 2 || refs.size >= 10) break
                val title = tag(item.groupValues[1], "title") ?: continue
                val link = tag(item.groupValues[1], "link") ?: continue
                val desc = tag(item.groupValues[1], "description").orEmpty().take(130)
                refs.add(SearchReference("[$name] $title", link, desc))
                per++
            }
            if (refs.size >= 10) break
        }
        if (refs.isEmpty()) return null
        val sb = StringBuilder("📰 人民日报·最新新闻：")
        refs.forEachIndexed { i, r ->
            sb.append("\n").append(i + 1).append(". ").append(r.title)
            if (r.snippet.isNotBlank()) sb.append("：").append(r.snippet)
        }
        return SearchOutcome(sb.toString(), refs)
    }

    /** 自检（验证内置查询，供自动化/ADB）：check news / check lunar / check huangli / check search <关键词> */
    suspend fun selfCheck(kind: String): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val k = kind.lowercase(Locale.ROOT)
        val result = when {
            k in setOf("news", "rd", "people", "热点") -> {
                val r = peopleDailyOutcome()
                if (r == null) "❌ news：人民网 RSS 获取失败（网络不可达或超时）"
                else "✅ 人民网新闻 ${r.refs.size} 条\n" + r.text
            }
            k in setOf("date", "today") -> {
                val r = lunarOutcome("农历今天")
                if (r == null) "❌ date 计算失败"
                else "✅ " + r.text
            }
            k in setOf("lunar", "农历") -> {
                val r = lunarOutcome("农历今天")
                if (r == null) "❌ lunar 农历计算失败"
                else "✅ " + r.text
            }
            k in setOf("huangli", "黄历") -> {
                val r = huangliOutcome()
                if (r == null) "❌ 黄历在线抓取失败（网络不可达或超时）"
                else "✅ " + r.text
            }
            k.startsWith("search") -> {
                val q = k.removePrefix("search").trim()
                if (q.isEmpty()) "用法：check search <关键词>"
                else {
                    val r = search(q)
                    if (r == null) "❌ 搜索无结果（全部引擎失败）"
                    else "✅ 搜索命中 ${r.refs.size} 条\n" + r.text.take(400)
                }
            }
            else -> "用法：check news / check lunar / check huangli / check search <关键词>"
        }
        result + "\n⏱ ${System.currentTimeMillis() - t0}ms"
    }
}
