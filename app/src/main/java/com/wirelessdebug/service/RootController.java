package com.wirelessdebug.service;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root 最高权限控制：检测已 root 设备并直接以 su 执行系统命令（uid 0）。
 * 开启 {@link #enabled} 后，设备控制（点击/滑动/输入/读屏/打开应用等）默认走最高权限。
 */
public class RootController {
    private static final String TAG = "RootController";

    /** 是否启用 root 优先（默认开启：有 root 就用最高权限）。 */
    public static volatile boolean enabled = true;

    private static volatile Boolean rooted;
    private static volatile long checkAtMs;

    /** 检测设备是否已 root（su 可用且能执行），结果缓存 15s。 */
    public static boolean isRooted() {
        long now = System.currentTimeMillis();
        if (rooted != null && now - checkAtMs < 15000) return rooted;
        boolean ok = runSu(new String[] { "-c", "id" }) != null;
        rooted = ok;
        checkAtMs = now;
        return ok;
    }

    /** root 通道可用且已启用。 */
    public static boolean canUseRoot() {
        return enabled && isRooted();
    }

    public static class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    /** 以 root 身份执行命令（su -c），超时 20s；失败返回 null（无 root / 被拒 / 超时）。 */
    public static Result exec(String... cmd) {
        return execAs(0, cmd);
    }

    /**
     * 以指定 uid 通过 su 执行命令（Magisk 支持 su <uid> -c）。
     * uiautomator 等读屏命令在 SELinux 下必须以 shell(2000) 身份运行，root 会被拒绝。
     */
    public static Result execAs(int uid, String... cmd) {
        if (cmd == null || cmd.length == 0) return new Result(false, "empty command");
        String joined = String.join(" ", cmd);
        // Magisk 语法：su <uid> -c "<cmd>"
        String out = runSu(new String[] { String.valueOf(uid), "-c", joined });
        if (out == null) return new Result(false, "su unavailable");
        return new Result(true, out.trim());
    }

    /** 以 shell(2000) 身份通过 su 执行（用于需要 shell 域的命令）。 */
    public static Result execAsShell(String... cmd) {
        return execAs(2000, cmd);
    }

    /** 通过 su 执行单条命令，返回输出；任何异常/超时返回 null。 */
    private static String runSu(String[] suArgs) {
        try {
            String[] full = new String[suArgs.length + 1];
            full[0] = "su";
            System.arraycopy(suArgs, 0, full, 1, suArgs.length);
            Process p = new ProcessBuilder(full)
                    .redirectErrorStream(true)
                    .start();
            String out = readAll(p.getInputStream());
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroy();
                Log.w(TAG, "su timeout: " + String.join(" ", suArgs));
                return null;
            }
            return out;
        } catch (Throwable t) {
            Log.d(TAG, "su failed: " + t.getMessage());
            return null;
        }
    }

    private static String readAll(InputStream is) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
