package com.xs.chat

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.offlinevoice.input.VoskEngine
import com.wirelessdebug.WdbContext
import com.wirelessdebug.service.RootController
import com.xs.chat.data.SettingsStore
import com.xs.chat.mcp.McpServer
import com.xs.chat.plugins.AppIndexPlugin
import com.xs.chat.sandbox.Sandbox
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局应用：捕获未处理异常，记录崩溃日志并按需重启，
 * 避免用户直接看到闪退白屏；连续崩溃（3 秒内再次崩溃）时停止重启防止死循环。
 */
class XSApp : Application() {

    private var lastCrashAt = 0L

    override fun onCreate() {
        super.onCreate()
        // 无线调试组件上下文初始化
        WdbContext.init(this)
        // 沙盒状态与 MCP 服务按设置启动
        val settings = SettingsStore(this)
        Sandbox.enabled = settings.sandboxEnabled
        RootController.enabled = settings.rootControlEnabled
        McpServer.appContext = this
        if (settings.mcpEnabled) {
            Thread { McpServer.start(settings.mcpPort) }.start()
        }
        // 预热离线语音模型（后台解压）：通话接通时零延时
        Thread {
            runCatching { VoskEngine.ensureModel(applicationContext, "vosk-model-small-cn", {}, {}) }
        }.start()
        // 预热 root 检测：后台探测 su 可用性，避免首次设备控制卡顿
        if (RootController.enabled) {
            Thread { RootController.isRooted() }.start()
        }
        // 应用索引：启动自动枚举全部已安装应用（供「打开应用」即时解析）
        Thread {
            runCatching { AppIndexPlugin.refresh(applicationContext) }
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            val now = System.currentTimeMillis()
            val restart = now - lastCrashAt > 3000
            lastCrashAt = now
            writeCrashLog(throwable)
            if (restart) scheduleRestart()
            // 链式交给之前的处理器（如系统默认），再结束进程
            if (previous != null && previous !== Thread.getDefaultUncaughtExceptionHandler()) {
                runCatching { previous.uncaughtException(thread, throwable) }
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "==== $stamp ====\n${throwable}\n${sw}\n"
            val logFile = File(filesDir, "crash_log.txt")
            // 日志上限 512KB，防止长期运行磁盘膨胀
            if (logFile.exists() && logFile.length() > 512 * 1024) logFile.delete()
            FileWriter(logFile, true).use { it.write(entry) }
        }
    }

    private fun scheduleRestart() {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@runCatching
            val pending = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 1200, pending)
        }
    }
}
