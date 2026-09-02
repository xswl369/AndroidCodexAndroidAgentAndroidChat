package com.xs.chat.plugins

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wirelessdebug.service.AdbShellController
import com.wirelessdebug.service.RootController
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置 Python 运行时（Shizuku 全内置模式）：
 * 不安装独立 Termux App、不注册 com.termux 包、不出现外部应用入口。
 * assets/termux/runtime.tar.gz 内嵌完整 Python 3.14 运行时（解释器+标准库，约 12MB）。
 * 首次使用时自解压到 /data/local/tmp/xsrt；该目录在部分 ROM 未被禁止 nosuid/exec，
 * shell 用户可直接执行内嵌 ELF（已实测）。Root 通道与免 Root 无线调试共用同一套运行时。
 */
object TermuxManager {

    private const val TAG = "TermuxManager"
    private const val RT_DIR = "/data/local/tmp/xsrt"
    private const val RT_PY = "$RT_DIR/bin/python3"
    private const val ASSET_GZ = "termux/runtime.rtbin"
    private const val PREF = "termux_runtime"
    private const val AUTO_DONE = "auto_ready_v2"

    data class State(val pythonReady: Boolean, val haveRoot: Boolean, val detail: String)

    // ---------------------------------------------------------------- 通道：0 无 / 1 root / 2 adb-su / 3 adb-shell(免Root)
    private var chMode = 0
    @Volatile private var chAt = 0L

    private fun resolveChannel(): Int {
        if (RootController.canUseRoot()) return 1
        if (!AdbShellController.isConnected()) return 0
        val id = AdbShellController.exec("id").output
        return if (id.contains("uid=0")) 2 else 3
    }

    /** 40s 缓存通道；启动预热只走缓存路径，不触发 mDNS 扫描。 */
    fun channelFast(): Int {
        val now = System.currentTimeMillis()
        if (chMode != 0 && now - chAt < 40_000) return chMode
        chAt = now
        chMode = resolveChannel()
        return chMode
    }

    private fun execPriv(cmd: String): Pair<Boolean, String> {
        val m = channelFast()
        if (m == 0) return false to "no channel"
        val b64 = Base64.encodeToString(cmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val run = "echo " + b64 + " | base64 -d | sh"
        return when (m) {
            1 -> { val r = RootController.exec(run); r.ok to r.output }
            2 -> { val r = AdbShellController.exec("su -c '" + run + "'"); r.ok to r.output }
            else -> { val r = AdbShellController.exec(run); r.ok to r.output }
        }
    }

    // ---------------------------------------------------------------- 就绪探测（8s 缓存，绝不阻塞主线程）
    @Volatile private var pyCache: Boolean? = null
    @Volatile private var pyAt = 0L

    fun pythonReady(): Boolean {
        val now = System.currentTimeMillis()
        if (pyCache != null && now - pyAt < 5_000) return pyCache!!
        val res = execPriv("ls -ld $RT_PY 2>&1")
        val p = res.first && !res.second.contains("No such")
        pyCache = p; pyAt = now
        return p
    }

    fun state(ctx: Context): State {
        val root = RootController.canUseRoot()
        val py = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) (pyCache ?: false) else pythonReady()
        val detail = buildString {
            append(if (py) "内置 Python 运行时已就绪" else "内置 Python 运行时未部署")
            append(" · ").append(if (root) "Root" else "免Root")
            if (!py) append("（一键就绪自动部署，无需安装 Termux）")
        }
        return State(py, root, detail.toString())
    }

    // ---------------------------------------------------------------- 运行时部署
    private fun assetGz(ctx: Context): File {
        val dir = File(ctx.filesDir, "termux").apply { mkdirs() }
        val dst = File(dir, "runtime.tar.gz")
        // gzip 魔数校验：防止历史脏缓存/AGP 解密产物被误用
        if (dst.exists() && dst.length() > 1_000_000 && isGzip(dst)) return dst
        FileOutputStream(dst).use { out -> out.write(runtimeAssetBytes(ctx)) }
        return dst
    }

    private fun isGzip(f: File): Boolean = runCatching {
        val head = f.readBytes().take(2)
        head.size == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
    }.getOrDefault(false)

    private fun runtimeAssetBytes(ctx: Context): ByteArray =
        ctx.assets.open(ASSET_GZ).use { it.readBytes() }

    /** 通过当前通道把内嵌运行时部署到 /data/local/tmp/xsrt。 */
    private fun deploy(ctx: Context): Pair<Boolean, String> {
        return try {
            val m = channelFast()
            if (m == 0) {
                false to "no-channel"
            } else if (execPriv("ls -ld $RT_PY 2>&1").let { it.first && !it.second.contains("No such") }) {
                true to "deploy-hot"
            } else if (m == 1) {
                // Root: copy asset archive from app-private dir, extract, own by shell
                val src = assetGz(ctx).absolutePath
                val r = RootController.exec(
                    "cp $src /data/local/tmp/runtime.tar.gz && tar -xzf /data/local/tmp/runtime.tar.gz -C /data/local/tmp && rm -f /data/local/tmp/runtime.tar.gz"
                )
                if (!r.ok) false to "解压失败：" + r.output.take(200) else {
                    RootController.exec("chown -R 2000:2000 /data/local/tmp/xsrt; chmod -R a+rX /data/local/tmp/xsrt; chmod 755 /data/local/tmp/xsrt/bin/*")
                    true to "deploy-root-ok"
                }
            } else {
                // Free-root: stage archive to external dir (shell-readable), then adb shell copies+extracts (shell-owned)
                val ext = File(ctx.getExternalFilesDir("xsrt"), "runtime.tar.gz").apply { parentFile?.mkdirs() }
                runtimeAssetBytes(ctx).let { b -> FileOutputStream(ext).use { out -> out.write(b) } }
                val e = AdbShellController.exec(
                    "mkdir -p /data/local/tmp && cp " + ext.absolutePath + " /data/local/tmp/runtime.tar.gz && " +
                        "cd /data/local/tmp && tar -xzf runtime.tar.gz && rm -f runtime.tar.gz && chmod -R a+rX /data/local/tmp/xsrt && chmod 755 /data/local/tmp/xsrt/bin/*"
                )
                if (!e.ok) false to "无线部署失败：" + e.output.take(8) else true to "deploy-adb-ok"
            }
        } catch (t: Throwable) {
            false to ("部署异常：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** 自检：内嵌 Python 可执行且标准库可导入（退出码 0 即通过）。 */
    private fun selfCheck(): Boolean {
        val r = execPriv(
            "export LD_LIBRARY_PATH=$RT_DIR/lib; export PYTHONHOME=$RT_DIR; export PYTHONDONTWRITEBYTECODE=1; " +
                "$RT_PY -c 'import sys;sys.exit(0)' 2>&1"
        )
        return r.first
    }

    // ---------------------------------------------------------------- Python 执行
    /** 以内嵌 Python 执行脚本：内容 base64 落盘，规避 su/sh 引号嵌套；输出与退出码回读。 */
    fun runPython(scriptPath: String, args: String, workDir: String? = null): Pair<Boolean, String> {
        if (!pythonReady()) return false to "内置 Python 未就绪，请先点「一键就绪」"
        if (channelFast() == 0) return false to "无特权通道（请开启 Root 或无线调试）"
        val code = try { java.io.File(scriptPath).readText() } catch (e: Exception) { return false to "读取脚本失败：" + e.message }
        val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val outFile = "/data/local/tmp/xs_out.txt"
        val cmd = buildString {
            append("cd ").append(workDir ?: RT_DIR).append(" 2>/dev/null; ")
            append("export LD_LIBRARY_PATH=$RT_DIR/lib PYTHONHOME=$RT_DIR ")
            append("export PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ")
            append("echo ").append(b64).append(" | base64 -d > /data/local/tmp/xs_run.py; ")
            append(RT_PY).append(" /data/local/tmp/xs_run.py")
            if (args.isNotBlank()) append(" ").append(args.trim())
            append(" > ").append(outFile).append(" 2>&1; echo XS_RC:$? >> ").append(outFile)
        }
        execPriv(cmd)
        val body = execPriv("cat $outFile 2>/dev/null; rm -f /data/local/tmp/xs_run.py $outFile").second
        val trimmed = body.trim()
        val exit = Regex("XS_RC:(\\d+)\\s*$").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val text = trimmed.replace(Regex("\\s*XS_RC:\\d+\\s*$"), "").trim()
        return when {
            exit == 0 -> true to text
            text.isNotBlank() -> false to text
            else -> false to "脚本异常退出（code $exit）"
        }
    }

    // ---------------------------------------------------------------- 一键就绪 / 启动预热
    suspend fun ensureReady(ctx: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (pythonReady()) return@withContext true to "内置 Python 3.14 已就绪"
        // 用户主动操作时允许触发一次无线调试连接（自动启动预热不走这里，避免 mDNS 抢冷启动）
        if (channelFast() == 0 && AdbShellController.ensureConnected()) {
            chMode = 0
            chAt = 0L
        }
        val (deployed, msg) = deploy(ctx)
        if (deployed && selfCheck()) true to "内置 Python 3.14 已就绪"
        else false to userFacing(msg)
    }

    private fun userFacing(msg: String): String = when {
        msg.startsWith("no-channel") -> "无可用通道：请开启 Root，或先连接无线调试后再试"
        else -> "部署未完成：" + msg
    }

    /** App 启动后台预热：仅走缓存通道，未就绪且无通道时静默跳过，等用户进设置页一键启动。 */
    fun autoPrepare(ctx: Context): String {
        Log.i(TAG, "autoPrepare called")
        if (pythonReady()) { prefs(ctx).edit().putBoolean(AUTO_DONE, true).apply(); return "ready" }
        if (prefs(ctx).getBoolean(AUTO_DONE, false)) { Log.i(TAG, "auto skip done-once"); return "skip" }
        if (channelFast() == 0) { Log.i(TAG, "auto no-channel"); return "no-channel" }
        val (deployed, msg) = deploy(ctx)
        if (deployed && selfCheck()) {
            prefs(ctx).edit().putBoolean(AUTO_DONE, true).apply()
            Log.i(TAG, "auto ready")
            return "ready"
        }
        Log.i(TAG, "auto deferred: $msg")
        return "deferred"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 清除已部署的运行时（重新部署用）。返回结果说明。 */
    fun resetRuntime(ctx: Context): String {
        pyCache = null
        val (ok, out) = execPriv("rm -rf $RT_DIR")
        prefs(ctx).edit().remove(AUTO_DONE).apply()
        return if (ok) "已清除，可重新部署" else "清除失败：" + out
    }
}
