package com.xs.chat.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wirelessdebug.PairState
import com.wirelessdebug.service.AccessibilityDevice
import com.wirelessdebug.service.AdbShellController
import com.wirelessdebug.service.RootController
import com.wirelessdebug.service.ScreenOcr
import com.wirelessdebug.service.ShizukuController
import com.wirelessdebug.service.ShizukuDevice
import com.xs.chat.data.AiModel
import com.xs.chat.data.OpenAiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.min

/**
 * 设备控制插件（类 Codex 电脑版）：先走正则快路径即时执行，
 * 无法解析的复杂指令（如「打开抖音搜索华为手机并点进第一个视频」）交给 LLM Agent
 * 以「读屏 → 决策 → 执行 → 验证」循环逐步完成，可操控已打开应用内部的一切界面元素。
 * 通道优先级：Root（uid 0 最高权限）→ 无线调试 shell → Shizuku → 无障碍（免 Root，与无线调试二选一）。
 * 读屏全部基于节点树/UI 摘要识别（无障碍节点树 / uiautomator dump / dumpsys），禁止截图查看。
 * 复杂指令优先由内置离线智能引擎（[OfflineAgent]）本地「读屏→决策→执行→验证」完成，无需联网；
 * 离线引擎无法解析的指令才交给 LLM Agent（在线增强）。
 */
object DeviceControlPlugin {

    /** 正则快路径无法完整处理的复合指令标记（如应用已定位但剩余动作无法解析）。 */
    private const val NEED_AGENT = "__NEED_AGENT__"
    private const val AGENT_MAX_STEPS = 15
    private const val STAGNANT_LIMIT = 3
    private const val STEP_DELAY_MS = 500L
    private const val HISTORY_LIMIT = 8
    private const val OBS_ITEM_LIMIT = 60
    private const val VISION_MAX_WIDTH = 720

    /** 兼容旧调用：不带模型时仅走正则快路径。 */
    suspend fun execute(context: Context, instruction: String): String =
        execute(context, instruction, null, null)

    /**
     * 执行设备控制指令。[model] 提供 Agent 推理能力（无模型时退化为快路径）；
     * [onProgress] 每步执行后回调人类可读的中间进度，用于聊天界面实时回显。
     */
    suspend fun execute(
        context: Context,
        instruction: String,
        model: AiModel?,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        // 非 root 用户：已连接通道优先（避免无障碍用户每次被无线调试探测拖慢），
        // 无线调试断开时 ensureConnected 自动重连，Shizuku 未授权则请求，均不可用则走无障碍
        val root = RootController.canUseRoot()
        val a11y = if (!root) AccessibilityDevice.isActiveChannel() else false
        var adb = if (!root && !a11y && !AdbShellController.isConnected()) AdbShellController.ensureConnected()
        else if (!root && !a11y) AdbShellController.isConnected() else false
        // 已配对但首轮连接失败（无线调试端口变化/瞬态）：等待 1.2s 重试一次再判定
        if (!root && !a11y && !adb && PairState.isPaired(context)) {
            Thread.sleep(1200)
            adb = AdbShellController.ensureConnected()
        }
        val shizuku = if (!root && !a11y && !adb && !ShizukuController.hasPermission()) {
            ShizukuController.requestPermission()
            ShizukuController.hasPermission()
        } else if (!root && !a11y) ShizukuController.hasPermission() else false
        if (!root && !adb && !shizuku && !a11y) {
            if (AdbShellController.isAuthRejected()) {
                "❌ 此手机的无线调试尚未授权内置密钥（换机或授权被撤销），请点击「一键配对」重新配对后再试。"
            } else if (PairState.isPaired(context)) {
                "❌ 无线调试已配对但当前连接失败：请确认开发者选项的「无线调试」开关已开启（端口变化会自动重连），或改用下方「无障碍控制」。"
            } else {
                "❌ 当前无可用控制通道。请开启：① Root 权限（已 root 设备）② 设置页「无线调试」配对 ③ Shizuku 授权 ④ 设置页「无障碍控制」（免 Root，与无线调试二选一）。"
            }
        } else {
            runCatching { runCommand(context, instruction.trim(), model, onProgress) }
                .getOrElse { e -> "❌ 设备控制失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }

    /**
     * 快路径（正则即时动作）优先，未命中或动作链不完整时先进离线智能引擎
     * （本地学习回放 / 智能操作 / 智能读屏）；离线无法解析或执行失败时
     * 自动联网决策——调用已配置模型接管当前屏幕继续完成目标。
     */
    private fun runCommand(
        context: Context,
        text: String,
        model: AiModel?,
        onProgress: ((String) -> Unit)?
    ): String {
        val quick = parseCommand(context, text)
        val needsAgent = quick == NEED_AGENT
            || quick.contains("无法识别指令")
            || quick.contains("未找到应用")
            || quick.contains("text not found on screen")
        if (!needsAgent) return quick
        // 离线智能引擎：本地学习回放 / 意图解析执行，不依赖联网模型
        val offline = OfflineAgent.run(context, text, onProgress)
        if (offline != null) {
            // 自动联网决策：离线执行失败且有模型时，LLM 基于当前屏幕状态继续
            if (!offline.startsWith("❌")) return offline
            val m = model ?: return offline
            onProgress?.invoke("🌐 离线执行受阻，自动切换联网模型决策…")
            return agentLoop(
                context,
                "目标：$text\n（离线引擎已尝试，受阻原因：${offline.take(120)}。请基于当前屏幕状态继续完成目标）",
                m,
                onProgress
            )
        }
        // 无法解析：无模型时提示，有模型时自动联网决策
        val m = model ?: return quick
        return agentLoop(context, text, m, onProgress)
    }

    /**
     * LLM Agent 循环：观察屏幕 → 模型决策 JSON 动作 → 执行 → 校验屏幕变化，
     * 直到模型判定完成（done）或达到步数上限 / 屏幕连续无变化兜底退出。
     */
    private fun agentLoop(
        context: Context,
        instruction: String,
        model: AiModel,
        onProgress: ((String) -> Unit)?
    ): String {
        val api = OpenAiApi(model.baseUrl, model.apiKey, readTimeoutMs = 120_000)
        onProgress?.invoke("🤖 开始自动操作：$instruction")
        var (observation, items) = readScreenOrRetry()
        if (observation.isBlank() || observation.startsWith("screen dump failed")) {
            // 本地读屏（节点树/像素 OCR）失败：自动联网视觉识别兜底
            onProgress?.invoke("🌐 本地读屏失败，尝试联网视觉识别…")
            val vision = visionObservation(model, api)
            observation = vision.first
            items = vision.second
            if (observation.startsWith("❌")) return observation
        }
        val history = mutableListOf<String>()
        var stagnant = 0
        repeat(AGENT_MAX_STEPS) { step ->
            val user = buildAgentPrompt(instruction, observation, history)
            val raw = runCatching {
                api.completeChat(model.modelId, AGENT_SYSTEM, listOf("user" to user), 0.2f)
            }.getOrElse { e ->
                return "❌ Agent 决策失败（第 ${step + 1} 步）：${e.message ?: e.javaClass.simpleName}"
            }
            val action = parseAgentAction(raw) ?: return "❌ Agent 返回了无法解析的动作：\n${raw.take(200)}"
            val name = action.get("action")?.asString
            if (name == "done") {
                val summary = action.get("summary")?.asString?.trim()
                val ok = action.get("ok")?.asBoolean ?: true
                return if (summary.isNullOrBlank()) "✅ 操作完成" else "${if (ok) "✅" else "⚠️"} $summary"
            }
            onProgress?.invoke("第 ${step + 1} 步：${describeAction(action, name)}")
            val result = executeAgentAction(context, action, name, items) ?: return "❌ Agent 动作缺少必要参数：$raw"
            if (result.isNotBlank()) onProgress?.invoke("↳ $result")
            // 记录动作轨迹供下一轮决策，避免模型丢失上下文重复操作
            history += "[${step + 1}] ${describeAction(action, name)} → ${result.take(90)}"
            if (history.size > HISTORY_LIMIT) history.removeAt(0)
            // 打开应用后等待窗口完全出现再读屏，避免过渡期读屏失败
            Thread.sleep(if (name == "open") 1500L else STEP_DELAY_MS)
            val (next, nextItems) = readScreenOrRetry()
            if (next.startsWith("screen dump failed")) {
                // 本步读屏失败：保留上轮观察并提示模型换策略，避免把错误文本喂给模型
                stagnant++
                if (stagnant >= STAGNANT_LIMIT) {
                    return "❌ 屏幕连续 ${STAGNANT_LIMIT} 次无变化或读屏失败，已停止操作。\n目标：$instruction"
                }
                observation = observation + "\n\n⚠️ 提示：上一步操作后读屏失败（${next.take(80)}），" +
                    "请 wait 等待或 back 返回后重试，不要重复相同的动作。"
                items = nextItems
            } else if (next == observation && name != "wait") {
                // wait 动作不判定停滞（等待期间屏幕本就无变化）
                stagnant++
                if (stagnant >= STAGNANT_LIMIT) {
                    return "❌ 屏幕连续 ${STAGNANT_LIMIT} 次无变化，已停止操作（可能卡在加载页或目标无法触达）。\n目标：$instruction"
                }
                // 屏幕未变化：把提示附给模型，让它换策略而非重复相同动作
                observation = next + "\n\n⚠️ 提示：上一步操作后屏幕没有变化（第 ${stagnant} 次）。" +
                    "请换一种操作：先 swipe 滚动/滑动、点击其他位置、back 返回上一级，或 wait 等待加载完成后再观察，不要重复相同的动作。"
                items = nextItems
            } else {
                stagnant = 0
                observation = next
                items = nextItems
            }
        }
        return "❌ 已达到最大步骤数（$AGENT_MAX_STEPS），未能确认目标完成。\n目标：$instruction"
    }

    /** 屏幕元素：label 为文本/描述，x/y 为中心坐标，flags 为 clickable/editable/scrollable 等标志。 */
    private data class UiItem(val label: String, val x: Int, val y: Int, val flags: String)

    /** 读屏带自动重试：窗口切换/过渡期节点树未就绪时等待后重试（最多 3 次）。 */
    private fun readScreenOrRetry(): Pair<String, List<UiItem>> {
        var attempt = 0
        while (true) {
            val obs = buildObservation()
            val ok = !obs.first.isBlank()
                && !obs.first.startsWith("screen dump failed")
                && !obs.first.startsWith("screen parse failed")
            if (ok) return obs
            if (++attempt >= 3) return obs
            Thread.sleep(600)
        }
    }

    /**
     * 联网视觉读屏兜底：本地节点树/像素 OCR 全部失败时，把截图发给视觉模型，
     * 解析出带估计坐标的文本元素（格式 - "文本" (x,y)），编号化后模型可直接 tap_item。
     */
    private fun visionObservation(model: AiModel, api: OpenAiApi): Pair<String, List<UiItem>> {
        val jpeg = captureScreenJpeg()
            ?: return "❌ 视觉读屏失败：无法获取屏幕截图（需要 Root/无线调试通道）" to emptyList()
        val size = ShizukuDevice.screenSize()
        val prompt = """
            这是手机截屏（屏幕分辨率 $size）。请严格按行输出所有可见文本与按钮，格式：
            - "文本内容" (x,y)
            x,y 为该元素中心在 $size 分辨率下的估计像素坐标，按屏幕从上到下、从左到右排列。
            只输出识别行，禁止任何解释或多余内容。
        """.trimIndent()
        val raw = runCatching {
            api.completeChat(
                model.modelId,
                "你是手机屏幕识别助手，只输出结构化识别结果。",
                listOf("user" to prompt),
                0.2f,
                imageDataUrl = jpeg
            )
        }.getOrElse { e ->
            return "❌ 视觉读屏失败：${e.message ?: e.javaClass.simpleName}" to emptyList()
        }
        val items = mutableListOf<UiItem>()
        val regex = Regex("""- "?(.*?)"?\s*\((\d+)\s*[,，]\s*(\d+)\)""")
        for (line in raw.lines()) {
            regex.find(line)?.let {
                items += UiItem(it.groupValues[1].trim(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), "clickable")
            }
        }
        if (items.isEmpty()) return raw.trim() to emptyList()
        // 编号化，保持与节点树观察一致的格式，模型可直接引用 tap_item
        val sb = StringBuilder("【视觉识别的可操作元素】编号=文本 估计中心坐标\n")
        items.forEachIndexed { i, it ->
            sb.appendLine("$i. \"${it.label}\" 中心=(${it.x},${it.y})")
        }
        return sb.toString().trim() to items
    }

    /** 截屏压缩为 JPEG data URL（视觉兜底用，限制宽度降 token 成本）。 */
    private fun captureScreenJpeg(): String? = runCatching {
        val bmp = ScreenOcr.captureBitmap() ?: return null
        val scale = min(1f, VISION_MAX_WIDTH.toFloat() / bmp.width)
        val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, nw, nh, true) else bmp
        if (scaled != bmp) bmp.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
        scaled.recycle()
        "[image omitted]" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private val ITEM_LINE_REGEX = Regex("""^-\s+(?:"(.*?)"|\s*\[(.*?)\])(.*?)\s*bounds=\((\d+),(\d+),(\d+),(\d+)\)""")

    /**
     * 读屏并构建 Agent 观察：可操作元素预计算中心坐标并编号（模型直接引用编号，
     * 避免弱模型心算坐标出错），其余文本仅列出供参考。返回 (摘要文本, 可点元素列表)。
     */
    private fun buildObservation(): Pair<String, List<UiItem>> {
        val raw = ShizukuDevice.screenDump()
        if (raw.isBlank() || raw.startsWith("screen dump failed") || raw.startsWith("screen parse failed")) {
            return raw to emptyList()
        }
        val items = mutableListOf<UiItem>()
        var app = ""
        var screen = ""
        for (line in raw.lines()) {
            when {
                line.startsWith("app=") -> app = line
                line.startsWith("screen=") -> screen = line
                line.startsWith("- ") -> {
                    val m = ITEM_LINE_REGEX.find(line) ?: continue
                    val label = m.groupValues[1].ifBlank { m.groupValues[2] }
                    val flags = m.groupValues[3].trim()
                    val l = m.groupValues[4].toInt(); val t = m.groupValues[5].toInt()
                    val r = m.groupValues[6].toInt(); val b = m.groupValues[7].toInt()
                    items += UiItem(label, (l + r) / 2, (t + b) / 2, flags)
                }
            }
        }
        if (items.isEmpty()) return raw to emptyList()
        val clickable = items.filter { it.flags.contains("clickable") || it.flags.contains("editable") }
        val sb = StringBuilder()
        if (app.isNotBlank()) sb.appendLine(app)
        if (screen.isNotBlank()) sb.appendLine(screen)
        sb.appendLine("【可操作元素】编号=文本 标志 中心坐标（点击用 tap_item 引用编号）")
        clickable.take(OBS_ITEM_LIMIT).forEachIndexed { i, it ->
            sb.appendLine("$i. \"${it.label}\"${it.flags} 中心=(${it.x},${it.y})")
        }
        val others = items.filter { !it.flags.contains("clickable") && !it.flags.contains("editable") }
        if (others.isNotEmpty()) {
            sb.appendLine("【其他文本】（仅供参考，需要点击时用 tap_text）")
            others.take(OBS_ITEM_LIMIT).forEach {
                sb.appendLine("- \"${it.label}\" 中心=(${it.x},${it.y})")
            }
        }
        return sb.toString().trim() to clickable
    }

    /** 组装每轮发给模型的提示：目标 + 操作历史（最近 N 步）+ 当前屏幕摘要。 */
    private fun buildAgentPrompt(instruction: String, observation: String, history: List<String>): String {
        val sb = StringBuilder("目标：$instruction\n")
        if (history.isNotEmpty()) {
            sb.append("\n操作历史（最近 ${history.size} 步，不要重复无效动作）：\n")
            history.forEach { sb.append(it).append('\n') }
        }
        sb.append("\n当前屏幕（UI 摘要，bounds 中心坐标已算好）：\n").append(observation)
        return sb.toString()
    }

    /** 从模型输出中提取 JSON 动作对象（容忍 ```json 围栏与前后杂讯）。 */
    private fun parseAgentAction(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject
        }.getOrNull()
    }

    /** 执行单个 Agent 动作；[items] 为最近一次读屏的可点元素列表（供 tap_item 引用）。返回执行结果文本，动作非法/缺参时返回 null。 */
    private fun executeAgentAction(context: Context, action: JsonObject, name: String?, items: List<UiItem>): String? {
        return when (name) {
            "tap_item" -> {
                val index = action.get("index")?.asInt ?: return null
                val item = items.getOrNull(index) ?: return null
                ShizukuDevice.tap(item.x, item.y)
            }
            "tap" -> {
                val x = action.get("x")?.asInt ?: return null
                val y = action.get("y")?.asInt ?: return null
                ShizukuDevice.tap(x, y)
            }
            "tap_text" -> {
                val text = action.get("text")?.asString ?: return null
                ShizukuDevice.tapText(text)
            }
            "long_press" -> {
                val x = action.get("x")?.asInt ?: return null
                val y = action.get("y")?.asInt ?: return null
                val ms = action.get("ms")?.asLong ?: 700L
                ShizukuDevice.longPress(x, y, ms)
            }
            "double_tap" -> {
                val x = action.get("x")?.asInt ?: return null
                val y = action.get("y")?.asInt ?: return null
                ShizukuDevice.doubleTap(x, y)
            }
            "swipe" -> {
                val dir = action.get("dir")?.asString
                if (!dir.isNullOrBlank()) {
                    swipeDir(dir)
                } else {
                    val x1 = action.get("x1")?.asInt ?: return null
                    val y1 = action.get("y1")?.asInt ?: return null
                    val x2 = action.get("x2")?.asInt ?: return null
                    val y2 = action.get("y2")?.asInt ?: return null
                    ShizukuDevice.swipe(x1, y1, x2, y2, 300L)
                }
            }
            "type" -> {
                val text = action.get("text")?.asString ?: return null
                inputText(context, text)
            }
            "key" -> {
                val key = action.get("key")?.asString ?: return null
                when (key.lowercase(Locale.ROOT)) {
                    "back" -> ShizukuDevice.back()
                    "home" -> ShizukuDevice.home()
                    "recents" -> ShizukuDevice.recents()
                    "power" -> ShizukuDevice.keyEvent("26")
                    "volume_up" -> ShizukuDevice.keyEvent("24")
                    "volume_down" -> ShizukuDevice.keyEvent("25")
                    "enter" -> ShizukuDevice.keyEvent("66")
                    "delete" -> ShizukuDevice.keyEvent("67")
                    "menu" -> ShizukuDevice.keyEvent("82")
                    else -> null
                }
            }
            "open" -> {
                val app = action.get("app")?.asString ?: return null
                val pkg = resolveApp(context, app)
                if (pkg == null) "未找到应用：$app" else ShizukuDevice.openApp(pkg)
            }
            "notifications" -> ShizukuDevice.notifications()
            "wait" -> {
                val ms = action.get("ms")?.asLong ?: 500L
                Thread.sleep(ms.coerceIn(0, 10_000))
                "已等待 ${ms}ms"
            }
            else -> null
        }
    }

    /** Agent 动作的人类可读描述（用于聊天界面进度回显）。 */
    private fun describeAction(action: JsonObject, name: String?): String {
        return when (name) {
            "tap_item" -> "点击编号元素 ${action.get("index")?.asInt}"
            "tap" -> "点击坐标 (${action.get("x")?.asInt}, ${action.get("y")?.asInt})"
            "tap_text" -> "点击「${action.get("text")?.asString}」"
            "long_press" -> "长按坐标 (${action.get("x")?.asInt}, ${action.get("y")?.asInt})"
            "double_tap" -> "双击坐标 (${action.get("x")?.asInt}, ${action.get("y")?.asInt})"
            "swipe" -> {
                val dir = action.get("dir")?.asString
                if (!dir.isNullOrBlank()) "向${dir}滑动" else "坐标滑动"
            }
            "type" -> "输入文字「${action.get("text")?.asString}」"
            "key" -> "按键 ${action.get("key")?.asString}"
            "open" -> "打开应用「${action.get("app")?.asString}」"
            "notifications" -> "下拉通知栏"
            "wait" -> "等待 ${action.get("ms")?.asLong ?: 500}ms"
            else -> name ?: "未知动作"
        }
    }

    /** 将应用中文名/别名/包名解析为包名。 */
    internal fun resolveApp(context: Context, name: String): String? {
        val n = name.trim()
        ALIASES[n]?.let { return it }
        val apps = AppIndexPlugin.all(context)
        apps.firstOrNull { it.label.equals(n, ignoreCase = true) }?.let { return it.pkg }
        AppIndexPlugin.find(context, n)?.let { return it }
        if (Regex("^[a-z0-9_.]{3,}$").matches(n)) return n
        return null
    }

    private fun parseCommand(context: Context, text: String): String {
        // 去掉「请帮我/帮我/请」等礼貌前缀，统一指令格式
        val lower = text.lowercase(Locale.ROOT)
            .replace(Regex("^(请帮我|帮我|请|麻烦)"), "")

        Regex("^(打开|启动|开启|open|launch)\\s*(.+)$").find(lower)?.let {
            val opened = openWithActions(context, it.groupValues[2])
            if (opened == NEED_AGENT) return NEED_AGENT
            return "✅ " + opened
        }
        Regex("^(点赞|喜欢)\\s*(?:前)?\\s*([0-9一二两三四五六七八九十二十三十]{1,2})?\\s*条?(?:视频|作品|内容)?$").find(lower)?.let {
            val n = parseCount(it.groupValues[2]) ?: 1
            return "✅ " + ShizukuDevice.likeVideos(n)
        }
        Regex("^(点击|点一下|点按|点|tap|click)\\s*\\(?\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.tap(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^(点击|点一下|点按|点|tap|click)\\s*(.+)$").find(lower)?.let {
            val target = it.groupValues[2].trim('"', '\u201C', '\u201D', ' ', '「', '」')
            return "✅ " + ShizukuDevice.tapText(target)
        }
        Regex("^(长按|long ?press|longpress)\\s*\\(?\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.longPress(it.groupValues[2].toInt(), it.groupValues[3].toInt(), 700L)
        }
        Regex("^(双击|double ?tap|doubletap)\\s*\\(?\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.doubleTap(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^(滑动|swipe)\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)\\s*[\\s,，]+\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.swipe(
                it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                it.groupValues[4].toInt(), it.groupValues[5].toInt(), 300L
            )
        }
        if (lower.contains("上滑") || lower.contains("向上滑")) return "✅ " + swipeDir("up")
        if (lower.contains("下滑") || lower.contains("向下滑")) return "✅ " + swipeDir("down")
        if (lower.contains("左滑") || lower.contains("向左滑")) return "✅ " + swipeDir("left")
        if (lower.contains("右滑") || lower.contains("向右滑")) return "✅ " + swipeDir("right")

        Regex("^(输入文字|打字|输入|type|input)\\s*(.+)$").find(lower)?.let {
            return "✅ " + inputText(context, it.groupValues[2])
        }

        if (lower.contains("返回") || lower == "back") return "✅ " + ShizukuDevice.back()
        if (lower.contains("主页") || lower.contains("首页") || lower == "home") return "✅ " + ShizukuDevice.home()
        if (lower.contains("最近任务")) return "✅ " + ShizukuDevice.recents()
        if (lower.contains("锁屏") || lower.contains("熄屏")) return "✅ " + ShizukuDevice.keyEvent("26")
        if (lower.contains("音量加") || lower.contains("音量+")) return "✅ " + ShizukuDevice.keyEvent("24")
        if (lower.contains("音量减") || lower.contains("音量-")) return "✅ " + ShizukuDevice.keyEvent("25")

        if (lower.contains("读屏") || lower.contains("看看屏幕") || lower.contains("查看屏幕") ||
            lower.contains("屏幕内容") || lower.contains("屏幕上") || lower.contains("read screen") ||
            lower.contains("readscreen")) {
            return "📱 当前屏幕（智能识别）：\n" + OfflineAgent.smartDump()
        }
        if (lower.contains("通知栏") || lower.contains("下拉通知")) return "✅ " + ShizukuDevice.notifications()

        return "❌ 无法识别指令：$text\n支持：打开应用（可带动作，如「打开抖音点赞前十条视频」）/ 点赞N条视频 / 点击(x,y)或\"点一下\"文本 / 长按(x,y) / 双击(x,y) / 上滑下滑左滑右滑 / 输入文本 / 返回主页最近任务 / 读屏 / 通知栏 / 锁屏"
    }

    private fun swipeDir(dir: String): String {
        val size = ShizukuDevice.screenSize()
        val wh = size.split("x")
        if (wh.size != 2) return "获取屏幕尺寸失败"
        val w = wh[0].trim().toIntOrNull() ?: 1080
        val h = wh[1].trim().toIntOrNull() ?: 2400
        return when (dir) {
            "up" -> ShizukuDevice.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.2).toInt(), 300L)
            "down" -> ShizukuDevice.swipe(w / 2, (h * 0.2).toInt(), w / 2, (h * 0.8).toInt(), 300L)
            "left" -> ShizukuDevice.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 300L)
            else -> ShizukuDevice.swipe((w * 0.15).toInt(), h / 2, (w * 0.85).toInt(), h / 2, 300L)
        }
    }

    /** 输入文本：ASCII 直接 input text；含中文等非 ASCII 时走剪贴板 + 粘贴键。 */
    internal fun inputText(context: Context, text: String): String {
        if (text.isEmpty()) return "输入内容为空"
        return if (text.all { it.code <= 127 }) {
            ShizukuDevice.inputText(text)
        } else {
            val ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("xs-device-input", text))
                true
            }.getOrDefault(false)
            if (!ok) return "非 ASCII 文本需要剪贴板粘贴，但设置剪贴板失败"
            ShizukuDevice.keyEvent("279") // KEYCODE_PASTE
        }
    }

    /**
     * 打开应用并解析后续动作。匹配顺序：
     * ① 别名/索引精确 ②「应用名前缀 + 动作链」最长匹配（如「打开抖音点赞前十条视频」→ 打开抖音 + 点赞 10 条）
     * ③ AppIndex 后缀词裁剪兜底 ④ 包名直开。
     * 应用名匹配成功但剩余动作链无法快速解析时返回 [NEED_AGENT]，交由 LLM Agent 接管。
     */
    private fun openWithActions(context: Context, query: String): String {
        val q = query.trim()
        val apps = AppIndexPlugin.all(context)
        // ① 精确匹配
        ALIASES[q]?.let { return ShizukuDevice.openApp(it) }
        apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return ShizukuDevice.openApp(it.pkg) }
        // ② 应用名前缀最长匹配，剩余部分交给动作解析（如「抖音点赞前十条视频」→ 抖音 + 点赞前十条视频）
        val candidates = LinkedHashMap<String, String>()
        ALIASES.forEach { (name, pkg) -> candidates[name] = pkg }
        apps.forEach { candidates[it.label] = it.pkg }
        val match = candidates.entries
            .filter { it.key.isNotBlank() && q.length > it.key.length && q.startsWith(it.key.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.key.length }
        if (match != null) {
            // 去掉「的/去/并/然后/接着/再」等连接词，让「打开微信并打开第一个群聊」这类指令能被继续解析
            val rest = q.drop(match.key.length).trim().trimStart('的', '去', '，', ',', '并', '且', '还', '然', '后', '接', '着', '再')
            if (rest.isEmpty()) return ShizukuDevice.openApp(match.value)
            val action = runAction(context, rest)
            if (action != null) {
                val opened = ShizukuDevice.openApp(match.value)
                return "$opened\n$action"
            }
            return NEED_AGENT
        }
        // ③ 兜底：AppIndex 后缀词裁剪 / 包名直开
        AppIndexPlugin.find(context, q)?.let { return ShizukuDevice.openApp(it) }
        if (Regex("^[a-z0-9_.]{3,}$").matches(q)) return ShizukuDevice.openApp(q)
        return "未找到应用：$q"
    }

    /** Agent 系统提示词：定义可用动作协议与决策规则。 */
    private val AGENT_SYSTEM = """
        你是手机自动化控制 Agent（类 Codex 电脑版）。用户给出目标，你通过「读屏 → 决策 → 执行 → 验证」循环逐步完成，
        可以操控手机里任何应用内部的界面元素（按钮、输入框、菜单、弹窗等）。
        每轮你会收到：目标 + 操作历史（最近几步，勿重复无效动作）+ 当前屏幕 UI 摘要。
        【可操作元素】已编号 0..N 并算好中心坐标，点击优先用 tap_item 引用编号，不要自行心算坐标；
        屏幕内容全部来自无障碍/读屏节点树实时解析（无截图），点击坐标以编号元素为准。

        只输出一行 JSON（禁止输出 JSON 以外的任何字符、注释或解释），动作协议：
        {"action":"tap_item","index":0}  点击编号元素（首选）
        {"action":"tap_text","text":"点赞"}  按屏幕文本点击（编号列表里没有时才用）
        {"action":"tap","x":123,"y":456}  点击坐标
        {"action":"long_press","x":123,"y":456,"ms":700}  长按
        {"action":"double_tap","x":123,"y":456}  双击
        {"action":"swipe","dir":"up|down|left|right"}  方向滑动（翻页/滚动列表）
        {"action":"swipe","x1":..,"y1":..,"x2":..,"y2":..}  坐标滑动
        {"action":"type","text":"你好"}  向焦点输入框输入文字（中文自动走剪贴板粘贴）
        {"action":"key","key":"back|home|recents|power|volume_up|volume_down|enter|delete"}  按键
        {"action":"open","app":"抖音|微信|包名"}  打开应用（中文名/别名/包名）
        {"action":"notifications"}  下拉通知栏
        {"action":"wait","ms":800}  等待页面加载/动画完成
        {"action":"done","ok":true,"summary":"已完成，中文总结"}  目标完成；遇到无法逾越的障碍时 ok=false 说明原因

        决策规则：
        - 一次只执行一个动作，执行后根据下一轮屏幕反馈决定后续步骤；先 open/wait 再找元素。
        - 弹窗/权限提示先处理（点击允许/取消/关闭按钮）再继续原目标。
        - 输入中文前先 tap_item/tap_text 点中对应输入框，再 type。
        - 目标未完成不要提前 done；找不到目标元素先 swipe 滚动再观察，必要时 back 返回上一级换路径。
        - 点赞/关注等社交按钮点击后会有状态变化，结合屏幕反馈确认成功。

        常见目标动作链示例：
        - 打开抖音点赞前 10 条视频：open 抖音 → wait → 找「点赞」按钮 tap_item/tap_text → swipe up 到下一条 → 重复至 10 条 → done
        - 打开微信并打开第一个群聊：open 微信 → wait → 消息列表第一条聊天项 tap_item → done
        - 打开抖音搜索「华为手机」并点进第一个视频：open 抖音 → 点顶部搜索框 → type 华为手机 → key enter → wait → 点第一个搜索结果 → wait → done
        - 给张三发微信消息：open 微信 → 点搜索 → type 张三 → tap 联系人 → 点输入框 → type 消息内容 → key enter → done
    """.trimIndent()

    /** 解析并执行应用名之后的动作；无法识别时返回 null（静默只打开应用）。 */
    private fun runAction(context: Context, rest: String): String? {
        val r = parseCommand(context, rest)
        return if (r.startsWith("❌ 无法识别指令")) null else r.removePrefix("✅ ")
    }

    /** 解析点赞数量：阿拉伯数字或中文数字（一~十、二十、三十），null 表示默认 1 条。 */
    private fun parseCount(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        s.toIntOrNull()?.let { return it }
        return when (s) {
            "一" -> 1; "两" -> 2; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10; "二十" -> 20; "三十" -> 30
            else -> null
        }
    }

    private val ALIASES = mapOf(
        "设置" to "com.android.settings", "相机" to "com.android.camera",
        "图库" to "com.android.gallery3d", "相册" to "com.android.gallery3d",
        "浏览器" to "com.android.browser", "文件管理" to "com.android.documentsui",
        "文件" to "com.android.documentsui", "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq", "抖音" to "com.ss.android.ugc.aweme",
        "支付宝" to "com.eg.android.AlipayGphone", "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall", "美团" to "com.sankuai.meituan",
        "哔哩哔哩" to "tv.danmaku.bili", "b站" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs", "微博" to "com.sina.weibo",
        "网易云音乐" to "com.netease.cloudmusic", "快手" to "com.smile.gifmaker",
        "高德地图" to "com.autonavi.minimap", "百度地图" to "com.baidu.BaiduMap",
        "计算器" to "com.android.calculator2", "时钟" to "com.android.deskclock",
        "日历" to "com.android.calendar", "联系人" to "com.android.contacts",
        "电话" to "com.android.dialer", "短信" to "com.android.mms",
        "应用商店" to "com.android.vending", "play商店" to "com.android.vending"
    )
}
