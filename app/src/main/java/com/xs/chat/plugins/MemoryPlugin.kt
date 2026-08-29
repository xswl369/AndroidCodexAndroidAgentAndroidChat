package com.xs.chat.plugins

import android.content.Context
import com.xs.chat.data.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 内置记忆插件：像 Codex 会话日志一样记录用户每次操作，
 * App 每次启动时自动读取之前的所有操作记录，供随时回顾。
 */
object MemoryPlugin {
    private const val FILE_NAME = "memory_log.txt"
    private const val DEFAULT_MAX_LINES = 2000
    private val io = Executors.newSingleThreadExecutor()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 追加一条操作记录：时间戳 | 动作 | 详情。 */
    fun log(context: Context, action: String, detail: String = "") {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$ts | $action${if (detail.isBlank()) "" else " | $detail"}"
        val maxLines = SettingsStore(context).memoryLimit.takeIf { it > 0 } ?: DEFAULT_MAX_LINES
        io.execute {
            runCatching {
                val f = file(context)
                val lines = if (f.exists()) f.readLines() else emptyList()
                (lines + line).takeLast(maxLines).also { f.writeText(it.joinToString("\n") + "\n") }
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
        io.execute { runCatching { file(context).delete() } }
    }
}
