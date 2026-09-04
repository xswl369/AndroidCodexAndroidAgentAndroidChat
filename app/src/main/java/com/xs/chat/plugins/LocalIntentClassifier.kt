package com.xs.chat.plugins

import java.util.Locale

/**
 * 本地轻量意图分类模型（离线、毫秒级）：
 * 按类别词表加权打分；聊天倾向词（疑问/解释/讨论/写作等）命中即归 CHAT，其次取得分最高的类别。
 * 设备控制要求置信度 ≥ DEVICE_MIN_SCORE（约 1 个强词）才直接接管；
 * 词表没把握但带设备域弱信号时，由上层交给云端小模型二次确认（hasDeviceSignal）。
 */
object LocalIntentClassifier {

    enum class Intent { CHAT, DEVICE_CONTROL, WEB_SEARCH, IMAGE_GEN, VIDEO_GEN }

    const val DEVICE_MIN_SCORE = 1.0f

    private val DEVICE_WORDS = listOf(
        "控制手机" to 2f, "操作手机" to 2f, "操控手机" to 2f, "控制设备" to 2f,
        "帮我打开" to 1.5f, "打开应用" to 1.5f, "帮我输入" to 1.5f, "输入文字" to 1.2f,
        "点击屏幕" to 1.5f, "点一下" to 1.0f, "点开" to 1.2f, "点击" to 0.6f, "点" to 0.3f,
        "读屏" to 2f, "看看屏幕" to 1.5f, "查看屏幕" to 1.5f, "屏幕上有什么" to 1.5f,
        "上滑" to 1.2f, "下滑" to 1.2f, "左滑" to 1.2f, "右滑" to 1.2f, "滑动屏幕" to 1.2f, "滑动" to 0.8f,
        "返回桌面" to 1.5f, "返回上一页" to 1.2f, "锁屏" to 1.2f, "熄屏" to 1.2f,
        "通知栏" to 1f, "下拉通知" to 1.5f, "状态栏" to 1f,
        "长按" to 1f, "双击" to 1f, "按下" to 0.8f, "按住" to 0.8f,
        "音量" to 0.8f, "亮度" to 0.8f, "蓝牙" to 1f, "wi-fi" to 1f, "wifi" to 1f,
        "杀进程" to 1.5f, "打开设置" to 1.2f, "回到桌面" to 1.2f,
        "back" to 1.2f, "home" to 1.2f, "tap" to 1.2f, "click" to 1.2f, "swipe" to 1.2f,
        "read screen" to 2f, "control phone" to 2f, "control device" to 2f,
        "open" to 1f, "launch" to 1f,
        "打开" to 1.0f, "启动" to 1.0f, "关闭" to 0.4f
    )

    private val WEB_WORDS = listOf(
        "搜索" to 1.5f, "搜一下" to 1.5f, "搜" to 0.8f, "查一下" to 1.2f, "查查" to 1.2f, "查询" to 1.2f, "查" to 0.6f,
        "新闻" to 1.2f, "热点" to 1.2f, "热搜" to 1.2f, "最新" to 0.8f, "实时" to 0.8f,
        "天气" to 1.2f, "天气预报" to 1.5f, "汇率" to 1.5f, "股价" to 1.5f, "行情" to 1.2f,
        "多少钱" to 1.2f, "怎么买" to 1f, "在哪里" to 0.8f, "攻略" to 1f,
        "今天" to 0.5f, "哪天" to 0.8f, "几月几号" to 1.5f, "星期几" to 1.5f, "周几" to 1.5f,
        "农历" to 1.5f, "黄历" to 1.5f, "历史上的今天" to 2f,
        "新闻联播" to 1.5f, "赛事" to 1.2f, "比分" to 1.2f, "积分榜" to 1.2f
    )

    private val IMAGE_WORDS = listOf(
        "画一张" to 1.5f, "画个" to 1.5f, "画一幅" to 1.5f, "生成图片" to 2f, "生成图像" to 2f,
        "生成一张" to 2f, "生成图" to 1.5f, "文生图" to 2f, "图生图" to 2f, "做张图" to 1.5f,
        "ai 绘画" to 1.5f, "绘画" to 1f
    )

    private val VIDEO_WORDS = listOf(
        "生成视频" to 2f, "做视频" to 1.5f, "制作视频" to 1.5f, "文生视频" to 2f, "图生视频" to 2f,
        "生成一段视频" to 2f, "生成个视频" to 2f, "生成动画" to 1.5f
    )

    /** 聊天倾向词：命中一律归 CHAT（先决条件），哪怕带设备词。 */
    private val CHAT_WORDS = listOf(
        "解释", "是什么意思", "什么意思", "什么区别", "区别", "怎么理解", "为什么", "为什么",
        "谈谈", "聊一聊", "讨论", "介绍", "怎么用", "如何使用", "怎么称呼", "是怎么回事",
        "帮我想", "帮我写", "帮我总结", "讲一讲", "说说", "分析", "总结一下",
        "可以吗", "行不行", "会不会", "能不能", "怎么办", "帮帮我", "建议",
        "吗", "呢", "吧", "几个", "些"
    )

    /** 设备域弱信号：仅用于判断「是否需要云端小模型二次确认」，命中 ≠ 设备指令。 */
    private val DEVICE_SIGNAL_WORDS = listOf(
        "屏幕", "亮度", "调亮", "调暗", "调低", "调高", "变亮", "变暗",
        "音量", "声音", "调小", "调大", "静音", "免提", "震动", "振动", "飞行模式", "蓝牙", "wifi", "wi-fi",
        "打开", "关闭", "开启", "启动", "退出", "点击", "点一下", "点开", "滑动",
        "上滑", "下滑", "左滑", "右滑", "长按", "双击", "设置", "锁屏", "熄屏", "解锁", "亮屏",
        "桌面", "主页", "返回", "通知栏", "状态栏", "悬浮窗", "后台",
        "截图", "录屏", "分屏", "横屏", "竖屏", "深色模式", "夜间模式", "字体",
        "应用", "软件", "微信", "qq", "抖音", "快手", "淘宝", "京东", "相机", "相册",
        "浏览器", "闹钟", "计时器", "日历", "手电筒", "重启", "关机", "安装", "卸载",
        "数据", "流量", "热点", "存储", "清理", "省电", "充电", "定位",
        "brightness", "volume", "bluetooth", "wifi", "screen", "settings",
        "screenshot", "lock", "unlock", "home", "back", "tap", "click",
        "scroll", "swipe", "open", "close", "launch", "open app"
    )

    /** 是否带设备域弱信号（供云端小模型兜底触发判断）。 */
    fun hasDeviceSignal(text: String): Boolean {
        val t = normalize(text)
        return DEVICE_SIGNAL_WORDS.any { t.contains(it) }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase(Locale.ROOT)
            .replace(Regex("""[\s,，.。!！?？;；:："']+"""), " ")
            .trim()
    }

    /** 分类：返回 (意图, 置信度)。置信度仅对 DEVICE_CONTROL 有意义。 */
    fun classify(text: String): Pair<Intent, Float> {
        val t = normalize(text)
        if (t.isEmpty()) return Intent.CHAT to 0f
        if (CHAT_WORDS.any { t.contains(it) }) return Intent.CHAT to 0f
        var best = Intent.CHAT
        var bestScore = 0f
        score(DEVICE_WORDS, t).let { if (it > bestScore) { bestScore = it; best = Intent.DEVICE_CONTROL } }
        score(WEB_WORDS, t).let { if (it > bestScore) { bestScore = it; best = Intent.WEB_SEARCH } }
        score(IMAGE_WORDS, t).let { if (it > bestScore) { bestScore = it; best = Intent.IMAGE_GEN } }
        score(VIDEO_WORDS, t).let { if (it > bestScore) { bestScore = it; best = Intent.VIDEO_GEN } }
        return best to bestScore
    }

    private fun score(words: List<Pair<String, Float>>, text: String): Float {
        var s = 0f
        for ((w, wt) in words) if (text.contains(w)) s += wt
        return s
    }
}
