package com.xs.chat.wireless

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.wirelessdebug.PairState
import com.wirelessdebug.WdbContext
import com.wirelessdebug.service.AdbPairClient
import com.wirelessdebug.service.AdbShellController
import com.wirelessdebug.service.PairCaptureService
import com.wirelessdebug.service.ScreenOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 无线调试一键配对协调器（换机/撤销授权后重新配对）。
 * 由于 Android 安全限制，配对码无法后台读取，必须：
 * ① 打开「使用配对码配对设备」页 → ② 用户允许一次屏幕录制 → ③ OCR 识别 6 位码自动配对。
 * 配对成功后内置 adb key 即被新手机授权，shell 控制通道自动建立。
 */
object AutoPair {

    /** 直达系统「使用配对码配对设备」页；ROM 不支持时逐级回退到无线调试/开发者选项。 */
    fun openPairingPage(context: Context) {
        val intents = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_PAIRING_SETTINGS"), // API 30+，直达配对码页
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment", "com.android.settings.development.WirelessDebuggingFragment"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
    }

    /** 携带录屏授权结果启动前台截屏服务（Android 14+ 必须先起前台服务再取 MediaProjection）。 */
    fun startCapture(context: Context, resultCode: Int, data: Intent) {
        val svc = Intent(context, PairCaptureService::class.java)
            .putExtra("resultCode", resultCode)
            .putExtra("data", data)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, svc) }
    }

    /**
     * 一键自动配对：发现配对端口 + 截屏 OCR 识别 6 位配对码 + 自动配对，90 秒超时。
     * 必须在用户已授权录屏并停留（或即将停留）在配对码页面时调用。
     */
    suspend fun runAutoPair(context: Context, onStatus: (String) -> Unit): String {
        WdbContext.init(context)
        // 离开 Compose 帧时钟：App 后台时帧停止会冻结轮询，改在时间基准的 Default 调度器运行
        return withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 90_000
            var lastError = ""
            var pairError = ""
            val tryCount = mutableMapOf<String, Int>()
            var stableCode: String? = null
            while (System.currentTimeMillis() < deadline) {
                val result = withContext(Dispatchers.IO) {
                    val bmp = MediaProjectionCapture.capture()
                    val ocrText = if (bmp != null) ScreenOcr.recognize(bmp) else null
                    // 配对弹窗可见时，IP:端口与 6 位码都从 OCR 文本直读，不依赖缓慢的 mDNS/扫描
                    val port = extractPairPort(ocrText) ?: -1
                    val code = extractPairCode(ocrText)
                    diag(context, "port=$port code=$code captureOk=${bmp != null} ocr=${ocrText?.replace("\n", " | ")?.take(160)}")
                    Triple(port, code, bmp != null)
                }
                val (port, code, captureOk) = result
                // 稳定性闸门：同一码连续两帧一致才尝试（首帧码常为 OCR 垃圾识别，直接试会浪费配对机会）
                val stable = code != null && code == stableCode
                stableCode = code
                // 仅在端口与码均由 OCR 从配对弹窗直接读出且码已稳定时尝试配对
                if (port > 0 && code != null && stable && (tryCount[code] ?: 0) < 2) {
                    tryCount[code] = (tryCount[code] ?: 0) + 1
                    val r = withContext(Dispatchers.IO) { AdbPairClient.pair("127.0.0.1", port, code) }
                    diag(context, "pair(127.0.0.1,$port,$code) ok=${r.ok} msg=${r.message}")
                    if (r.ok) {
                        PairState.markPaired(context)
                        AdbShellController.clearFailCooldown() // 配对成功立即清失败冷却，允许立刻重新扫描连接
                        stopCapture(context)
                        // shell 通道后台建立，不阻塞「配对成功」即时反馈
                        Thread({ runCatching { AdbShellController.ensureConnected() } }, "adb-ensure-conn").start()
                        return@withContext "配对成功！端口 $port，ADB 通道已建立"
                    }
                    pairError = "配对尝试失败：" + r.message
                    onStatus(pairError)
                } else {
                    lastError = when {
                        port <= 0 -> "未找到配对端口，请停留在「使用配对码配对设备」页面"
                        !captureOk -> "正在等待录屏授权生效…"
                        else -> if (code != null && !stable) "正在确认配对码…" else if (code != null) "正在校验 $code…" else "正在识别配对码…"
                    }
                    onStatus(lastError)
                }
                delay(if (code != null || port > 0) 400 else 500)
            }
            stopCapture(context)
            "配对超时：" + (pairError.ifBlank { lastError }).ifBlank { "请确认无线调试已开启并停留在配对页面" }
        }
    }

    /** 配对过程落盘（部分 ROM 屏蔽 logcat，落盘用于排查 OCR/握手问题）。 */
    private fun diag(context: Context, line: String) {
        runCatching {
            val f = java.io.File(context.filesDir, "pip_diag.txt")
            f.appendText(System.currentTimeMillis().toString() + " [AutoPair] " + line + "\n")
        }
    }

    fun stopCapture(context: Context) {
        runCatching { context.stopService(Intent(context, PairCaptureService::class.java)) }
    }

    /** 配对码 OCR 增强：字母误识别映射回数字（O→0/I→1 等），逐行优先匹配独立 6 位数字。 */
    private fun extractPairCode(text: String?): String? {
        if (text == null) return null
        val fixed = text.map { c ->
            when (c) {
                'O', 'o' -> '0'; 'I', 'l' -> '1'; 'Z' -> '2'; 'S', 's' -> '5'
                'B' -> '8'; 'G' -> '6'; 'T' -> '7'; else -> c
            }
        }.joinToString("")
        for (line in fixed.split("\n")) {
            Regex("""(?<!\d)\d{6}(?!\d)""").find(line)?.let { return it.value }
        }
        Regex("""(?<!\d)\d{6}(?!\d)""").find(fixed)?.let { return it.value }
        val compact = fixed.replace(Regex("""[^0-9]"""), "")
        return Regex("""\d{6}""").find(compact)?.value
    }

    /**
     * 从配对页 OCR 文本提取配对端口（IP:端口 格式，端口 4-5 位）。
     * 配对弹窗内「IP 地址和端口」位于配对码下方；弹窗背后可能透出主页的连接端口行
     * （如 192.168.21.8:37467 被误读为 3746），因此优先取「配对码所在行之后」的
     * IP:端口，兜底取最后一条。
     */
    private fun extractPairPort(text: String?): Int? {
        if (text == null) return null
        val fixed = text.map { c ->
            when (c) {
                'O', 'o' -> '0'; 'I', 'l' -> '1'; 'Z' -> '2'; 'S', 's' -> '5'
                'B' -> '8'; 'G' -> '6'; 'T' -> '7'; else -> c
            }
        }.joinToString("")
        val portRe = Regex("""\d{1,3}(?:\.\d{1,3}){3}:(\d{4,5})""")
        // ① 配对码所在行之后的第一条 IP:端口（弹窗内的配对端口）
        var codeIdx = -1
        val lines = fixed.split("\n")
        for ((i, l) in lines.withIndex()) {
            if (Regex("""(?<!\d)\d{6}(?!\d)""").containsMatchIn(l)) { codeIdx = i; break }
        }
        if (codeIdx >= 0) {
            for (i in codeIdx + 1 until lines.size) {
                portRe.find(lines[i])?.let { return it.groupValues[1].toIntOrNull() }
            }
        }
        // ② 兜底：最后一条 IP:端口（配对弹窗通常位于屏幕下方）
        return portRe.findAll(fixed).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }
}
