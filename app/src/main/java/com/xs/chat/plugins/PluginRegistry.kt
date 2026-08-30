package com.xs.chat.plugins

import com.xs.chat.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 内置插件注册表：列表 = 内置插件（代码注册，不可删除）+ 用户自建插件（插件管理页添加/删除，持久化）。
 * 新增内置插件只需在 {@link #plugins} 追加一项，即可出现在设置页「插件」列表并受开关控制。
 */
object PluginRegistry {

    data class PluginInfo(
        val id: String,
        val name: String,
        val desc: String
    )

    /** 全部内置插件（按需扩展：新增插件只需在此列表追加一项）。 */
    val plugins: List<PluginInfo> = listOf(
        PluginInfo("device_control", "设备控制", "聊天指令操控手机：打开应用 / 点击 / 滑动 / 输入 / 读屏 / 按键"),
        PluginInfo("web_search", "联网搜索", "实时联网搜索，返回网页结果标题、链接与摘要"),
        PluginInfo("file_edit", "文件修改", "AI 按指令修改文件，保留原格式，结果可预览并保存到下载目录"),
        PluginInfo("image_gen", "AI 生图", "文字 / 图片生成图片（需配置支持生图的模型）"),
        PluginInfo("video_gen", "AI 生视频", "文字 / 图片生成视频（需配置支持生视频的模型）"),
        PluginInfo("memory", "记忆插件", "跨会话记录操作与对话历史，可在设置中配置上限")
    )

    /** 全部插件 = 内置 + 用户自建。 */
    fun all(settings: SettingsStore): List<PluginInfo> = plugins + userPlugins(settings)

    /** 用户自建插件（插件管理页添加，持久化在 SettingsStore）。 */
    fun userPlugins(settings: SettingsStore): List<PluginInfo> {
        val raw = settings.userPluginsJson()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) null
                else PluginInfo(id, name, o.optString("desc").trim())
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 添加用户插件。id 需为小写字母/数字/下划线 2-32 位，且不能与内置或其他用户插件重名。
     * 返回 false 表示校验失败或已存在。
     */
    fun addUserPlugin(settings: SettingsStore, id: String, name: String, desc: String): Boolean {
        val pid = id.trim().lowercase(Locale.ROOT)
        val pname = name.trim()
        if (!Regex("^[a-z0-9_]{2,32}$").matches(pid) || pname.isEmpty()) return false
        val list = userPlugins(settings).toMutableList()
        if (plugins.any { it.id == pid } || list.any { it.id == pid }) return false
        list.add(PluginInfo(pid, pname, desc.trim()))
        save(settings, list)
        return true
    }

    /** 删除用户插件；内置插件不可删。返回是否真正删除。 */
    fun removeUserPlugin(settings: SettingsStore, id: String): Boolean {
        val before = userPlugins(settings)
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        save(settings, after)
        return true
    }

    private fun save(settings: SettingsStore, list: List<PluginInfo>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("desc", it.desc)) }
        settings.setUserPluginsJson(arr.toString())
    }

    fun isEnabled(settings: SettingsStore, id: String): Boolean = settings.pluginEnabled(id)

    /** 指令路由是否允许命中：插件存在且开关开启。 */
    fun routeAllowed(settings: SettingsStore, id: String): Boolean =
        plugins.any { it.id == id } && settings.pluginEnabled(id)
}
