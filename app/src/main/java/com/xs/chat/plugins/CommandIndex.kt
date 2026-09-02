package com.xs.chat.plugins

/** 应用索引条目（纯数据，不依赖 Android，便于复杂指令识别逻辑单测）。 */
data class IndexedApp(val pkg: String, val label: String)

/**
 * 复杂指令索引的纯匹配逻辑：识别复合句式（如「在抖音搜索华为手机并点进第一个视频」）
 * 并在句中抽取应用名。不依赖 Android 上下文，由 [AppIndexPlugin] 喂入缓存的应用列表。
 */
object CommandIndex {

    /** 疑问/闲聊句式排除词：命中视为聊天而非设备指令。 */
    val CHAT_HINTS = listOf(
        "如何", "怎么", "怎样", "教程", "方法", "推荐", "请问", "是什么", "是什么东西",
        "有哪些", "可以吗", "能不能", "是否", "怎么用", "是不是", "介绍", "介绍一下",
        "讲讲", "说说", "谈谈", "科普", "原理", "功能", "区别", "对比", "聊聊"
    )

    /** 句首控制动词：出现在句首时判定为设备指令。 */
    private val LEADING_VERBS = listOf(
        "打开", "启动", "开启", "进入", "去", "在", "搜索", "搜", "查一下",
        "点开", "点进", "长按", "双击", "输入", "截图", "录屏", "返回", "锁屏", "熄屏"
    )

    /** 复合句式动作词：与索引应用名同时出现时判定为设备指令。 */
    private val ACTION_VERBS = listOf(
        "搜索", "搜", "查找", "查一下", "点赞", "收藏", "评论", "转发", "下载", "发送",
        "输入", "打开", "点开", "点进", "关注", "查看", "播放", "进入", "点击", "找一下",
        "退出", "发消息", "打电话", "浏览", "滑动", "长按", "双击", "录屏", "截屏", "保存"
    )

    /** 应用名前置锚词：紧邻应用名前出现时可信度更高。 */
    private val APP_ANCHORS = listOf("打开", "启动", "开启", "进入", "去", "在", "搜", "搜索", "到", "上", "用")

    /** 疑问/闲聊句式排除。 */
    fun isQuestion(text: String): Boolean {
        val t = text.trim()
        return CHAT_HINTS.any { t.contains(it) } || t.endsWith("?") || t.endsWith("？")
    }

    /**
     * 复杂指令识别：命中句首控制动词，或「应用名 + 动作词」复合句式。
     */
    fun isDeviceCommand(text: String, apps: List<IndexedApp>): Boolean {
        val t = text.trim()
        if (t.isEmpty() || isQuestion(t)) return false
        val compact = t.replace(" ", "")
        if (compact.length >= 2 && LEADING_VERBS.any { compact.startsWith(it) }) return true
        if (ACTION_VERBS.any { compact.contains(it) }) {
            if (extractAppName(t, apps) != null) return true
        }
        return false
    }

    /**
     * 从复杂指令中抽取应用名（如「在抖音搜索华为手机」→「抖音」）。
     * 评分策略：前置锚动词与后随动作词加分，标签越长越可信。
     */
    fun extractAppName(text: String, apps: List<IndexedApp>): String? {
        val t = text.replace(" ", "")
        if (t.isEmpty()) return null
        var found = false
        var bestName: String? = null
        var bestScore = Int.MIN_VALUE
        for (app in apps) {
            val label = app.label.trim()
            if (label.length < 2) continue
            val idx = t.indexOf(label)
            if (idx < 0) continue
            found = true
            var score = label.length * 2
            val before = t.substring(0, idx)
            val after = t.substring(idx + label.length)
            if (before.isEmpty() || APP_ANCHORS.any { before.endsWith(it) }) score += 30
            else if (before.endsWith("帮") || before.endsWith("请") || before.endsWith("麻烦")) score += 12
            else score -= 6
            if (after.isEmpty() || ACTION_VERBS.any { after.startsWith(it) }) score += 10
            if (score > bestScore) {
                bestScore = score
                bestName = label
            }
        }
        return if (found) bestName else null
    }
}


