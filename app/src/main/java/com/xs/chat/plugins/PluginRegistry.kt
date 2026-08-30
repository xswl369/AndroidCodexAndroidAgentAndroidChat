package com.xs.chat.plugins

import com.xs.chat.data.SettingsStore

/**
 * 内置插件注册表：新增插件时在此追加一项，即可出现在设置页「插件」列表中
 * 并受开关控制（新插件默认启用）。AI 写完插件代码后注册到这里即可接入。
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

    fun isEnabled(settings: SettingsStore, id: String): Boolean = settings.pluginEnabled(id)

    /** 指令路由是否允许命中：插件存在且开关开启。 */
    fun routeAllowed(settings: SettingsStore, id: String): Boolean =
        plugins.any { it.id == id } && settings.pluginEnabled(id)
}