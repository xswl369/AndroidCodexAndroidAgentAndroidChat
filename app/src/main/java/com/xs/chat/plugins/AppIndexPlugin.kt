package com.xs.chat.plugins

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用索引插件：App 启动时自动枚举手机全部已安装应用（启动器应用：包名 + 中文名），
 * 缓存到本地供「打开应用」即时解析，无需每次实时查询 PackageManager。
 *
 * 同时维护「复杂指令索引」：识别复合句式（如「在抖音搜索华为手机并点进第一个视频」、
 * 「打开微信给他发条消息」），供聊天意图路由（ChatViewModel）与离线智能引擎
 * （OfflineAgent）直接命中，让复杂指令与应用名一样可被本地识别，无需联网。
 * 纯匹配逻辑见 [CommandIndex]，本类只负责持久化与 Android 侧接入。
 */
object AppIndexPlugin {
    private const val PREFS = "app_index"
    private const val KEY_APPS = "apps"
    private const val KEY_CMDS = "commands"
    private const val KEY_TIME = "time"

    data class AppInfo(val pkg: String, val label: String)

    /** 复杂指令模板：描述 + 示例句式（持久化到索引，供展示与示例参考）。 */
    data class CommandInfo(val desc: String, val example: String)

    private val DEFAULT_COMMANDS = listOf(
        CommandInfo("打开应用并搜索", "打开抖音搜索华为手机并点进第一个视频"),
        CommandInfo("打开应用并点赞/评论", "打开抖音点赞前十条视频"),
        CommandInfo("打开应用并指定操作", "打开微信并打开第一个群聊"),
        CommandInfo("应用内搜索", "在淘宝搜索手机壳"),
        CommandInfo("打开应用找内容", "打开B站并找到我的关注"),
        CommandInfo("给联系人发消息", "给张三发一条微信消息"),
        CommandInfo("应用内输入文本", "打开备忘录输入明天开会"),
        CommandInfo("读屏并操作", "看看屏幕上有什么 并点一下登录")
    )

    /** 后缀动作词：用户常在应用名后附加操作词（打开后搜索/刷视频等）。 */
    private val SUFFIX_WORDS = listOf(
        "搜索", "搜一下", "搜", "查找", "查一下", "看看", "打开", "一下",
        "页面", "主页", "视频", "详情", "官网", "设置", "列表", "点赞", "评论", "转发"
    )

    // ---------- 枚举/持久化 ----------

    /** 枚举全部启动器应用（含系统应用）并缓存；返回数量。 */
    fun refresh(context: Context): Int {
        val apps = queryLaunchableApps(context)
        val json = JSONArray()
        apps.forEach { json.put(JSONObject().put("p", it.pkg).put("l", it.label)) }
        prefs(context).edit().putString(KEY_APPS, json.toString()).putLong(KEY_TIME, System.currentTimeMillis()).apply()
        return apps.size
    }

    /** 读取缓存应用列表；为空时尝试实时刷新。 */
    fun all(context: Context): List<AppInfo> {
        val cached = load(context)
        if (cached.isEmpty()) {
            refresh(context)
            return load(context)
        }
        return cached
    }

    /** 复杂指令模板（含内置默认模板）；空缓存时写回默认模板。 */
    fun commands(context: Context): List<CommandInfo> {
        val raw = prefs(context).getString(KEY_CMDS, "")
        if (raw.isNullOrBlank()) {
            val def = DEFAULT_COMMANDS
            saveCommands(context, def)
            return def
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CommandInfo(o.optString("d"), o.optString("e"))
            }
        }.getOrDefault(DEFAULT_COMMANDS)
    }

    /** 覆盖复杂指令模板索引（供设置页管理，暂以内置模板为默认）。 */
    fun saveCommands(context: Context, list: List<CommandInfo>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("d", it.desc).put("e", it.example)) }
        prefs(context).edit().putString(KEY_CMDS, arr.toString()).apply()
    }

    /**
     * 复杂指令识别：判断一段用户输入是否属于「设备控制」指令（含复合句式）。
     * 仅读缓存，不触发应用枚举，保证主线程安全。
     */
    fun isDeviceCommand(context: Context, text: String): Boolean =
        CommandIndex.isDeviceCommand(text, load(context).map { IndexedApp(it.pkg, it.label) })

    /**
     * 从复杂指令中抽取「应用名」（如「在抖音搜索华为手机」→「抖音」）。
     * 仅读缓存；[refreshIfEmpty] 为 true 且缓存为空时才触发枚举补全。
     */
    fun extractAppName(context: Context, text: String, refreshIfEmpty: Boolean = true): String? {
        val cached = load(context)
        val apps = if (cached.isEmpty() && refreshIfEmpty) { refresh(context); load(context) } else cached
        return CommandIndex.extractAppName(text, apps.map { IndexedApp(it.pkg, it.label) })
    }

    /**
     * 按用户输入查找应用包名，返回 null 表示未找到。
     * 匹配顺序：① 名称精确/包含（可带后缀词裁剪） ② 复杂指令句中抽取应用名再匹配
     * ③ 包名包含 ④ 实时 PackageManager 兜底。
     */
    fun find(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val apps = all(context)

        // ① 名称精确 / 包含（先裁剪动作后缀词）
        apps.firstOrNull { it.label == q }?.let { return it.pkg }
        apps.firstOrNull { it.label.contains(q) }?.let { return it.pkg }
        for (suffix in SUFFIX_WORDS) {
            if (q.endsWith(suffix)) {
                val trimmed = q.dropLast(suffix.length).trim()
                if (trimmed.isNotEmpty()) {
                    apps.firstOrNull { it.label == trimmed }?.let { return it.pkg }
                    apps.firstOrNull { it.label.contains(trimmed) }?.let { return it.pkg }
                }
            }
        }

        // ② 复杂指令句式：先抽应用名再匹配（「在抖音搜索华为手机」→ 抖音）
        val name = extractAppName(context, q, refreshIfEmpty = false)
        if (name != null) {
            apps.firstOrNull { it.label == name }?.let { return it.pkg }
            apps.firstOrNull { it.label.contains(name) }?.let { return it.pkg }
        }

        // ③ 包名包含
        apps.firstOrNull { it.pkg.contains(q, ignoreCase = true) }?.let { return it.pkg }

        // ④ 实时 PackageManager 兜底（缓存未命中时，可能刚新装应用）
        return resolveByLabelLive(context, q)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): List<AppInfo> {
        val raw = prefs(context).getString(KEY_APPS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AppInfo(o.optString("p"), o.optString("l"))
            }
        }.getOrDefault(emptyList())
    }

    private fun queryLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull() ?: return emptyList()
        val seen = LinkedHashMap<String, String>()
        list.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (seen.containsKey(pkg)) return@forEach
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: pkg
            seen[pkg] = label
        }
        return seen.map { AppInfo(it.key, it.value) }
    }

    private fun resolveByLabelLive(context: Context, query: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull() ?: return null
        return list.asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: return@mapNotNull null
                if (label == query || label.contains(query) || pkg.contains(query, ignoreCase = true)) {
                    label to pkg
                } else null
            }
            .minByOrNull { it.first.length }
            ?.second
    }
}
