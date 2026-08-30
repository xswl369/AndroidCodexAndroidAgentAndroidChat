package com.xs.chat.sandbox

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 轻量沙盒（与 Codex 沙盒同理念）：
 * 开启后拦截破坏性 shell 命令与越权写路径，仅放行只读/控制类命令。
 * 拦截记录保留在内存环形缓冲，可在设置页查看。
 */
object Sandbox {
    private const val MAX_LOG = 20
    val log = MutableStateFlow<List<String>>(emptyList())

    @Volatile var enabled: Boolean = true

    /** 命令拦截：返回 null 放行，否则返回拒绝原因。 */
    fun intercept(command: String): String? {
        if (!enabled) return null
        val c = command.trim()
        if (c.isEmpty()) return "空命令"
        val lower = c.lowercase()
        if (SU_PATTERN.containsMatchIn(lower)) return "沙盒拦截：禁止提权命令 su"
        for (bad in DANGEROUS) {
            if (lower.contains(bad)) return "沙盒拦截：命令含危险操作「$bad」"
        }
        return null
    }

    /** 写路径限制：仅允许应用私有目录与用户文件目录。返回 null 放行。 */
    fun interceptWritePath(path: String): String? {
        if (!enabled) return null
        val p = path.trim().trim('\'').trim('"')
        if (p.isEmpty()) return null
        val norm = p.replace('\\', '/')
        val allowed = listOf(
            "/data/user/0/com.xs.chat/",
            "/data/data/com.xs.chat/",
            "/sdcard/",
            "/storage/emulated/0/",
            "/mnt/sdcard/"
        )
        if (allowed.any { norm.startsWith(it) }) return null
        return "沙盒拦截：禁止写入系统路径「$path」"
    }

    fun record(reason: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cur = log.value
        log.value = (cur + "$stamp $reason").takeLast(MAX_LOG)
    }

    fun clearLog() {
        log.value = emptyList()
    }

    private val SU_PATTERN = Regex("""(^|[\s;&|()])su([\s;&|()]|$)""")

    // 子串匹配（小写）；`su` 用词边界正则单独处理避免误伤
    private val DANGEROUS = listOf(
        "rm -rf", "rm -fr", "rm -r -f",
        "mkfs", "mke2fs", "format", "fdisk", "parted", "wipe",
        "dd if=", "fastboot", "flash",
        "reboot", "shutdown", "halt", "poweroff",
        "killall", "pkill", "kill -9",
        "pm uninstall", "pm clear", "pm disable", "pm enable",
        "settings put global", "settings put secure", "settings delete",
        "setprop", "svc wifi", "svc data", "svc bluetooth", "svc power", "svc usb",
        "mount", "umount", "insmod", "iptables", "ip6tables", "ifconfig",
        "chmod", "chown",
        "> /sys/", ">> /sys/", "> /proc/", ">> /proc/", "> /dev/", ">> /dev/",
        "curl -o /", "wget -o /", "wget -O /"
    )
}
