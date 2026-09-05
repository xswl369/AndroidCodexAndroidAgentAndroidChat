package com.xs.chat.plugins

import android.content.Context
import com.xs.chat.data.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 内置记忆插件：像 Codex 会话日志一样记录用户每次操作，
 * App 每次启动时自动读取之前的所有操作记录，供随时回顾。
 */
object MemoryPlugin {
    private const val FILE_NAME = "memory_log.txt"
    private const val DEFAULT_MAX_LINES = 2000
    private val io = Executors.newSingleThreadExecutor()
    /** 内存操作日志变更监听：聊天气泡/设置页实时刷新，无需重启 App。 */
    private val listeners = CopyOnWriteArraySet<(List<String>) -> Unit>()

    fun addListener(listener: (List<String>) -> Unit) { listeners.add(listener) }

    fun removeListener(listener: (List<String>) -> Unit) { listeners.remove(listener) }

    private fun notify(newestFirst: List<String>) {
        listeners.forEach { runCatching { it(newestFirst) } }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 追加一条操作记录：时间戳 | 动作 | 详情 | 禁止查看截图。 */
    fun log(context: Context, action: String, detail: String = "") {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val detailPart = if (detail.isBlank()) "" else " | $detail"
        val line = "$ts | $action$detailPart | 禁止查看截图"
        val maxLines = SettingsStore(context).memoryLimit.takeIf { it > 0 } ?: DEFAULT_MAX_LINES
        io.execute {
            runCatching {
                val f = file(context)
                val lines = if (f.exists()) f.readLines() else emptyList()
                (lines + line).takeLast(maxLines).also { kept ->
                    f.writeText(kept.joinToString("\n") + "\n")
                    notify(kept.reversed())
                }
            }
        }
    }

    /** 读取全部操作记录（最新在前）。 */
    fun readAll(context: Context): List<String> = runCatching {
        val f = file(context)
        if (f.exists()) f.readLines().filter { it.isNotBlank() }.reversed() else emptyList()
    }.getOrDefault(emptyList())

    /** 清空操作记录。 */
    fun clear(context: Context) {
        io.execute {
            runCatching { file(context).delete() }
            notify(emptyList())
        }
    }
}
