package com.xs.chat.plugins

import com.xs.chat.sandbox.Sandbox

import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 脚本原生桥：JS（__xs.*）与 Lua（xs.*）共用。
 * 提供：日志、命令行参数、网络抓取、插件目录内文件读写、受沙盒约束的 shell 执行。
 * 所有相对路径被限制在插件目录内，防止脚本越权读写。
 */
@Suppress("unused")
class ScriptIo(
    private val dir: File,
    private val log: StringBuilder,
    private val args: String
) {
    private val maxText = 200_000
    private val maxReadBytes = 10 * 1024 * 1024
    private val maxOutputBytes = 512 * 1024

    fun log(msg: String) {
        if (log.length >= maxText) return
        log.append(msg).append('\n')
    }

    fun argString(): String = args

    fun cwd(): String = dir.absolutePath

    fun fetchText(url: String, timeoutMs: Int): String =
        httpGet(url, timeoutMs.coerceIn(5_000, 180_000)).toString(StandardCharsets.UTF_8)

    fun fetchBase64(url: String): String = Base64.encodeToString(httpGet(url, 30_000), Base64.NO_WRAP)

    fun downloadFile(url: String, relPath: String): String {
        val target = safePath(relPath)
        target.parentFile?.mkdirs()
        target.writeBytes(httpGet(url, 60_000))
        return target.absolutePath
    }

    fun writeFile(relPath: String, content: String): Boolean {
        val f = safePath(relPath)
        f.parentFile?.mkdirs()
        f.writeText(content, StandardCharsets.UTF_8)
        return true
    }

    fun writeFileBase64(relPath: String, b64: String): Boolean {
        val f = safePath(relPath)
        f.parentFile?.mkdirs()
        f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
        return true
    }

    fun readFile(relPath: String, maxBytes: Int): String? {
        val f = safePath(relPath)
        if (!f.isFile) return null
        val limit = if (maxBytes > 0) minOf(maxBytes, maxReadBytes) else maxReadBytes
        if (f.length() > limit) {
            val bytes = f.readBytes()
            val head = bytes.take(minOf(limit, bytes.size)).toByteArray()
            return head.toString(StandardCharsets.UTF_8) + "\n…（文件过大已截断）"
        }
        return f.readText(StandardCharsets.UTF_8)
    }

    fun readFileBase64(relPath: String): String? {
        val f = safePath(relPath)
        if (!f.isFile || f.length() > maxReadBytes) return null
        return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    fun listFiles(relPath: String): List<String> =
        safePath(relPath).listFiles()?.map { it.name } ?: emptyList()

    /** 执行 shell 命令（走应用沙盒过滤，超时 60s，返回合并输出）。 */
    fun shell(cmd: String): String {
        val blocked = Sandbox.intercept(cmd)
        if (blocked != null) throw RuntimeException(blocked)
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        pb.environment().put("XS_PLUGIN_DIR", dir.absolutePath)
        val proc = pb.start()
        val out = ByteArrayOutputStream()
        val reader = Thread({
            try {
                proc.inputStream.use { input ->
                    val chunk = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (total < maxOutputBytes) {
                            val take = minOf(n, maxOutputBytes - total)
                            if (take > 0) {
                                out.write(chunk, 0, take)
                                total += take
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "xs-io-shell").apply { isDaemon = true }
        reader.start()
        val finished = proc.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { if (Build.VERSION.SDK_INT >= 26) proc.destroyForcibly() else proc.destroy() }
            reader.join(1500)
            throw RuntimeException("shell 执行超时（60s）")
        }
        reader.join(1500)
        return out.toString(StandardCharsets.UTF_8.name()).trimEnd()
    }

    private fun safePath(rel: String): File {
        if (rel.isBlank()) throw IllegalArgumentException("路径为空")
        val target = if (File(rel).isAbsolute) File(rel) else File(dir, rel)
        val base = dir.canonicalFile.toPath().normalize()
        val t = target.canonicalFile.toPath().normalize()
        if (!t.startsWith(base)) throw IllegalArgumentException("路径越界：" + rel)
        return target
    }

    private fun httpGet(url: String, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "XS-Chat-ScriptPlugins/1.0")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxReadBytes) throw RuntimeException("响应体过大（>10MB）")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}
