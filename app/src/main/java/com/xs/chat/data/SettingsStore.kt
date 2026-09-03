package com.xs.chat.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("xs_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, "").orEmpty().trim()
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "").orEmpty().trim()
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var temperature: Float
        get() = sp.getFloat(KEY_TEMP, 0.7f)
        set(value) = sp.edit().putFloat(KEY_TEMP, value).apply()

    var systemPrompt: String
        get() = sp.getString(KEY_SYSTEM, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SYSTEM, value).apply()

    var lastModelId: String
        get() = sp.getString(KEY_LAST_MODEL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_MODEL, value).apply()

    var darkMode: String
        get() = sp.getString(KEY_DARK_MODE, "system").orEmpty()
        set(value) = sp.edit().putString(KEY_DARK_MODE, value).apply()

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "zh").orEmpty()
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    var imageSize: String
        get() = sp.getString(KEY_IMAGE_SIZE, "1024x1024").orEmpty()
        set(value) = sp.edit().putString(KEY_IMAGE_SIZE, value.trim()).apply()

    var videoResolution: String
        get() = sp.getString(KEY_VIDEO_RESOLUTION, "720P").orEmpty()
        set(value) = sp.edit().putString(KEY_VIDEO_RESOLUTION, value.trim()).apply()

    var videoDuration: String
        get() = sp.getString(KEY_VIDEO_DURATION, "5").orEmpty()
        set(value) = sp.edit().putString(KEY_VIDEO_DURATION, value.trim()).apply()

    var memoryLimit: Int
        get() = sp.getInt(KEY_MEMORY_LIMIT, 2000)
        set(value) = sp.edit().putInt(KEY_MEMORY_LIMIT, value.coerceIn(10, 50000)).apply()

    var sandboxEnabled: Boolean
        get() = sp.getBoolean(KEY_SANDBOX_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SANDBOX_ENABLED, value).apply()

    /** Root 最高权限控制开关（默认开启：已 root 设备直接以 uid 0 操控手机）。 */
    var rootControlEnabled: Boolean
        get() = sp.getBoolean(KEY_ROOT_CONTROL_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ROOT_CONTROL_ENABLED, value).apply()

    /** 内置插件开关（默认启用；新增插件注册到 PluginRegistry 后在此持久化）。 */
    fun pluginEnabled(pluginId: String): Boolean = sp.getBoolean(KEY_PLUGIN_PREFIX + pluginId, true)

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        sp.edit().putBoolean(KEY_PLUGIN_PREFIX + pluginId, enabled).apply()
    }

    /** 联网搜索模式（元宝同款）：0 关闭 / 1 自动 / 2 总是开启，默认为自动。 */
    var webSearchMode: Int
        get() = sp.getInt(KEY_WEB_SEARCH_MODE, 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_WEB_SEARCH_MODE, value.coerceIn(0, 2)).apply()

    /** 思考深度（Codex 同款）：auto / low / medium / high / xhigh，默认 auto（不传参，由服务端决定）。 */
    var reasoningEffort: String
        get() = sp.getString(KEY_REASONING_EFFORT, "auto").orEmpty()
        set(value) = sp.edit().putString(KEY_REASONING_EFFORT, value).apply()

    /** 用户自建插件列表（JSON 数组 [{id,name,desc}]，插件管理页添加/删除）。 */
    fun userPluginsJson(): String = sp.getString(KEY_USER_PLUGINS, "").orEmpty()

    fun setUserPluginsJson(json: String) {
        sp.edit().putString(KEY_USER_PLUGINS, json).apply()
    }

    var mcpEnabled: Boolean
        get() = sp.getBoolean(KEY_MCP_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_MCP_ENABLED, value).apply()

    var mcpPort: Int
        get() = sp.getInt(KEY_MCP_PORT, 8765)
        set(value) = sp.edit().putInt(KEY_MCP_PORT, value.coerceIn(1024, 65535)).apply()

    var callRoleId: String
        get() = sp.getString(KEY_CALL_ROLE_ID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_CALL_ROLE_ID, value).apply()

    // 方言语音引擎：阿里云百炼(DashScope) CosyVoice API Key + CosyVoice 开源自部署地址（二者至少其一）
    var dialectKey: String
        get() = sp.getString(KEY_DIALECT_KEY, "").orEmpty().trim()
        set(value) = sp.edit().putString(KEY_DIALECT_KEY, value.trim()).apply()

    var dialectUrl: String
        get() = sp.getString(KEY_DIALECT_URL, "").orEmpty().trim()
        set(value) = sp.edit().putString(KEY_DIALECT_URL, value.trim()).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_TEMP = "temperature"
        const val KEY_SYSTEM = "system_prompt"
        const val KEY_LAST_MODEL = "last_model_id"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_LANGUAGE = "language"
        const val KEY_IMAGE_SIZE = "image_size"
        const val KEY_VIDEO_RESOLUTION = "video_resolution"
        const val KEY_VIDEO_DURATION = "video_duration"
        const val KEY_MEMORY_LIMIT = "memory_limit"
        const val KEY_SANDBOX_ENABLED = "sandbox_enabled"
        const val KEY_ROOT_CONTROL_ENABLED = "root_control_enabled"
        const val KEY_PLUGIN_PREFIX = "plugin_"
        const val KEY_USER_PLUGINS = "user_plugins"
        const val KEY_WEB_SEARCH_MODE = "web_search_mode"
        const val KEY_REASONING_EFFORT = "reasoning_effort"
        const val KEY_MCP_ENABLED = "mcp_enabled"
        const val KEY_MCP_PORT = "mcp_port"
        const val KEY_CALL_ROLE_ID = "call_role_id"
        const val KEY_DIALECT_KEY = "dialect_key"
        const val KEY_DIALECT_URL = "dialect_url"
    }
}
