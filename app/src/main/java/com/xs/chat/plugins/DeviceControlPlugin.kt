package com.xs.chat.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.wirelessdebug.service.AdbShellController
import com.wirelessdebug.service.RootController
import com.wirelessdebug.service.ShizukuController
import com.wirelessdebug.service.ShizukuDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 设备控制插件（类 Codex 电脑版）：解析自然语言指令直接操控手机。
 * 通道优先级：Root（uid 0 最高权限）→ 无线调试 shell → Shizuku。
 * 支持：打开应用 / 点击 / 滑动 / 输入 / 按键 / 读屏 / 通知栏 / 锁屏。
 */
object DeviceControlPlugin {

    suspend fun execute(context: Context, instruction: String): String = withContext(Dispatchers.IO) {
        // 非 root 用户：无线调试配对过但连接断开时自动重连（ensureConnected 兜底），Shizuku 未授权则请求
        val root = RootController.canUseRoot()
        val adb = if (!root && !AdbShellController.isConnected()) AdbShellController.ensureConnected()
        else AdbShellController.isConnected()
        val shizuku = if (!root && !adb && !ShizukuController.hasPermission()) {
            ShizukuController.requestPermission()
            ShizukuController.hasPermission()
        } else ShizukuController.hasPermission()
        if (!root && !adb && !shizuku) {
            "❌ 当前无可用控制通道。请开启：① Root 权限（已 root 设备）② 设置页「无线调试」配对 ③ Shizuku 授权。"
        } else {
            runCatching { parseAndRun(context, instruction.trim()) }
                .getOrElse { e -> "❌ 设备控制失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun parseAndRun(context: Context, text: String): String {
        // 去掉「请帮我/帮我/请」等礼貌前缀，统一指令格式
        val lower = text.lowercase(Locale.ROOT)
            .replace(Regex("^(请帮我|帮我|请|麻烦)"), "")

        Regex("^(打开|启动|开启|open|launch)\\s*(.+)$").find(lower)?.let {
            return "✅ " + openApp(context, it.groupValues[2])
        }
        Regex("^(点击|点一下|点按|点|tap|click)\\s*\\(?\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.tap(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^(点击|点一下|点按|点|tap|click)\\s*(.+)$").find(lower)?.let {
            val target = it.groupValues[2].trim('"', '\u201C', '\u201D', ' ', '「', '」')
            return "✅ " + ShizukuDevice.tapText(target)
        }
        Regex("^(滑动|swipe)\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)\\s*[\\s,，]+\\s*(\\d+)\\s*[\\s,，]\\s*(\\d+)").find(lower)?.let {
            return "✅ " + ShizukuDevice.swipe(
                it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                it.groupValues[4].toInt(), it.groupValues[5].toInt(), 300L
            )
        }
        if (lower.contains("上滑") || lower.contains("向上滑")) return "✅ " + swipeDir("up")
        if (lower.contains("下滑") || lower.contains("向下滑")) return "✅ " + swipeDir("down")
        if (lower.contains("左滑") || lower.contains("向左滑")) return "✅ " + swipeDir("left")
        if (lower.contains("右滑") || lower.contains("向右滑")) return "✅ " + swipeDir("right")

        Regex("^(输入文字|打字|输入|type|input)\\s*(.+)$").find(lower)?.let {
            return "✅ " + inputText(context, it.groupValues[2])
        }

        if (lower.contains("返回") || lower == "back") return "✅ " + ShizukuDevice.back()
        if (lower.contains("主页") || lower.contains("首页") || lower == "home") return "✅ " + ShizukuDevice.home()
        if (lower.contains("最近任务")) return "✅ " + ShizukuDevice.recents()
        if (lower.contains("锁屏") || lower.contains("熄屏")) return "✅ " + ShizukuDevice.keyEvent("26")
        if (lower.contains("音量加") || lower.contains("音量+")) return "✅ " + ShizukuDevice.keyEvent("24")
        if (lower.contains("音量减") || lower.contains("音量-")) return "✅ " + ShizukuDevice.keyEvent("25")

        if (lower.contains("读屏") || lower.contains("看看屏幕") || lower.contains("查看屏幕") ||
            lower.contains("屏幕内容") || lower.contains("屏幕上") || lower.contains("read screen") ||
            lower.contains("readscreen")) {
            return "📱 当前屏幕：\n" + ShizukuDevice.screenDump()
        }
        if (lower.contains("通知栏") || lower.contains("下拉通知")) return "✅ " + ShizukuDevice.notifications()

        return "❌ 无法识别指令：$text\n支持：打开应用 / 点击(x,y)或\"点一下\"文本 / 上滑下滑左滑右滑 / 输入文本 / 返回主页最近任务 / 读屏 / 通知栏 / 锁屏"
    }

    private fun swipeDir(dir: String): String {
        val size = ShizukuDevice.screenSize()
        val wh = size.split("x")
        if (wh.size != 2) return "获取屏幕尺寸失败"
        val w = wh[0].trim().toIntOrNull() ?: 1080
        val h = wh[1].trim().toIntOrNull() ?: 2400
        return when (dir) {
            "up" -> ShizukuDevice.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.2).toInt(), 300L)
            "down" -> ShizukuDevice.swipe(w / 2, (h * 0.2).toInt(), w / 2, (h * 0.8).toInt(), 300L)
            "left" -> ShizukuDevice.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 300L)
            else -> ShizukuDevice.swipe((w * 0.15).toInt(), h / 2, (w * 0.85).toInt(), h / 2, 300L)
        }
    }

    /** 输入文本：ASCII 直接 input text；含中文等非 ASCII 时走剪贴板 + 粘贴键。 */
    private fun inputText(context: Context, text: String): String {
        if (text.isEmpty()) return "输入内容为空"
        return if (text.all { it.code <= 127 }) {
            ShizukuDevice.inputText(text)
        } else {
            val ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("xs-device-input", text))
                true
            }.getOrDefault(false)
            if (!ok) return "非 ASCII 文本需要剪贴板粘贴，但设置剪贴板失败"
            ShizukuDevice.keyEvent("279") // KEYCODE_PASTE
        }
    }

    /** 打开应用：先查别名表，再按启动时缓存的全量应用索引解析（支持「打开抖音搜索」这类带动作词指令），最后按包名直开。 */
    private fun openApp(context: Context, query: String): String {
        val q = query.trim()
        ALIASES[q]?.let { return ShizukuDevice.openApp(it) }
        AppIndexPlugin.find(context, q)?.let { return ShizukuDevice.openApp(it) }
        if (Regex("^[a-z0-9_.]{3,}$").matches(q)) return ShizukuDevice.openApp(q)
        return "未找到应用：$q"
    }

    private val ALIASES = mapOf(
        "设置" to "com.android.settings", "相机" to "com.android.camera",
        "图库" to "com.android.gallery3d", "相册" to "com.android.gallery3d",
        "浏览器" to "com.android.browser", "文件管理" to "com.android.documentsui",
        "文件" to "com.android.documentsui", "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq", "抖音" to "com.ss.android.ugc.aweme",
        "支付宝" to "com.eg.android.AlipayGphone", "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall", "美团" to "com.sankuai.meituan",
        "哔哩哔哩" to "tv.danmaku.bili", "b站" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs", "微博" to "com.sina.weibo",
        "网易云音乐" to "com.netease.cloudmusic", "快手" to "com.smile.gifmaker",
        "高德地图" to "com.autonavi.minimap", "百度地图" to "com.baidu.BaiduMap",
        "计算器" to "com.android.calculator2", "时钟" to "com.android.deskclock",
        "日历" to "com.android.calendar", "联系人" to "com.android.contacts",
        "电话" to "com.android.dialer", "短信" to "com.android.mms",
        "应用商店" to "com.android.vending", "play商店" to "com.android.vending"
    )
}
