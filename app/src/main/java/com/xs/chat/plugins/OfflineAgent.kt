package com.xs.chat.plugins

import android.content.Context
import android.content.SharedPreferences
import com.wirelessdebug.service.ShizukuDevice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 离线智能识别引擎（内置）——四大能力：
 * 1. 自动学习：成功执行的「指令→动作序列」持久化为技能，相似指令自动回放；
 *    成功点击的元素坐标按应用记忆，下次免滚动直接命中。
 * 2. 自动联网决策：规则解析失败或离线执行失败时，由调用方自动转已配置 LLM 联网决策兜底。
 * 3. 智能操作：弹窗预检、加载等待、记忆坐标优先、多方向滚动查找、失败自动换策略。
 * 4. 智能读屏：节点树结构化解析（应用/页面标题/按钮/输入框/列表项）+ 模糊匹配（忽略空白标点）。
 * 读屏全程不截图，基于无障碍/无线调试节点树。
 */
object OfflineAgent {

    private const val MAX_STEPS = 14
    private const val WAIT_MS = 900L
    private const val SWIPE_WAIT_MS = 1300L
    private const val KEY_SKILLS = "skills"
    private const val KEY_MEM = "memories"
    private const val MAX_SKILLS = 50
    private const val MAX_MEMORIES = 100
    private const val MATCH_MIN = 0.22
    private const val MATCH_MIN_TOKENS = 5

    private val SEARCH_HINTS = listOf("搜索", "搜索框", "搜索微信", "搜索商品", "Search", "search", "查找", "输入关键字")
    private val POPUP_CLOSE = listOf("我知道了", "以后再说", "跳过", "暂不", "拒绝", "不允许", "关闭", "取消")
    private val LOADING_WORDS = listOf("加载中", "正在加载", "请稍候", "拼命加载", "玩命加载", "努力加载")

    // ---------- 入口 ----------

    /** 尝试执行；返回 null 表示无法解析（由调用方转联网决策）。离线执行失败返回 "❌ ..."。 */
    fun run(context: Context, instruction: String, onProgress: ((String) -> Unit)?): String? {
        // 1) 自动学习：相似指令技能回放（失败自动遗忘并重新解析）
        replaySkill(context, instruction, onProgress)?.let { return it }
        // 2) 规则解析 → 智能执行
        val plan = parse(context, instruction) ?: return null
        onProgress?.invoke("🤖 离线智能识别，开始执行：$instruction")
        val result = execute(context, plan.steps, onProgress)
        // 3) 自动学习：成功指令沉淀为技能
        if (result.startsWith("✅")) recordSkill(context, instruction, plan.steps)
        return result
    }

    /** 智能读屏：返回结构化中文摘要（应用、页面标题、可点按钮、输入框、其他文本）。 */
    fun smartDump(): String {
        val dump = screenDumpRetry()
        if (dump.startsWith("screen dump failed") || dump.startsWith("screen parse failed")) return dump
        val items = readItems(dump)
        val (w, h) = screenSizeOf(dump)
        val sb = StringBuilder()
        appNameOf(dump)?.let { sb.appendLine("应用：$it") }
        if (w > 0 && h > 0) sb.appendLine("屏幕：${w}x$h")
        items.firstOrNull { h == 0 || it.y < (h * 0.12).toInt() }?.let { sb.appendLine("页面标题：${it.label}") }
        val buttons = items.filter { it.clickable && !it.flags.contains("editable") }
        if (buttons.isNotEmpty()) {
            sb.appendLine("【可点击按钮】")
            buttons.take(40).forEach { sb.appendLine("- ${it.label} 中心=(${it.x},${it.y})") }
        }
        val inputs = items.filter { it.flags.contains("editable") }
        if (inputs.isNotEmpty()) {
            sb.appendLine("【输入框】")
            inputs.take(10).forEach { sb.appendLine("- ${it.label} 中心=(${it.x},${it.y})") }
        }
        val others = items.filter { !it.clickable && !it.flags.contains("editable") }
        if (others.isNotEmpty()) {
            sb.appendLine("【其他文本】")
            others.take(30).forEach { sb.appendLine("- ${it.label}") }
        }
        return sb.toString().trim()
    }

    // ---------- 意图解析（自然语言 → 动作计划） ----------

    private sealed class Step {
        object Wait : Step()
        object Enter : Step()
        object SwipeUp : Step()
        object SwipeDown : Step()
        object TapFirst : Step()
        object ClosePopups : Step()
        data class Open(val app: String) : Step()
        data class TapText(val text: String) : Step()
        data class Type(val text: String) : Step()
        data class Search(val keyword: String, val tapFirst: Boolean) : Step()
        data class FindTap(val text: String) : Step()
    }

    private data class Plan(val steps: List<Step>)

    private fun parse(context: Context, text: String): Plan? {
        val t = text.trim()
        // 给张三发微信：内容 / 向李四发送消息
        Regex("""^(给|向)\s*(.+?)\s*(?:发|发送|发个|发条)\s*(?:微信|消息|信息)?\s*[:：,，]?\s*(.+)$""").find(t)?.let {
            val contact = it.groupValues[2].trim()
            val msg = it.groupValues[3].trim().trim('"', '\u201C', '\u201D', '「', '」')
            if (msg.isNotBlank() && contact.isNotBlank()) {
                return Plan(listOf(
                    Step.Open("微信"), Step.Search(contact, true), Step.Type(msg), Step.TapText("发送")
                ))
            }
        }
        // 打开X搜索Y（并点进第一个...）
        Regex("""^(打开|启动|开启|open|launch)\s*(.+?)\s*(?:搜索|搜一下|查找|查一下)\s*(.+)$""").find(t)?.let {
            val rest = it.groupValues[3].trim()
            val kw = rest
                .removeSuffix("并点进第一个视频").removeSuffix("并点进第一条视频")
                .removeSuffix("并点进第一个结果").removeSuffix("并点进第一个")
                .trim()
            val tapFirst = rest.contains("第一个") || rest.contains("第一条") || rest.contains("最新")
            return Plan(listOf(Step.Open(it.groupValues[2].trim()), Step.Search(kw, tapFirst)))
        }
        // 打开X并点进/进入第一个...（如「打开微信并打开第一个群聊」）
        Regex("""^(打开|启动|开启|open|launch)\s*(.+?)\s*(?:并|然后|再|接着)?\s*(?:点进|进入|打开|点开)\s*第一个\s*(.+)?$""").find(t)?.let {
            return Plan(listOf(Step.Open(it.groupValues[2].trim()), Step.Wait, Step.TapFirst))
        }
        // 点第一个结果 / 点开第一个
        if (t.contains("第一个") && (t.startsWith("点") || t.startsWith("点开") || t.contains("结果"))) {
            return Plan(listOf(Step.TapFirst))
        }
        // 打开X并找到/点击Y
        Regex("""^(打开|启动|开启|open|launch)\s*(.+?)\s*(?:并|然后|再|接着)?\s*(?:找到|查找|定位|点击|点一下|点开|进入)\s*(.+)$""").find(t)?.let {
            return Plan(listOf(Step.Open(it.groupValues[2].trim()), Step.FindTap(it.groupValues[3].trim())))
        }
        // 找到/点击屏幕上的Y（滚动查找）
        Regex("""^(?:找|找到|查找|点击|点一下|点开|进入)\s*(?:屏幕上的|页面上的)?\s*(.+)$""").find(t)?.let {
            return Plan(listOf(Step.FindTap(it.groupValues[1].trim())))
        }
        // 往下翻 / 下一页
        if (Regex("""^(?:往下翻|向下翻|下一页|再往下|往下滑|继续翻)""").containsMatchIn(t)) {
            return Plan(listOf(Step.SwipeUp))
        }
        // 关闭弹窗/广告/跳过广告
        if (t.contains("关闭弹窗") || t.contains("关掉弹窗") || t.contains("关闭广告") || t.contains("关掉广告") || t.contains("跳过广告")) {
            return Plan(listOf(Step.ClosePopups))
        }
        // —— 复杂指令索引兜底：无需以「打开」开头，也能从复合句中抽出应用名执行 ——
        // 例：「在抖音搜索华为手机并点进第一个视频」「去B站找一下我的关注」「打开微信看看」
        val appName = AppIndexPlugin.extractAppName(context, t)
        if (appName != null) {
            val after = t.substringAfterLast(appName)
                .trimStart('的', '中', '里', '内', '上', '，', ',', '、', '并', '再', '和').removePrefix("然后").removePrefix("还有")
            Regex("""^(?:搜索|搜一下|搜|查找|查一下|看看|看一下|了解一下)\s*(.+)$""").find(after)?.let { m ->
                val raw = m.groupValues[1].trim()
                val kw = raw
                    .removeSuffix("并点进第一个视频").removeSuffix("并点进第一条视频")
                    .removeSuffix("并点进第一个结果").removeSuffix("并点进第一个")
                    .removeSuffix("的第一个视频").removeSuffix("的第一个")
                    .trim()
                val tapFirst = raw.contains("第一个") || raw.contains("第一条") || raw.contains("最新")
                if (kw.isNotBlank()) return Plan(listOf(Step.Open(appName), Step.Search(kw, tapFirst)))
            }
            if (after.contains("第一个") || after.contains("第一条") ||
                after.startsWith("点开") || after.startsWith("点进") || after.contains("点进第一个")) {
                return Plan(listOf(Step.Open(appName), Step.Wait, Step.TapFirst))
            }
            Regex("""^(?:找到|查找|定位|点击|点一下|点开|进入|找一下|打开)\s*(.+)$""").find(after)?.let { m ->
                return Plan(listOf(Step.Open(appName), Step.FindTap(m.groupValues[1].trim())))
            }
            // 「打开应用看看」类：应用名后只剩语气助词/查看词，直接打开应用
            val trailing = after.trim('的', '。', '！', '!', '，', ',', '啊', '呀', '吧', '呢')
            if (trailing.isEmpty() || trailing == "看看" || trailing == "看下" || trailing == "看一下" || trailing == "一下") {
                return Plan(listOf(Step.Open(appName)))
            }
        }
        return null
    }

    // ---------- 执行器（读屏 → 动作 → 验证） ----------

    private fun execute(context: Context, steps: List<Step>, onProgress: ((String) -> Unit)?): String {
        var stepCount = 0
        var popupClosed = 0
        for (s in steps) {
            if (++stepCount > MAX_STEPS) return "❌ 离线执行步骤过多（$MAX_STEPS），已停止"
            // 智能操作：动作前弹窗预检（最多 2 次，防止死循环）
            if (popupClosed < 2 && s !is Step.ClosePopups) {
                if (closePopups(1, onProgress)) popupClosed++
            }
            when (s) {
                Step.Wait -> {
                    sleep(WAIT_MS)
                    onProgress?.invoke("⏳ 等待页面加载")
                }
                Step.Enter -> {
                    ShizukuDevice.keyEvent("66")
                    waitForStable(onProgress)
                }
                Step.SwipeUp -> {
                    swipeUp()
                    sleep(SWIPE_WAIT_MS)
                }
                Step.SwipeDown -> {
                    swipeDown()
                    sleep(SWIPE_WAIT_MS)
                }
                Step.TapFirst -> {
                    tapFirstResult(onProgress)?.let { return it }
                    waitForStable(onProgress)
                }
                Step.ClosePopups -> closePopups(4, onProgress)
                is Step.Open -> {
                    val pkg = DeviceControlPlugin.resolveApp(context, s.app)
                    if (pkg == null) return "❌ 未找到应用：${s.app}"
                    val r = ShizukuDevice.openApp(pkg)
                    if (r.startsWith("open failed")) return "❌ $r"
                    onProgress?.invoke("📱 $r")
                    sleep(WAIT_MS)
                    waitForStable(onProgress)
                }
                is Step.TapText -> {
                    tapTextSmart(context, s.text, onProgress)?.let { return it }
                }
                is Step.Type -> {
                    // 先点中输入框（读屏定位 editable 元素），再输入
                    val editable = readItems(screenDumpRetry()).firstOrNull { it.flags.contains("editable") }
                    if (editable != null) {
                        ShizukuDevice.tap(editable.x, editable.y)
                        sleep(400)
                    }
                    val r = DeviceControlPlugin.inputText(context, s.text)
                    if (isFail(r)) return "❌ 输入失败：$r"
                    onProgress?.invoke("⌨️ 已输入")
                    sleep(300)
                }
                is Step.Search -> {
                    doSearch(context, s.keyword, s.tapFirst, onProgress)?.let { return it }
                }
                is Step.FindTap -> {
                    findAndTap(context, s.text, 5, onProgress)?.let { return it }
                }
            }
        }
        return "✅ 离线智能执行完成"
    }

    /** 打开应用并搜索关键词：点搜索框 → 输入 → 回车 →（可选）点第一个结果。 */
    private fun doSearch(context: Context, keyword: String, tapFirst: Boolean, onProgress: ((String) -> Unit)?): String? {
        val hint = tapAny(context, SEARCH_HINTS, onProgress) ?: return "❌ 未找到搜索框（可手动点击搜索框后重试）"
        sleep(WAIT_MS)
        val input = DeviceControlPlugin.inputText(context, keyword)
        if (isFail(input)) return "❌ 输入失败：$input"
        onProgress?.invoke("⌨️ 输入关键词「$keyword」")
        sleep(400)
        ShizukuDevice.keyEvent("66")
        waitForStable(onProgress)
        if (tapFirst) {
            tapFirstResult(onProgress)?.let { return it }
        }
        return null
    }

    /** 智能点击文本：记忆坐标优先 → 模糊匹配直接点 → 多方向滚动查找。 */
    private fun tapTextSmart(context: Context, text: String, onProgress: ((String) -> Unit)?): String? {
        val pkg = currentApp()
        // 自动学习：元素坐标记忆优先（免滚动）
        memTap(context, pkg, text)?.let { (x, y) ->
            onProgress?.invoke("🧠 使用记忆坐标点击「$text」")
            ShizukuDevice.tap(x, y)
            sleep(300)
            return null
        }
        if (containsFuzzy(screenDumpRetry(), text)) {
            val r = ShizukuDevice.tapText(text)
            if (!r.startsWith("text not found")) {
                recordTapForLabel(context, pkg, text)
                onProgress?.invoke("👆 $r")
                return null
            }
        }
        return findAndTap(context, text, 5, onProgress)
    }

    /** 滚动查找并点击目标文本：先向上滑，找不到再向下翻；命中后记录坐标记忆。 */
    private fun findAndTap(context: Context, target: String, maxScrolls: Int, onProgress: ((String) -> Unit)?): String? {
        val pkg = currentApp()
        repeat(maxScrolls + 1) { i ->
            val dump = screenDumpRetry()
            if (containsFuzzy(dump, target)) {
                val r = ShizukuDevice.tapText(target)
                if (!r.startsWith("text not found")) {
                    recordTapForLabel(context, pkg, target)
                    onProgress?.invoke("👆 找到并点击「$target」")
                    waitForStable(onProgress)
                    return null
                }
            }
            if (i < maxScrolls) {
                onProgress?.invoke("📜 未找到「$target」，向上滑动寻找（${i + 1}/$maxScrolls）")
                swipeUp()
                sleep(SWIPE_WAIT_MS)
            }
        }
        if (maxScrolls > 0) {
            repeat(maxScrolls) { i ->
                val dump = screenDumpRetry()
                if (containsFuzzy(dump, target)) {
                    val r = ShizukuDevice.tapText(target)
                    if (!r.startsWith("text not found")) {
                        recordTapForLabel(context, pkg, target)
                        onProgress?.invoke("👆 找到并点击「$target」（向下翻 ${i + 1} 次）")
                        return null
                    }
                }
                swipeDown()
                sleep(SWIPE_WAIT_MS)
            }
        }
        return "❌ 上下滑动共 ${maxScrolls * 2} 次仍未找到「$target」"
    }

    /** 点击当前列表第一个可点结果（排除输入框与底部导航）。 */
    private fun tapFirstResult(onProgress: ((String) -> Unit)?): String? {
        val dump = screenDumpRetry()
        val items = readItems(dump)
        val h = screenSizeOf(dump).second
        val target = items.firstOrNull {
            it.clickable && !it.flags.contains("editable") && (h == 0 || it.y < h * 0.85)
        }
        if (target == null) return "❌ 未找到可点击的结果"
        onProgress?.invoke("👆 点击第一个结果「${target.label}」")
        ShizukuDevice.tap(target.x, target.y)
        sleep(WAIT_MS)
        return null
    }

    /** 逐个尝试点击候选文本，命中即返回命中的文本；成功后记录坐标记忆。 */
    private fun tapAny(context: Context, hints: List<String>, onProgress: ((String) -> Unit)?): String? {
        val pkg = currentApp()
        for (h in hints) {
            // 记忆坐标优先
            memTap(context, pkg, h)?.let { (x, y) ->
                onProgress?.invoke("🧠 使用记忆坐标点击「$h」")
                ShizukuDevice.tap(x, y)
                return h
            }
            val r = ShizukuDevice.tapText(h)
            if (!r.startsWith("text not found")) {
                recordTapForLabel(context, pkg, h)
                return h
            }
        }
        return null
    }

    /** 循环关闭弹窗/广告；返回是否关闭过。 */
    private fun closePopups(max: Int, onProgress: ((String) -> Unit)?): Boolean {
        var closed = false
        repeat(max) {
            val dump = screenDumpRetry()
            val hit = POPUP_CLOSE.firstOrNull { containsFuzzy(dump, it) } ?: return closed
            val r = ShizukuDevice.tapText(hit)
            if (r.startsWith("text not found")) return closed
            closed = true
            onProgress?.invoke("✖️ 关闭弹窗按钮「$hit」")
            sleep(WAIT_MS)
        }
        return closed
    }

    /** 智能等待：屏幕出现加载文案时轮询等待其消失（上限 5s）。 */
    private fun waitForStable(onProgress: ((String) -> Unit)?) {
        var waited = 0
        while (waited < 5) {
            val dump = screenDumpRetry()
            val loading = LOADING_WORDS.any { containsFuzzy(dump, it) }
            if (!loading) return
            onProgress?.invoke("⏳ 页面加载中，等待稳定…")
            sleep(1000)
            waited++
        }
    }

    // ---------- 自动学习：技能（指令→动作序列） ----------

    private fun recordSkill(context: Context, instruction: String, steps: List<Step>) {
        if (steps.isEmpty() || instruction.isBlank()) return
        runCatching {
            val sp = prefs(context)
            val skills = JSONArray(sp.getString(KEY_SKILLS, "[]"))
            var updated = false
            for (i in 0 until skills.length()) {
                val o = skills.getJSONObject(i)
                if (o.optString("instruction") == instruction) {
                    o.put("actions", stepsToJson(steps)).put("ts", System.currentTimeMillis())
                    updated = true
                    break
                }
            }
            if (!updated) {
                skills.put(JSONObject()
                    .put("instruction", instruction)
                    .put("actions", stepsToJson(steps))
                    .put("ts", System.currentTimeMillis()))
            }
            while (skills.length() > MAX_SKILLS) skills.remove(0)
            sp.edit().putString(KEY_SKILLS, skills.toString()).apply()
        }
    }

    private fun replaySkill(context: Context, instruction: String, onProgress: ((String) -> Unit)?): String? {
        val skill = matchSkill(context, instruction) ?: return null
        val steps = mutableListOf<Step>()
        val arr = skill.optJSONArray("actions") ?: return null
        for (i in 0 until arr.length()) {
            stepFromJson(arr.optJSONObject(i))?.let { steps += it }
        }
        if (steps.isEmpty()) return null
        onProgress?.invoke("🧠 命中学习技能（相似指令回放）：${skill.optString("instruction")}")
        val result = execute(context, steps, onProgress)
        if (result.startsWith("✅")) return result
        // 回放失败：技能已过期，遗忘并重新走规则解析
        forgetSkill(context, instruction)
        return null
    }

    private fun matchSkill(context: Context, instruction: String): JSONObject? {
        if (instruction.isBlank()) return null
        return runCatching {
            val skills = JSONArray(prefs(context).getString(KEY_SKILLS, "[]"))
            var best: JSONObject? = null
            var bestScore = 0.0
            for (i in 0 until skills.length()) {
                val o = skills.optJSONObject(i) ?: continue
                val score = similarity(instruction, o.optString("instruction"))
                if (score > bestScore) {
                    bestScore = score
                    best = o
                }
            }
            val tokens = tokensOf(instruction).size.coerceAtLeast(tokensOf(best?.optString("instruction") ?: "").size)
            best?.takeIf { bestScore >= MATCH_MIN && tokens >= MATCH_MIN_TOKENS }
        }.getOrNull()
    }

    private fun forgetSkill(context: Context, instruction: String) {
        runCatching {
            val sp = prefs(context)
            val skills = JSONArray(sp.getString(KEY_SKILLS, "[]"))
            val kept = JSONArray()
            for (i in 0 until skills.length()) {
                val o = skills.optJSONObject(i) ?: continue
                if (similarity(instruction, o.optString("instruction")) < MATCH_MIN) kept.put(o)
            }
            sp.edit().putString(KEY_SKILLS, kept.toString()).apply()
        }
    }

    private fun stepsToJson(steps: List<Step>): JSONArray {
        val arr = JSONArray()
        steps.forEach { s ->
            val o = JSONObject().put("t", stepType(s))
            when (s) {
                is Step.Open -> o.put("app", s.app)
                is Step.TapText -> o.put("text", s.text)
                is Step.Type -> o.put("text", s.text)
                is Step.Search -> o.put("kw", s.keyword).put("first", s.tapFirst)
                is Step.FindTap -> o.put("text", s.text)
                else -> {}
            }
            arr.put(o)
        }
        return arr
    }

    private fun stepType(s: Step): String = when (s) {
        Step.Wait -> "wait"; Step.Enter -> "enter"; Step.SwipeUp -> "swipe_up"
        Step.SwipeDown -> "swipe_down"; Step.TapFirst -> "tap_first"; Step.ClosePopups -> "close_popups"
        is Step.Open -> "open"; is Step.TapText -> "tap_text"; is Step.Type -> "type"
        is Step.Search -> "search"; is Step.FindTap -> "find_tap"
    }

    private fun stepFromJson(o: JSONObject?): Step? {
        if (o == null) return null
        return when (o.optString("t")) {
            "wait" -> Step.Wait
            "enter" -> Step.Enter
            "swipe_up" -> Step.SwipeUp
            "swipe_down" -> Step.SwipeDown
            "tap_first" -> Step.TapFirst
            "close_popups" -> Step.ClosePopups
            "open" -> Step.Open(o.optString("app"))
            "tap_text" -> Step.TapText(o.optString("text"))
            "type" -> Step.Type(o.optString("text"))
            "search" -> Step.Search(o.optString("kw"), o.optBoolean("first"))
            "find_tap" -> Step.FindTap(o.optString("text"))
            else -> null
        }
    }

    private fun similarity(a: String, b: String): Double {
        val ta = tokensOf(a)
        val tb = tokensOf(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size
        return inter.toDouble() / ta.union(tb).size
    }

    /** 中文按字符 bigram 切分（结构词保留），英文按单词。 */
    private fun tokensOf(s: String): Set<String> {
        val norm = s.lowercase(Locale.ROOT)
            .replace(Regex("[的了吗呢吧并然后接着帮我请一个条个篇视频内容商品结果]"), " ")
            .trim()
        val out = mutableSetOf<String>()
        for (w in norm.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val han = w.filter { it.code > 127 }
            if (han.isNotEmpty()) {
                for (i in 0 until han.length - 1) out.add(han.substring(i, i + 2))
            } else if (w.length >= 2) {
                out.add(w)
            }
        }
        return out
    }

    // ---------- 自动学习：元素坐标记忆 ----------

    private fun memTap(context: Context, pkg: String, label: String): Pair<Int, Int>? {
        if (pkg.isBlank() || pkg == "unknown" || label.isBlank()) return null
        return runCatching {
            val arr = JSONArray(prefs(context).getString(KEY_MEM, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("pkg") == pkg && o.optString("label") == label && o.optInt("hits") > 0) {
                    return@runCatching (o.optInt("x") to o.optInt("y"))
                }
            }
            null
        }.getOrNull()
    }

    private fun recordTap(context: Context, pkg: String, label: String, x: Int, y: Int) {
        if (pkg.isBlank() || pkg == "unknown" || label.isBlank() || x <= 0 || y <= 0) return
        runCatching {
            val sp = prefs(context)
            val arr = JSONArray(sp.getString(KEY_MEM, "[]"))
            var updated = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("pkg") == pkg && o.optString("label") == label) {
                    o.put("x", x).put("y", y).put("hits", o.optInt("hits") + 1)
                    updated = true
                    break
                }
            }
            if (!updated) {
                arr.put(JSONObject().put("pkg", pkg).put("label", label).put("x", x).put("y", y).put("hits", 1))
            }
            while (arr.length() > MAX_MEMORIES) arr.remove(0)
            sp.edit().putString(KEY_MEM, arr.toString()).apply()
        }
    }

    private fun recordTapForLabel(context: Context, pkg: String, label: String) {
        if (label.isBlank()) return
        val norm = normText(label)
        val item = readItems(screenDumpRetry()).firstOrNull { normText(it.label).contains(norm) }
        if (item != null) recordTap(context, pkg, label, item.x, item.y)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("offline_agent_learn", Context.MODE_PRIVATE)

    // ---------- 智能读屏：结构化解析 + 模糊匹配 ----------

    private data class Item(val label: String, val x: Int, val y: Int, val flags: String, val clickable: Boolean)

    private val LINE_REGEX = Regex("""^-\s+(?:"(.*?)"|\s*\[(.*?)\])(.*?)\s*bounds=\((\d+),(\d+),(\d+),(\d+)\)""")

    private fun readItems(dump: String): List<Item> {
        val items = mutableListOf<Item>()
        for (line in dump.lines()) {
            val m = LINE_REGEX.find(line) ?: continue
            val label = m.groupValues[1].ifBlank { m.groupValues[2] }
            val flags = m.groupValues[3].trim()
            val l = m.groupValues[4].toInt(); val t = m.groupValues[5].toInt()
            val r = m.groupValues[6].toInt(); val b = m.groupValues[7].toInt()
            items += Item(label, (l + r) / 2, (t + b) / 2, flags, flags.contains("clickable"))
        }
        return items
    }

    /** 模糊匹配：忽略空白与标点后做包含匹配（智能识别增强）。 */
    private fun containsFuzzy(dump: String, target: String): Boolean {
        val tn = normText(target)
        if (tn.isEmpty()) return false
        for (line in dump.lines()) {
            val m = LINE_REGEX.find(line) ?: continue
            val hay = normText(m.groupValues[1] + " " + m.groupValues[2])
            if (hay.contains(tn)) return true
        }
        return false
    }

    private fun normText(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}，。！？、；：·…—…“”‘’「」『』（）【】《》]+"), "")

    private fun screenSizeOf(dump: String): Pair<Int, Int> {
        for (line in dump.lines()) {
            if (line.startsWith("screen=")) {
                val p = line.removePrefix("screen=").split("x")
                if (p.size == 2) return (p[0].trim().toIntOrNull() ?: 0) to (p[1].trim().toIntOrNull() ?: 0)
            }
        }
        return 0 to 0
    }

    private fun appNameOf(dump: String): String? {
        for (line in dump.lines()) {
            if (line.startsWith("app=")) return line.removePrefix("app=").trim().ifBlank { null }
        }
        return null
    }

    private fun currentApp(): String = appNameOf(screenDumpRetry()) ?: ""

    /** 读屏自动重试：打开应用/窗口过渡期读屏失败时等待后重试（最多 3 次）。 */
    private fun screenDumpRetry(): String {
        var dump = ShizukuDevice.screenDump()
        if (!dump.startsWith("screen dump failed")) return dump
        repeat(2) {
            sleep(500)
            dump = ShizukuDevice.screenDump()
            if (!dump.startsWith("screen dump failed")) return dump
        }
        return dump
    }

    private fun swipeUp() {
        val (w, h) = screenSizeOf(screenDumpRetry())
        if (w <= 0 || h <= 0) return
        ShizukuDevice.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.2).toInt(), 300L)
    }

    private fun swipeDown() {
        val (w, h) = screenSizeOf(screenDumpRetry())
        if (w <= 0 || h <= 0) return
        ShizukuDevice.swipe(w / 2, (h * 0.2).toInt(), w / 2, (h * 0.8).toInt(), 300L)
    }

    private fun isFail(s: String): Boolean =
        s.contains("失败") || s.contains("failed") || s.contains("required") || s.contains("empty")

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
}




