package com.xs.chat.plugins

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 应用内嵌入式 Python 3.14 引擎（JNI，无需 Root / 无线调试 / 文件可执行权限）。
 *
 * 背景：部分 ROM 的 SELinux 收紧后，App 无法 exec 应用私有目录下的 ELF
 * （avc: denied { execute_no_trans }），此前「本地直跑解释器」在这些机型上必然失败。
 * 本引擎改为把 CPython 运行时打包进 APK lib/（jniLibs），通过 dlopen 加载，
 * 完全规避 exec 权限；执行路径为 libxspy.so -> libpython3.14.so（纯动态库加载，合法）。
 */
object PyEngine {
    private const val TAG = "PyEngine"
    private const val SEP = "\u001e"

    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    /** 本机是 arm64 且动态库加载成功，才可使用嵌入式引擎。 */
    fun available(context: Context): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            loadError = "当前设备 ABI 不支持嵌入式 Python（仅 arm64-v8a）"
            return false
        }
        if (loaded) return true
        return try {
            System.loadLibrary("xspy")
            loaded = true
            true
        } catch (t: Throwable) {
            loadError = "加载嵌入式 Python 引擎失败：" + (t.message ?: t.javaClass.simpleName)
            Log.e(TAG, loadError!!)
            false
        }
    }

    fun loadErrorText(): String? = loadError

    private external fun nativeRun(home: String, nativeLibDir: String, code: String, args: String): ByteArray

    /**
     * 执行 Python 代码（stdout 与 stderr 合并返回）。
     * @return Pair(是否成功, 输出文本)
     */
    fun run(context: Context, code: String, args: String = ""): Pair<Boolean, String> {
        val home = File(context.filesDir, "xsrt").absolutePath
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val raw = try {
            String(nativeRun(home, nativeDir, code, args), StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            return false to ("嵌入式 Python 执行异常：" + (t.message ?: t.javaClass.simpleName))
        }
        val idx = raw.indexOf(SEP)
        if (idx < 0) return false to raw.take(200_000)
        val out = raw.substring(0, idx)
        val err = raw.substring(idx + 1)
        if (err.isBlank()) return true to out.take(200_000)
        return false to err.take(200_000)
    }
}
