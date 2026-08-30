package com.xs.chat.plugins

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用索引插件：App 启动时自动枚举手机全部已安装应用（启动器应用：包名 + 中文名），
 * 缓存到本地供「打开应用」即时解析，无需每次实时查询 PackageManager。
 * 新增应用后可在设置页/启动时刷新。
 */
object AppIndexPlugin {
    private const val PREFS = "app_index"
    private const val KEY_APPS = "apps"
    private const val KEY_TIME = "time"

    data class AppInfo(val pkg: String, val label: String)

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

    /**
     * 按用户输入查找应用包名，返回 null 表示未找到。
     * 匹配顺序：① 名称精确/包含 ② 去掉动作后缀词再匹配（如「打开抖音搜索」→「抖音」）
     * ③ 包名包含 ④ 实时 PackageManager 兜底。
     */
    fun find(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val apps = all(context)

        // ① 名称精确 / 包含
        apps.firstOrNull { it.label == q }?.let { return it.pkg }
        apps.firstOrNull { it.label.contains(q) }?.let { return it.pkg }

        // ② 裁剪动作后缀词再匹配（「打开抖音搜索」→ 裁剪「搜索」→「抖音」）
        for (suffix in SUFFIX_WORDS) {
            if (q.endsWith(suffix)) {
                val trimmed = q.dropLast(suffix.length).trim()
                if (trimmed.isNotEmpty()) {
                    apps.firstOrNull { it.label == trimmed }?.let { return it.pkg }
                    apps.firstOrNull { it.label.contains(trimmed) }?.let { return it.pkg }
                }
            }
        }

        // ③ 包名包含
        apps.firstOrNull { it.pkg.contains(q, ignoreCase = true) }?.let { return it.pkg }

        // ④ 实时 PackageManager 兜底（缓存未命中时，可能刚安装新应用）
        return resolveByLabelLive(context, q)
    }

    /** 后缀动作词：用户常在应用名后附加操作词。 */
    private val SUFFIX_WORDS = listOf(
        "搜索", "搜一下", "搜", "查找", "查一下", "看看", "打开", "一下",
        "页面", "主页", "视频", "详情", "官网", "设置", "列表"
    )

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