package com.xs.chat.plugins

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.Base64
import com.xs.chat.plugins.PluginRegistry.PluginInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import java.io.FileInputStream
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.RhinoException
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/**
 * 脚本插件运行器：按语言路由到本地运行时。
 * - .sh：Android 自带 mksh（含 toybox 基础工具）
 * - .js：内置 Rhino（ES6 子集，提供 __xs 原生桥：网络 / 文件 / 日志）
 * - .lua：内置 LuaJ（提供 xs 原生桥）
 * - .py：设备上的 Python 3（如 Termux 安装；deps 字段自动 pip 安装到插件目录）
 * 脚本运行于应用私有目录，输出上限 512KB，超时自动终止；删除插件时脚本目录一并清理。
 */
object ScriptRunner {

    data class RunResult(val ok: Boolean, val output: String, val error: String? = null)

    /**
     * 将失败结果提炼为一句中文原因，供聊天卡片直接展示（覆盖常见报错场景）。
     */
    fun explainFailure(result: RunResult): String {
        val text = (result.error ?: "") + "\n" + result.output
        // 缺 Python 库：ModuleNotFoundError / ImportError
        Regex("ModuleNotFoundError: No module named ['\"]([^'\"]+)['\"]").find(text)?.let { m ->
            val mod = m.groupValues[1]
            val extra = when (mod) {
                "turtle" -> "（且 Android 无桌面窗口，turtle 无法弹窗绘图）"
                "tkinter" -> "（GUI 库，Android 不可用）"
                else -> ""
            }
            return "缺少 Python 库 " + mod + extra + "：内置引擎只带标准库（numpy/matplotlib/pip 均不可用），可让 AI 改用纯标准库写法"
        }
        // Shell 127：命令不存在
        if ((result.error?.contains("脚本退出码 127") == true) || text.contains("inaccessible or not found")) {
            return "脚本用到的命令在当前设备上不存在（如 python/pip），内置环境只有受限命令集"
        }
        // SELinux / 权限拒绝
        if (text.contains("execute_no_trans") || text.contains("Permission denied") || text.contains("PermissionError")) {
            return "系统权限拒绝：当前 ROM 的 SELinux 限制直接执行程序"
        }
        // Python traceback 通用翻译（NameError/SyntaxError/TypeError 等）
        val errLine = text.lineSequence().filter { it.isNotBlank() }
            .lastOrNull { Regex("\\w+(Error|Exception):").containsMatchIn(it) }
        if (errLine != null) {
            val kind = errLine.substringBefore(":").trim()
            val msg = errLine.substringAfter(":").trim().take(120)
            val reason = when {
                kind.contains("Name") -> "代码引用了未定义的变量或名称"
                kind.contains("Syntax") -> "代码存在语法错误"
                kind.contains("Indent") -> "缩进错误（Python 对缩进敏感）"
                kind.contains("Type") -> "数据类型不匹配"
                kind.contains("Value") -> "传入的值或参数不合法"
                kind.contains("ZeroDivision") -> "出现除零运算"
                kind.contains("Attribute") -> "访问了不存在的对象属性"
                kind.contains("Key") -> "字典缺少对应的键"
                kind.contains("Index") -> "下标越界"
                kind.contains("FileNotFound") || kind.contains("OSError") -> "文件或路径不存在/无权限"
                kind.contains("Timeout") -> "操作超时"
                else -> "Python 运行时异常（" + kind + "）"
            }
            return reason + (if (msg.isEmpty()) "" else "：" + msg)
        }
        return "脚本执行失败：请结合下方原始输出修正，或让 AI 改写脚本"
    }

    private const val DEFAULT_TIMEOUT_MS = 180_000L
    private const val LANG_TIMEOUT_MS = 120_000L
    private const val PIP_TIMEOUT_MS = 6 * 60_000L
    private const val MAX_OUTPUT_BYTES = 512 * 1024
    private const val MAX_READ_BYTES = 10 * 1024 * 1024
    private const val MAX_TEXT = 200_000

    /**
     * 代码块围栏语言 -> 可运行语言键（py / js / sh / lua / sql）；不支持返回 null。
     * 供聊天代码块「一键运行」按语言路由到对应内置运行时。
     */
    fun langFromFence(label: String?): String? {
        val key = label?.trim()?.lowercase(Locale.ROOT)?.removePrefix("`")?.trim() ?: return null
        return when {
            key in setOf("py", "python", "python3", "python3.x") -> "py"
            key in setOf("js", "javascript", "node", "nodejs", "es6") -> "js"
            key in setOf("sh", "bash", "shell", "zsh", "ash", "mksh", "console") -> "sh"
            key in setOf("lua") -> "lua"
            key in setOf("sql", "sqlite", "sqlite3", "mysql", "postgresql", "postgres", "psql") -> "sql"
            else -> null
        }
    }

    private val PY_CANDIDATES = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python3.12",
        "/data/data/com.termux/files/usr/bin/python3.11",
        "/data/data/com.termux/files/usr/bin/python"
    )

    suspend fun run(
        context: Context,
        plugin: PluginInfo,
        args: String,
        onProgress: ((String) -> Unit)? = null
    ): RunResult = withContext(Dispatchers.IO) {
        val script = ScriptStore.scriptFile(context, plugin)
        if (script == null || !script.isFile) {
            return@withContext RunResult(false, "", "脚本文件不存在：" + plugin.scriptFile)
        }
        val dir = ScriptStore.dir(context, plugin.id)
        val lang = plugin.lang ?: PluginRegistry.langFor(plugin.scriptFile ?: "") ?: ""
        try {
            when (lang) {
                "sh" -> execRun(listOf("/system/bin/sh", script.absolutePath, args), dir, DEFAULT_TIMEOUT_MS)
                "js" -> runJs(dir, script, args)
                "lua" -> runLua(dir, script, args)
                "py" -> runPython(context, dir, plugin, script, args, onProgress)
                "sql" -> runSql(script)
                else -> RunResult(false, "", "暂不支持的脚本语言 " + lang)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunResult(false, "", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- 进程执行（sh / python） ----------

    private fun execRun(cmd: List<String>, dir: File, timeoutMs: Long, env: Map<String, String> = emptyMap()): RunResult {
        val pb = ProcessBuilder(cmd)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("XS_PLUGIN_DIR", dir.absolutePath)
            env.forEach { (k, v) -> put(k, v) }
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
                        if (total < MAX_OUTPUT_BYTES) {
                            val take = minOf(n, MAX_OUTPUT_BYTES - total)
                            if (take > 0) {
                                out.write(chunk, 0, take)
                                total += take
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "xs-script-reader").apply { isDaemon = true }
        reader.start()
        var code = -1
        try {
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                killProc(proc)
                return RunResult(false, trimOutput(out), "脚本执行超时（" + timeoutMs / 1000 + "s），已终止")
            }
            code = proc.exitValue()
        } finally {
            reader.join(2000)
        }
        val text = trimOutput(out)
        return if (code == 0) RunResult(true, text)
        else RunResult(false, text, "脚本退出码 $code")
    }

    private fun killProc(proc: Process) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) proc.destroyForcibly() else proc.destroy()
        }
    }

    private fun trimOutput(out: ByteArrayOutputStream): String {
        val text = out.toString(StandardCharsets.UTF_8.name()).trimEnd()
        return if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…（输出过长已截断）" else text
    }

    // ---------- JS（Rhino）与 Lua（LuaJ）：独立守护线程 + 超时放弃等待 ----------

    private fun runJs(dir: File, script: File, args: String): RunResult = bounded(LANG_TIMEOUT_MS) {
        val log = StringBuilder()
        var cx: RhinoContext? = null
        try {
            cx = RhinoContext.enter()
            cx.languageVersion = RhinoContext.VERSION_ES6
            // Android 上禁用字节码优化（解释执行），避免 ClassDefDino 类文件加载失败
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            val io = ScriptIo(dir, log, args)
            scope.put("__xs", scope, RhinoContext.javaToJS(io, scope))
            cx.evaluateString(scope, BOOTSTRAP_JS, "bootstrap.js", 1, null)
            val ret = cx.evaluateString(scope, script.readText(StandardCharsets.UTF_8), script.name, 1, null)
            if (ret != null && !RhinoContext.toString(ret).isNullOrBlank()) appendLog(log, RhinoContext.toString(ret))
            RunResult(true, trimText(log))
        } catch (e: RhinoException) {
            RunResult(false, trimText(log), e.details().take(400))
        } catch (e: Exception) {
            RunResult(false, trimText(log), e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { RhinoContext.exit() }
        }
    }

    private fun runLua(dir: File, script: File, args: String): RunResult = bounded(LANG_TIMEOUT_MS) {
        val log = StringBuilder()
        try {
            val globals: Globals = JsePlatform.standardGlobals()
            globals.set("xs", CoerceJavaToLua.coerce(ScriptIo(dir, log, args)))
            globals.set("XS_ARGS", LuaValue.valueOf(args))
            globals.set("print", object : OneArgFunction() {
                override fun call(arg: LuaValue): LuaValue {
                    appendLog(log, arg.tojstring())
                    return LuaValue.NIL
                }
            })
            val chunk = globals.load(FileInputStream(script.absolutePath), script.name, "t", globals)
            val ret = chunk.call()
            if (!ret.isnil() && !ret.tojstring().isBlank()) appendLog(log, ret.tojstring())
            RunResult(true, trimText(log))
        } catch (e: LuaError) {
            RunResult(false, trimText(log), e.message ?: "Lua 脚本执行失败")
        } catch (e: Exception) {
            RunResult(false, trimText(log), e.message ?: e.javaClass.simpleName)
        }
    }

    /** SQL：走 Android 内置 SQLite，内存库执行多语句，SELECT/PRAGMA 以表格形式输出。 */
    private fun runSql(script: File): RunResult = bounded(LANG_TIMEOUT_MS) { runSqlInner(script) }

    private fun runSqlInner(script: File): RunResult {
        val log = StringBuilder()
        var db: android.database.sqlite.SQLiteDatabase? = null
        val result: RunResult = try {
            val flags = android.database.sqlite.SQLiteDatabase.OPEN_READWRITE or
                android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
            db = android.database.sqlite.SQLiteDatabase.openDatabase(":memory:", null, flags)
            for (stmt in splitSqlStatements(script.readText(StandardCharsets.UTF_8))) {
                val sql = stmt.trim()
                if (sql.isEmpty()) continue
                if (sql.length > 100_000) {
                    appendLog(log, "单条 SQL 过长（>100KB），已中止")
                    break
                }
                val upper = sql.uppercase(Locale.ROOT)
                val isQuery = upper.startsWith("SELECT") || upper.startsWith("PRAGMA") || upper.startsWith("EXPLAIN")
                if (isQuery) {
                    val cur = db.rawQuery(sql, null)
                    try {
                        if (cur.columnCount > 0) {
                            appendLog(log, (0 until cur.columnCount).joinToString(" | ") { cur.getColumnName(it) })
                            var rows = 0
                            while (cur.moveToNext() && rows < 500) {
                                appendLog(log, (0 until cur.columnCount).joinToString(" | ") { i ->
                                    when (cur.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i).toString()
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i).toString()
                                        android.database.Cursor.FIELD_TYPE_BLOB -> "blob(" + cur.getBlob(i).size + "B)"
                                        else -> cur.getString(i)?.let { s -> if (s.length > 160) s.take(160) + "…" else s } ?: "NULL"
                                    }
                                })
                                rows++
                            }
                            val total = cur.count
                            if (total > 500) appendLog(log, "……（已截断至 500 行，共 $total 行）")
                        } else {
                            appendLog(log, "OK")
                        }
                    } finally {
                        runCatching { cur.close() }
                    }
                } else {
                    db.execSQL(sql)
                    appendLog(log, "OK")
                }
            }
            RunResult(true, trimText(log))
        } catch (e: Exception) {
            RunResult(false, trimText(log), e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { db?.close() }
        }
        return result
    }

    /** 简易 SQL 语句拆分：按分号切分，忽略引号内/行注释中的分号。 */
    private fun splitSqlStatements(raw: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var quote = false
        var comment = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                comment -> if (c == '\n') comment = false
                quote -> {
                    buf.append(c)
                    if (c == '\'' && (i == 0 || raw[i - 1] != '\\')) quote = false
                }
                c == '\'' -> { quote = true; buf.append(c) }
                c == ';' -> { out += buf.toString().trim(); buf.clear() }
                c == '-' && i + 1 < raw.length && raw[i + 1] == '-' -> comment = true
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    /** 独立守护线程执行；超时后放弃等待（进程不被阻塞，脚本线程后台自行结束）。 */
    private fun bounded(timeoutMs: Long, block: () -> RunResult): RunResult {
        val ref = AtomicReference<RunResult>()
        val t = Thread(
            { ref.set(runCatching { block() }.getOrElse { e -> RunResult(false, "", e.message ?: "脚本异常") }) },
            "xscript-timed"
        ).apply { isDaemon = true }
        t.start()
        try {
            t.join(timeoutMs)
        } catch (_: InterruptedException) {
            return RunResult(false, "", "脚本执行被中断")
        }
        return ref.get() ?: RunResult(false, "", "脚本执行超时（" + timeoutMs / 1000 + "s），已放弃等待")
    }

    // ---------- Python：优先本地运行时可执行，否则走内置 Termux 链路 ----------

    private suspend fun runPython(context: Context, dir: File, plugin: PluginInfo, script: File, args: String, onProgress: ((String) -> Unit)?): RunResult {
        // ① 嵌入式 Python 3.14（JNI + dlopen：免 Root/无线调试/文件 exec 权限，SELinux 收紧 ROM 亦可直跑）
        val pyAvail = PyEngine.available(context)
        Log.i("XSRunDebug", "pyAvail=" + pyAvail + " err=" + PyEngine.loadErrorText())
        if (pyAvail) {
            onProgress?.invoke("正在就绪内置 Python 3.14…")
            val (ready, msg) = runCatching { TermuxManager.ensureLocalReady(context) }
                .getOrElse { false to ("运行时解压异常：" + (it.message ?: it.javaClass.simpleName)) }
            if (ready) {
                onProgress?.invoke("▶ 内置 Python 3.14（应用内直跑）")
                val deps = plugin.deps.trim()
                if (deps.isNotEmpty()) onProgress?.invoke("嵌入式引擎不含 pip；第三方依赖请改用通道环境")
                val (ok, out) = PyEngine.run(context, script.readText(StandardCharsets.UTF_8), args)
                return if (ok) RunResult(true, out) else RunResult(false, out, "Python 脚本出错")
            }
            onProgress?.invoke(msg)
            Log.w("XSRunDebug", "embedded python not ready: " + msg)
        }

        val localPy = PY_CANDIDATES.map { File(it) }.firstOrNull { it.canExecute() }
        if (localPy != null) {
            val env = mutableMapOf("PYTHONDONTWRITEBYTECODE" to "1", "PYTHONUNBUFFERED" to "1")
            val deps = plugin.deps.trim()
            if (deps.isNotEmpty()) {
                onProgress?.invoke("正在自动安装依赖：" + deps.take(60) + "…")
                ensurePipDeps(localPy, dir, deps)
                env["PYTHONPATH"] = File(dir, ".pydeps").absolutePath
            }
            return execRun(listOf(localPy.absolutePath, "-3", script.absolutePath, args), dir, DEFAULT_TIMEOUT_MS, env)
        }

        // ---- 内置 Termux 链路（自动安装/初始化，Root 通道直接执行）----
        onProgress?.invoke("正在就绪内置 Termux 运行时…")
        val (ready, msg) = runCatching { TermuxManager.ensureReady(context) }
            .getOrElse { false to ("Termux 初始化异常：" + (it.message ?: it.javaClass.simpleName)) }
        if (!ready) {
            // 安装/初始化进行中：给 Termux 内安装 Python 留出时间
            onProgress?.invoke(msg)
            // 通道缺失（无 Root/无线调试）时立即返回明确错误，不空等
            if (TermuxManager.channelFast() == 0) return RunResult(false, "", msg)
            var waited = 0L
            while (waited < 150_000L && !TermuxManager.pythonReady()) {
                Thread.sleep(2000)
                waited += 2000L
            }
            if (!TermuxManager.pythonReady()) return RunResult(false, "", msg)
        }
        val deps = plugin.deps.trim()
        if (deps.isNotEmpty()) {
            return RunResult(false, "", "内置 Termux 链路暂不支持自动 pip 依赖（请改在 Termux 内安装：" + deps.take(80) + "）")
        }
        val (ok, out) = TermuxManager.runPython(context, script.absolutePath, args, dir.absolutePath)
        return if (ok) RunResult(true, out) else RunResult(false, out, "")
    }

    /** pip 安装依赖到插件目录 .pydeps（失败抛出具体原因）。 */
    private fun ensurePipDeps(py: File, dir: File, deps: String) {
        val target = File(dir, ".pydeps").apply { mkdirs() }
        val req = File(dir, "requirements.txt")
        req.writeText(deps.replace('\n', ' ').trim() + "\n")
        val install = execRun(
            cmd = listOf(
                py.absolutePath, "-m", "pip", "install",
                "--disable-pip-version-check", "--no-input", "-q",
                "-r", req.name, "--target", target.absolutePath
            ),
            dir = dir,
            timeoutMs = PIP_TIMEOUT_MS,
            env = mapOf("PIP_DISABLE_PIP_VERSION_CHECK" to "1", "PIP_NO_INPUT" to "1")
        )
        if (!install.ok) throw RuntimeException("依赖安装失败：" + install.output.take(600))
    }

    // ---------- 通用工具 ----------

    private fun appendLog(log: StringBuilder, msg: String) {
        if (msg.isBlank() || log.length >= MAX_TEXT) return
        log.append(msg).append('\n')
    }

    private fun trimText(log: StringBuilder): String = log.toString().trimEnd()

    private val BOOTSTRAP_JS = """
        var XS_ARGS = __xs.argString();
        var XS_DIR = __xs.cwd();
        function print() {
            var s = "";
            for (var i = 0; i < arguments.length; i++) s += arguments[i] + " ";
            __xs.log(s);
        }
        var console = { log: print, error: print, warn: print };
        function fetchText(url, timeoutMs) { return __xs.fetchText(String(url), (timeoutMs || 15000) | 0); }
        function fetchJSON(url) { return JSON.parse(__xs.fetchText(String(url), 15000)); }
        function fetchBase64(url) { return __xs.fetchBase64(String(url)); }
        function downloadFile(url, relPath) { return __xs.downloadFile(String(url), String(relPath)); }
        function writeFile(relPath, content) { __xs.writeFile(String(relPath), String(content)); }
        function writeBase64(relPath, b64) { __xs.writeFileBase64(String(relPath), String(b64)); }
        function readFile(relPath, maxBytes) { return __xs.readFile(String(relPath), (maxBytes || 0) | 0); }
        function readBase64(relPath) { return __xs.readFileBase64(String(relPath)); }
        function listFiles(relPath) { return __xs.listFiles(String(relPath || ".")); }
        function runShell(cmd) { return __xs.shell(String(cmd)); }
    """.trimIndent()

    private const val PY_NOT_FOUND =
        "未检测到本机 Python 3 运行时。推荐安装 Termux（F-Droid）后执行：pkg install python。否则请改用 .sh / .js / .lua 脚本（无需额外运行时）。"
}



