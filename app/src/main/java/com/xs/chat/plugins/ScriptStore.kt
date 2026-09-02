package com.xs.chat.plugins

import android.content.Context
import android.net.Uri
import com.xs.chat.plugins.PluginRegistry.PluginInfo
import java.io.File

/**
 * 脚本插件文件存取：统一存放于应用私有目录 filesDir/script_plugins/<插件id>/，
 * 与其它插件共用同一套启停/删除管理，删除插件时连同脚本一并清理。
 */
object ScriptStore {

    fun dir(context: Context, pluginId: String): File =
        File(context.filesDir, "script_plugins" + File.separator + pluginId)

    /** 保存用户从系统文件选择器选中的脚本（SAF content Uri）。成功返回 null，否则返回错误原因。 */
    fun save(context: Context, plugin: PluginInfo, uri: Uri, displayName: String): String? {
        val dir = dir(context, plugin.id).apply { mkdirs() }
        val target = File(dir, displayName)
        return try {
            val input = context.contentResolver.openInputStream(uri)
            input?.use { ins ->
                target.outputStream().use { out -> ins.copyTo(out) }
            } ?: return "无法读取所选文件"
            if (!target.isFile || target.length() == 0L) {
                target.delete()
                return "脚本内容为空"
            }
            null
        } catch (e: Exception) {
            target.delete()
            "脚本保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 脚本绝对路径（不存在返回 null）。 */
    fun scriptFile(context: Context, plugin: PluginInfo): File? =
        plugin.scriptFile?.let { File(dir(context, plugin.id), it) }

    fun deleteDir(context: Context, pluginId: String) {
        dir(context, pluginId).let { d -> if (d.exists()) d.deleteRecursively() }
    }
}
