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
        boolean ok = exec("id").ok;
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
     * 注意：调用方不要传入含单引号的命令（内部以 su <uid> -c 'cmd' 整条执行）。
     */
    public static Result execAs(int uid, String... cmd) {
        if (cmd == null || cmd.length == 0) return new Result(false, "empty command");
        String joined = String.join(" ", cmd);
        ExecOut r = runSu(new String[] { String.valueOf(uid), "-c", joined });
        if (r == null) return new Result(false, "su unavailable");
        // 探测类命令（test/prob）以退出码为准, stdout 可能被 magisk 吞掉
        return new Result(r.exit == 0, r.output.trim());
    }

    /** 以 shell(2000) 身份通过 su 执行（用于需要 shell 域的命令）。 */
    public static Result execAsShell(String... cmd) {
        return execAs(2000, cmd);
    }

    /** 通过 su 执行单条命令，返回退出码与输出；任何异常/超时返回 null。 */
    private static synchronized ExecOut runSu(String[] suArgs) {
        try {
            String uid = suArgs.length > 0 ? suArgs[0] : "0";
            String cmdLine = suArgs.length > 2 ? suArgs[2] : String.join(" ", suArgs);
            String line = "/system/bin/su " + uid + " -c '" + cmdLine + "'";
            Log.i(TAG, "su call: " + line);
            Process p = new ProcessBuilder("/system/bin/sh", "-c", line)
                    .redirectErrorStream(true)
                    .start();
            // 不用 Process.waitFor(超时): 个别 ROM 上子进程退出后 waitFor 仍可永久沉睡；
            // 改为有界轮询 exitValue()，超时强制销毁。
            long deadline = System.currentTimeMillis() + 20000;
            int exit = -1;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = p.exitValue();
                    break;
                } catch (IllegalThreadStateException alive) {
                    try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (exit < 0) {
                p.destroyForcibly();
                Log.w(TAG, "su timeout: " + String.join(" ", suArgs));
                return null;
            }
            String out = readAll(p.getInputStream());
            Log.i(TAG, "su ok exit=" + exit + " out=[" + (out == null ? "" : out.trim()) + "]");
            return new ExecOut(exit, out == null ? "" : out);
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

    private static final class ExecOut {
        final int exit;
        final String output;
        ExecOut(int exit, String output) { this.exit = exit; this.output = output; }
    }
}