package com.xs.chat.mcp

import android.content.Context
import android.os.Build
import com.xs.chat.BuildConfig
import com.xs.chat.sandbox.Sandbox
import com.wirelessdebug.WdbContext
import com.wirelessdebug.service.AdbShellController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置 MCP server（Streamable HTTP / JSON-RPC 2.0）：
 * 监听 127.0.0.1:8765，外部客户端（如 PC 上的 Codex）通过
 * `adb reverse tcp:8765 tcp:8765` 后连接 http://127.0.0.1:8765/message。
 * 工具：device_info / app_status / shell_exec（shell_exec 强制走沙盒过滤）。
 */
object McpServer {
    private const val PROTOCOL_VERSION = "2025-03-26"
    private const val SERVER_NAME = "xs-chat-mcp"
    private const val SERVER_VERSION = "1.0.0"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var port = 8765
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mcp-worker").apply { isDaemon = true } }
    val state = MutableStateFlow("已停止")

    @Volatile var appContext: Context? = null

    fun start(targetPort: Int): Boolean {
        if (running.get()) return true
        return try {
            port = targetPort.coerceIn(1024, 65535)
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            serverSocket = ss
            running.set(true)
            state.value = "运行中（127.0.0.1:$port）"
            Thread({ acceptLoop(ss) }, "mcp-accept").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            state.value = "启动失败：${e.message}"
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        state.value = "已停止"
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                pool.execute { handle(socket) }
            } catch (e: Exception) {
                if (running.get()) {
                    try { Thread.sleep(50) } catch (ignored: InterruptedException) { break }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val (status, payload) = when {
                    method == "GET" && path.startsWith("/health") ->
                        "200 OK" to """{"status":"ok","server":"$SERVER_NAME","version":"$SERVER_VERSION"}"""
                    method == "POST" && path.startsWith("/message") -> "200 OK" to handleRpc(body)
                    else -> "404 Not Found" to """{"error":"not found"}"""
                }
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                val head = StringBuilder()
                    .append("HTTP/1.1 ").append(status).append("\r\n")
                    .append("Content-Type: application/json; charset=utf-8\r\n")
                    .append("Content-Length: ").append(bytes.size).append("\r\n")
                    .append("MCP-Protocol-Version: ").append(PROTOCOL_VERSION).append("\r\n")
                    .append("Connection: close\r\n\r\n")
                val out = s.getOutputStream()
                out.write(head.toString().toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // 单个连接异常不影响服务
        }
    }

    private fun handleRpc(body: String): String {
        return try {
            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method")
            if (id == null || id == JSONObject.NULL) return "" // 通知类消息，无响应
            when (method) {
                "initialize" -> result(id, JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION)))
                "tools/list" -> result(id, JSONObject().put("tools", tools()))
                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    callTool(id, params.optString("name"), params.optJSONObject("arguments"))
                }
                "ping" -> result(id, JSONObject())
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            error(JSONObject.NULL, -32700, "Parse error: ${e.message}")
        }
    }

    private fun tools(): JSONArray = JSONArray()
        .put(tool("device_info", "获取设备品牌、型号、Android 版本等基本信息", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())))
        .put(tool("app_status", "获取本 App 运行状态（包名、版本、沙盒状态、MCP 端口）", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())))
        .put(tool("shell_exec", "在沙盒内执行 shell 命令（破坏性/提权/写系统路径的命令会被拦截）", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("command", JSONObject()
                .put("type", "string")
                .put("description", "要执行的 shell 命令")))
            .put("required", JSONArray().put("command"))))

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", schema)

    private fun callTool(id: Any?, name: String, arguments: JSONObject?): String {
        return when (name) {
            "device_info" -> {
                val info = JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("board", Build.BOARD)
                rpcResult(id, textContent(info.toString(2)))
            }
            "app_status" -> {
                val ctx = appContext
                val status = JSONObject()
                    .put("packageName", BuildConfig.APPLICATION_ID)
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("sandboxEnabled", Sandbox.enabled)
                    .put("mcpPort", port)
                    .put("mcpState", state.value)
                    .put("adbConnected", ctx != null && AdbShellController.isConnected())
                rpcResult(id, textContent(status.toString(2)))
            }
            "shell_exec" -> {
                val cmd = arguments?.optString("command").orEmpty().trim()
                if (cmd.isEmpty()) return rpcError(id, "缺少 command 参数")
                val blocked = Sandbox.intercept(cmd)
                if (blocked != null) {
                    Sandbox.record(blocked)
                    return rpcError(id, blocked)
                }
                val writeTarget = extractWriteTarget(cmd)
                if (writeTarget != null) {
                    val pathBlocked = Sandbox.interceptWritePath(writeTarget)
                    if (pathBlocked != null) {
                        Sandbox.record(pathBlocked)
                        return rpcError(id, pathBlocked)
                    }
                }
                val ctx = appContext
                if (ctx == null) return rpcError(id, "应用上下文未初始化")
                WdbContext.init(ctx)
                if (!AdbShellController.isConnected() && !AdbShellController.ensureConnected()) {
                    return rpcError(id, "无线调试通道未连接，请先在设置页开启无线调试并配对")
                }
                val r = AdbShellController.exec(cmd)
                if (r.ok) rpcResult(id, textContent(r.output.trim())) else rpcError(id, "命令执行失败：" + r.output.trim())
            }
            else -> rpcError(id, "Unknown tool: $name")
        }
    }

    /** 提取重定向目标路径：`> xxx` / `>> xxx`（跳过 /dev/null）。 */
    private fun extractWriteTarget(cmd: String): String? {
        val m = Regex("""(?:^|[\s;&|])(?:1?>>?|2>>?)\s*([^\s;&|]+)""").find(cmd) ?: return null
        val target = m.groupValues[1]
        if (target.startsWith("/dev/")) return null
        return target
    }

    private fun textContent(text: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun rpcResult(id: Any?, payload: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", payload)
        .toString()

    private fun rpcError(id: Any?, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", -32000).put("message", message))
        .toString()

    private fun result(id: Any?, payload: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", payload)
        .toString()

    private fun error(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()
}
