package com.xs.chat.plugins

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wirelessdebug.service.AdbShellController
import com.wirelessdebug.service.RootController
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置 Python 运行时（元宝式「应用内直跑」）：
 * 不安装独立 Termux App、不注册 com.termux 包、无外部入口。
 * assets/termux/runtime.rtbin 内嵌完整 Python 3.14 运行时（解释器+标准库，约 12MB 压缩）。
 *
 * 执行策略（自动两级切换，零配置）：
 * 1. 本地直跑（默认）：启动后台把运行时解压到 filesDir/xsrt，直接用 ProcessBuilder exec
 *    解释器（LD_LIBRARY_PATH / PYTHONHOME 指向应用私有目录），无需 Root / 无线调试 / Shizuku；
 *    App 私有目录执行 ELF 已在多台设备实测通过。
 * 2. 通道兜底：数据分区禁止执行（较新 ROM）时回退 /data/local/tmp + Root/无线调试通道。
 */
object TermuxManager {

    private const val TAG = "TermuxManager"
    private const val LOCAL_DIR = "xsrt"                     // filesDir 下的本地运行时
    private const val RT_DIR = "/data/local/tmp/xsrt"        // 通道兜底目录
    private const val RT_PY = "$RT_DIR/bin/python3"
    private const val ASSET_GZ = "termux/runtime.rtbin"
    private const val PREF = "termux_runtime"
    private const val AUTO_DONE = "auto_ready_v2"
    private const val PY_TIMEOUT_MS = 180_000L
    private const val MAX_OUT = 512 * 1024

    data class State(val pythonReady: Boolean, val haveRoot: Boolean, val detail: String)

    @Volatile private var ctxCache: Context? = null

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

    // ---------------------------------------------------------------- 应用目录：本地直跑

    private fun localRoot(ctx: Context): File = File(ctx.filesDir, LOCAL_DIR)
    private fun localPy(ctx: Context): File = File(localRoot(ctx), "bin/python3")

    /** 本地已就绪：解释器存在于应用私有目录且可执行（禁止执行/越权时返回 false）。 */
    fun pythonLocalReady(ctx: Context): Boolean {
        val py = localPy(ctx)
        if (!py.isFile || !py.canRead()) return false
        return runCatching { py.canExecute() }.getOrDefault(false)
    }

    /** 首个入口缓存上下文，供无参 [pythonReady] 复用。 */
    private fun remember(ctx: Context) {
        if (ctxCache == null) ctxCache = ctx.applicationContext
    }

    // ---------------------------------------------------------------- 就绪探测（5s 缓存，绝不阻塞主线程）
    @Volatile private var pyCache: Boolean? = null
    @Volatile private var pyAt = 0L

    fun pythonReady(): Boolean {
        val now = System.currentTimeMillis()
        if (pyCache != null && now - pyAt < 5_000) return pyCache!!
        val ctx = ctxCache
        if (ctx != null && pythonLocalReady(ctx)) {
            pyCache = true
            pyAt = now
            return true
        }
        val res = execPriv("ls -ld $RT_PY 2>&1")
        val p = res.first && !res.second.contains("No such")
        pyCache = p
        pyAt = now
        return p
    }

    /** 状态展示（主线程安全）：只读缓存，绝不触发 su / socket。 */
    fun state(ctx: Context): State {
        remember(ctx)
        val pyNow = pyCache ?: false
        val detail = buildString {
            append(if (pyNow) "内置 Python 已就绪（应用内直跑）" else "内置 Python 未部署")
            append(" · ").append(if (RootController.rootCached()) "Root" else "免Root")
            if (!pyNow) append("（启动自动部署，无需 Root/无线调试）")
        }
        return State(pyNow, RootController.rootCached(), detail.toString())
    }

    /** 后台刷新真实状态（阻塞探测，必须切 IO 线程调用）。 */
    fun stateBlocking(ctx: Context): State {
        remember(ctx)
        val pyNow = pythonLocalReady(ctx)
        val rootNow = RootController.canUseRoot()
        val detail = buildString {
            append(if (pyNow) "内置 Python 已就绪（应用内直跑）" else "内置 Python 未部署")
            append(" · ").append(if (rootNow) "Root" else "免Root")
            if (!pyNow) append("（一键就绪自动部署到本机，无需任何权限）")
        }
        return State(pyNow, rootNow, detail.toString())
    }

    // ---------------------------------------------------------------- 本地部署（解压运行时到私有目录）

    private fun assetGz(ctx: Context): File {
        val dir = File(ctx.filesDir, "termux").apply { mkdirs() }
        val dst = File(dir, "runtime.tar.gz")
        // gzip 魔数校验：防止历史脏缓存/解码产物被误用
        if (dst.exists() && dst.length() > 1_000_000 && isGzip(dst)) return dst
        FileOutputStream(dst).use { out -> out.write(runtimeAssetBytes(ctx)) }
        return dst
    }

    /** 仅读文件头 2 字节做 gzip 魔数校验，避免全量读入（12MB）判断缓存是否损坏。 */
    private fun isGzip(f: File): Boolean = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.length() >= 2 && raf.readUnsignedByte() == 0x1f && raf.readUnsignedByte() == 0x8b
        }
    }.getOrDefault(false)

    private fun runtimeAssetBytes(ctx: Context): ByteArray =
        ctx.assets.open(ASSET_GZ).use { it.readBytes() }

    /** 把内嵌运行时解压到 App 私有目录 filesDir/xsrt。 */
    private fun deployLocal(ctx: Context): Pair<Boolean, String> = runCatching {
        if (pythonLocalReady(ctx)) return@runCatching true to "already-ready"
        remember(ctx)
        extractTarGz(assetGz(ctx), ctx.filesDir)
        // 统一补齐可读/可执行位（tar 内权限为 666，解释器需可执行）
        localRoot(ctx).walkTopDown().forEach { f ->
            runCatching { f.setReadable(true, false) }
            runCatching { f.setExecutable(true, false) }
        }
        if (!pythonLocalReady(ctx)) false to "exec-denied"
        else true to "deploy-local-ok"
    }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }

    /** 极简 tar.gz 解压（GNU tar / ustar 子集，512 字节块解析；零第三方依赖）。 */
    private fun extractTarGz(gz: File, dest: File) {
        dest.mkdirs()
        BufferedInputStream(FileInputStream(gz)).use { raw ->
            GZIPInputStream(raw).use { tin ->
                val header = ByteArray(512)
                while (true) {
                    var off = 0
                    while (off < 512) {
                        val n = tin.read(header, off, 512 - off)
                        if (n < 0) { off = -1; break }
                        off += n
                    }
                    if (off < 512) break
                    if (header.all { it == 0.toByte() }) break
                    val name = header.toName(0, 100)
                    val size = header.toOctal(124, 12)
                    val type = header[156].toInt().toChar()
                    if (name.isEmpty() || size < 0) break
                    val target = File(dest, name)
                    val destBase = dest.canonicalPath + File.separator
                    if (!target.canonicalPath.startsWith(destBase)) break
                    if (type == '5') {
                        target.mkdirs()
                    } else if (type == '2') {
                        val link = header.toName(157, 100)
                        runCatching { java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link)) }
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = size
                            val buf = ByteArray(8192)
                            while (remaining > 0) {
                                val n = tin.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                if (n > 0) { out.write(buf, 0, n); remaining -= n }
                            }
                        }
                    }
                    // 数据块按 512 对齐跳过
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    var skipped = 0
                    while (skipped < pad) {
                        val n = tin.skip((pad - skipped).toLong())
                        if (n <= 0) break
                        skipped += n.toInt()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 通道兜底部署（/data/local/tmp，Root / 无线调试）

    private fun deployChannel(ctx: Context): Pair<Boolean, String> {
        return try {
            val m = channelFast()
            if (m == 0) {
                false to "no-channel"
            } else if (execPriv("ls -ld $RT_PY 2>&1").let { it.first && !it.second.contains("No such") }) {
                true to "deploy-hot"
            } else if (m == 1) {
                val src = assetGz(ctx).absolutePath
                val r = RootController.exec(
                    "cp " + src + " /data/local/tmp/runtime.tar.gz && tar -xzf /data/local/tmp/runtime.tar.gz -C /data/local/tmp && rm -f /data/local/tmp/runtime.tar.gz"
                )
                if (!r.ok) false to ("解压失败：" + r.output.take(200)) else {
                    RootController.exec("chown -R 2000:2000 /data/local/tmp/xsrt; chmod -R a+rX /data/local/tmp/xsrt; chmod 755 /data/local/tmp/xsrt/bin/*")
                    true to "deploy-root-ok"
                }
            } else {
                val ext = File(ctx.getExternalFilesDir("xsrt"), "runtime.tar.gz").apply { parentFile?.mkdirs() }
                FileOutputStream(ext).use { out -> out.write(runtimeAssetBytes(ctx)) }
                val e = AdbShellController.exec(
                    "mkdir -p /data/local/tmp && cp " + ext.absolutePath + " /data/local/tmp/runtime.tar.gz && " +
                        "cd /data/local/tmp && tar -xzf runtime.tar.gz && rm -f runtime.tar.gz && chmod -R a+rX /data/local/tmp/xsrt && chmod 755 /data/local/tmp/xsrt/bin/*"
                )
                if (!e.ok) false to ("无线部署失败：" + e.output.take(8)) else true to "deploy-adb-ok"
            }
        } catch (t: Throwable) {
            false to ("部署异常：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- Python 执行

    /** 执行 Python 脚本（兼容旧调用：无 ctx 时读取缓存上下文）。 */
    fun runPython(scriptPath: String, args: String, workDir: String? = null): Pair<Boolean, String> =
        runPython(ctxCache, scriptPath, args, workDir)

    /** 执行 Python：① App 内直跑（零通道） ② 通道兜底。 */
    fun runPython(ctx: Context?, scriptPath: String, args: String, workDir: String? = null): Pair<Boolean, String> {
        if (ctx != null) {
            remember(ctx)
            if (pythonLocalReady(ctx)) return execPythonLocal(ctx, scriptPath, args, workDir)
        }
        if (!pythonReady()) return false to "内置 Python 未就绪，请稍候自动部署完成后再试"
        if (channelFast() == 0) return false to "无特权通道（本地直跑不可用，请开启 Root 或无线调试）"
        val code = try { java.io.File(scriptPath).readText() } catch (e: Exception) { return false to ("读取脚本失败：" + e.message) }
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
        val text = trimmed.replace(Regex("""\s*XS_RC:\d+\s*$"""), "").trim()
        return when {
            exit == 0 -> true to text
            text.isNotBlank() -> false to text
            else -> false to ("脚本异常退出（code $exit）")
        }
    }

    /** 应用内直跑：ProcessBuilder 直接执行私有目录解释器，无需任何特权通道。 */
    private fun execPythonLocal(ctx: Context, scriptPath: String, args: String, workDir: String?): Pair<Boolean, String> {
        return try {
            val root = localRoot(ctx)
            val cmd = mutableListOf(File(root, "bin/python3").absolutePath, scriptPath)
            if (args.isNotBlank()) cmd.add(args.trim())
            val pb = ProcessBuilder(cmd)
            pb.directory(File(workDir ?: File(scriptPath).parentFile?.absolutePath ?: root.absolutePath))
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", File(root, "lib").absolutePath)
                put("PYTHONHOME", root.absolutePath)
                put("PYTHONDONTWRITEBYTECODE", "1")
                put("PYTHONUNBUFFERED", "1")
            }
            val proc = pb.start()
            val out = ByteArrayOutputStream()
            val reader = Thread({
                try {
                    proc.inputStream.use { input ->
                        val chunk = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (total < MAX_OUT) {
                                val take = minOf(n, MAX_OUT - total)
                                if (take > 0) { out.write(chunk, 0, take); total += take }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "xs-local-py-reader").apply { isDaemon = true }
            reader.start()
            if (!proc.waitFor(PY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { proc.destroyForcibly() }
                reader.join(2000)
                return false to ("脚本执行超时（" + PY_TIMEOUT_MS / 1000 + "s），已终止")
            }
            reader.join(2000)
            val text = out.toString(StandardCharsets.UTF_8.name()).trimEnd()
            if (proc.exitValue() == 0) true to text
            else if (text.isNotBlank()) false to text
            else false to ("脚本异常退出（code " + proc.exitValue() + "）")
        } catch (t: Throwable) {
            false to ("本地执行失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** 本地自检：解释器可启动且标准库可导入。 */
    private fun selfCheckLocal(ctx: Context): Boolean {
        if (!pythonLocalReady(ctx)) return false
        return runCatching {
            val root = localRoot(ctx)
            val pb = ProcessBuilder(File(root, "bin/python3").absolutePath, "-c", "import sys")
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", File(root, "lib").absolutePath)
                put("PYTHONHOME", root.absolutePath)
                put("PYTHONDONTWRITEBYTECODE", "1")
            }
            val p = pb.start()
            p.inputStream.readBytes()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { runCatching { p.destroyForcibly() }; false } else p.exitValue() == 0
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 一键就绪 / 启动预热

    suspend fun ensureReady(ctx: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        remember(ctx)
        if (pythonReady()) return@withContext true to "内置 Python 3.14 已就绪（应用内直跑）"
        // ① App 内直跑
        val (okLocal, msgLocal) = deployLocal(ctx)
        if (okLocal && selfCheckLocal(ctx)) {
            pyCache = true
            pyAt = System.currentTimeMillis()
            return@withContext true to "内置 Python 3.14 已就绪（应用内直跑）"
        }
        // ② 通道兜底（仅本地不可行时）
        if (channelFast() == 0 && AdbShellController.ensureConnected()) {
            chMode = 0
            chAt = 0L
        }
        val (deployed, msg) = deployChannel(ctx)
        if (deployed && selfCheck()) true to "内置 Python 3.14 已就绪"
        else false to (if (channelFast() == 0) "本地直跑不可用（" + msgLocal + "），且无特权通道：请开启 Root 或无线调试" else userFacing(msg))
    }

    private fun userFacing(msg: String): String = when {
        msg.startsWith("no-channel") -> "无可用通道：请开启 Root，或先连接无线调试后再试"
        else -> "部署未完成：" + msg
    }

    /**
     * App 启动后台自动就绪：本地直跑优先，仅本地被禁时才尝试通道。
     * 以「真实是否可执行」为准（pythonReady），运行时被清空后启动自动重新部署。
     */
    fun autoPrepare(ctx: Context): String {
        Log.i(TAG, "autoPrepare called")
        remember(ctx)
        if (pythonReady()) { prefs(ctx).edit().putBoolean(AUTO_DONE, true).apply(); return "ready" }
        val (localOk, localMsg) = deployLocal(ctx)
        if (localOk && selfCheckLocal(ctx)) {
            pyCache = true
            prefs(ctx).edit().putBoolean(AUTO_DONE, true).apply()
            Log.i(TAG, "auto local ready")
            return "ready"
        }
        if (channelFast() == 0) { Log.i(TAG, "auto deferred: $localMsg"); return "deferred" }
        val (deployed, msg) = deployChannel(ctx)
        if (deployed && selfCheck()) {
            prefs(ctx).edit().putBoolean(AUTO_DONE, true).apply()
            Log.i(TAG, "auto channel ready")
            return "ready"
        }
        Log.i(TAG, "auto deferred: $msg")
        return "deferred"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 清除已部署的运行时（重新部署用）。阻塞执行，必须切后台线程；返回结果说明。 */
    suspend fun resetRuntime(ctx: Context): String = withContext(Dispatchers.IO) {
        pyCache = null
        runCatching { localRoot(ctx).deleteRecursively() }
        val (ok, out) = execPriv("rm -rf $RT_DIR")
        prefs(ctx).edit().remove(AUTO_DONE).apply()
        if (ok) "已清除，可重新部署"
        else if (channelFast() == 0) "已清除本地运行时，可重新部署"
        else "清除失败：" + out
    }

    /** 通道兜底自检（与旧实现一致）。 */
    private fun selfCheck(): Boolean {
        val r = execPriv(
            "export LD_LIBRARY_PATH=$RT_DIR/lib; export PYTHONHOME=$RT_DIR; export PYTHONDONTWRITEBYTECODE=1; " +
                "$RT_PY -c 'import sys;sys.exit(0)' 2>&1"
        )
        return r.first
    }
}

// ---------- tar 解析基础工具（文件私有） ----------

private fun ByteArray.toName(start: Int, len: Int): String {
    var end = start
    while (end < start + len && this[end] != 0.toByte()) end++
    return if (end > start) String(this, start, end - start, StandardCharsets.UTF_8) else ""
}

private fun ByteArray.toOctal(start: Int, len: Int): Long {
    var v = 0L
    var i = start
    val end = minOf(start + len, size)
    while (i < end) {
        val c = this[i]
        if (c < '0'.code.toByte() || c > '7'.code.toByte()) {
            if (c == 0.toByte()) break
            i++
            continue
        }
        v = v * 8 + (c - '0'.code.toByte()).toLong()
        i++
    }
    return v
}
