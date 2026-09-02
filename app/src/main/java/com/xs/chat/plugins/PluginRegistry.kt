package com.xs.chat.plugins

import com.xs.chat.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 内置插件注册表：列表 = 内置插件（代码注册，不可删除）+ 用户自建插件（插件管理页添加/删除，持久化）。
 * 用户自建插件为「脚本上传」形态：上传 .sh/.py/.js/.lua 脚本本体，由 [ScriptRunner] 实际执行，
 * 依赖列表（Python pip）运行前自动安装。
 */
object PluginRegistry {

    data class PluginInfo(
        val id: String,
        val name: String,
        val desc: String,
        /** AI 自动生成的触发指令与使用说明（添加插件后由模型完善）。 */
        val usage: String = "",
        /** 脚本文件名（存放于 filesDir/script_plugins/<id>/，null 表示旧版描述型插件）。 */
        val scriptFile: String? = null,
        /** 脚本语言：sh / py / js / lua。 */
        val lang: String? = null,
        /** 依赖说明：Python 为空格/换行分隔的 pip 包名，运行前自动安装。 */
        val deps: String = ""
    ) {
        val isScript: Boolean get() = scriptFile != null
    }

    /** 全部内置插件（按需扩展：新增内置插件只需在此列表追加一项）。 */
    val plugins: List<PluginInfo> = listOf(
        PluginInfo("device_control", "设备控制", "聊天指令操控手机：打开应用 / 点击 / 滑动 / 输入 / 读屏 / 按键，复杂指令本地离线智能识别"),
        PluginInfo("web_search", "联网搜索", "实时联网搜索，返回网页结果标题、链接与摘要"),
        PluginInfo("file_edit", "文件修改", "AI 按指令修改文件，保留原格式，结果可预览并保存到下载目录"),
        PluginInfo("image_gen", "AI 生图", "文字 / 图片生成图片（需配置支持生图的模型）"),
        PluginInfo("video_gen", "AI 生视频", "文字 / 图片生成视频（需配置支持生视频的模型）"),
        PluginInfo("memory", "记忆插件", "跨会话记录操作与对话历史，可在设置中配置上限")
    )

    /** 全部插件 = 内置 + 用户自建。 */
    fun all(settings: SettingsStore): List<PluginInfo> = plugins + userPlugins(settings)

    /** 用户自建插件（插件管理页脚本上传，持久化在 SettingsStore）。 */
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
                else PluginInfo(
                    id = id, name = name, desc = o.optString("desc").trim(),
                    usage = o.optString("usage").trim(),
                    scriptFile = o.optString("file").trim().ifBlank { null },
                    lang = o.optString("lang").trim().ifBlank { null },
                    deps = o.optString("deps").trim()
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 按文件名识别脚本语言：sh / py / js / lua，不支持返回 null。 */
    fun langFor(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "sh" -> "sh"
            "py" -> "py"
            "js" -> "js"
            "lua" -> "lua"
            "sql" -> "sql"
            else -> null
        }
    }

    fun langLabel(lang: String?): String = when (lang) {
        "sh" -> "Shell"
        "py" -> "Python"
        "js" -> "JavaScript"
        "lua" -> "Lua"
        "sql" -> "SQL"
        else -> ""
    }

    /** 添加旧版描述型用户插件（仅供兼容，新流程统一走脚本上传）。 */
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

    /**
     * 从名称/文件名生成插件 id（小写字母/数字/下划线 2-32 位），
     * 撞见内置或其他用户插件时自动追加 _2/_3…
     */
    private fun suggestId(settings: SettingsStore, name: String, fileName: String): String {
        val base = (name + fileName.substringBeforeLast('.'))
            .lowercase(Locale.ROOT)
            .map { c -> if (c.isLetterOrDigit() || c == '_') c else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("_")
            .trim('_')
            .take(32)
            .ifBlank { "plugin" }
        var id = base
        var suffix = 2
        val taken = plugins.map { it.id }.toMutableSet()
        taken.addAll(userPlugins(settings).map { it.id })
        while (id in taken) id = base.take(28) + "_" + suffix++
        return id
    }

    /**
     * 添加脚本型用户插件（只登记元数据，脚本文件由 [ScriptStore] 落盘）。
     * name 为空/语言不支持返回 null，成功返回生成的插件 id。
     */
    fun addScriptPlugin(settings: SettingsStore, name: String, desc: String, fileName: String, deps: String): String? {
        val lang = langFor(fileName) ?: return null
        val pname = name.trim().take(20).ifBlank { fileName.substringBeforeLast('.').take(20) }
        val id = suggestId(settings, pname, fileName)
        userPlugins(settings).let { list ->
            save(settings, list + PluginInfo(
                id = id, name = pname, desc = desc.trim(),
                scriptFile = fileName, lang = lang, deps = deps.trim()
            ))
        }
        return id
    }

    /** 删除用户插件；内置插件不可删。返回是否真正删除。 */
    fun removeUserPlugin(settings: SettingsStore, id: String): Boolean {
        val before = userPlugins(settings)
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        save(settings, after)
        return true
    }

    /** 更新用户插件的 AI 完善内容（触发指令 / 使用说明）。 */
    fun updatePluginUsage(settings: SettingsStore, id: String, usage: String): Boolean {
        val list = userPlugins(settings).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(usage = usage.trim())
        save(settings, list)
        return true
    }

    private fun save(settings: SettingsStore, list: List<PluginInfo>) {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
                .put("id", p.id).put("name", p.name).put("desc", p.desc).put("usage", p.usage)
            if (p.scriptFile != null) {
                o.put("file", p.scriptFile).put("lang", p.lang).put("deps", p.deps)
            }
            arr.put(o)
        }
        settings.setUserPluginsJson(arr.toString())
    }

    fun isEnabled(settings: SettingsStore, id: String): Boolean = settings.pluginEnabled(id)

    /** 指令路由是否允许命中：插件存在且开关开启。 */
    fun routeAllowed(settings: SettingsStore, id: String): Boolean =
        plugins.any { it.id == id } && settings.pluginEnabled(id)
}
