package com.wirelessdebug.service;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 免权限手机控制入口：通过 Shizuku 以 shell 身份执行系统命令（input/am/uiautomator/cmd/pm），
 * 无需开启无障碍服务。一次 adb 授权 Shizuku 后即可控制手机。
 */
public class ShizukuController {
    private static final String TAG = "ShizukuController";
    private static final int REQ_CODE = 10086;

    public static boolean isAvailable() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable t) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    /** 发起 Shizuku 授权请求（系统会弹出授权确认框）。 */
    public static void requestPermission() {
        try {
            if (!isAvailable() || hasPermission()) return;
            if (Shizuku.shouldShowRequestPermissionRationale()) return;
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) { Log.e(TAG, "requestPermission failed", t); }
    }

    public static class ExecResult {
        public final boolean ok;
        public final String output;
        ExecResult(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    /** 以 shell 身份执行命令并返回输出，超时 25s。 */
    public static ExecResult exec(String... cmd) {
        if (cmd != null && cmd.length > 0) {
            // Root 模式（默认开启）：已 root 设备直接以最高权限执行。
            // uiautomator 读屏在 SELinux 下拒绝 root，须以 shell(2000) 身份运行
            if (RootController.canUseRoot()) {
                RootController.Result rr = "uiautomator".equals(cmd[0])
                    ? RootController.execAsShell(cmd)
                    : RootController.exec(cmd);
                if (rr.ok) return new ExecResult(true, rr.output);
            }
            StringBuilder line = new StringBuilder();
            for (String c : cmd) {
                if (line.length() > 0) line.append(' ');
                line.append(c);
            }
            AdbShellController.Result r = AdbShellController.exec(line.toString());
            if (r.ok) return new ExecResult(true, r.output);
        }
        try {
            Process p = newProcess(cmd);
            if (p == null) return new ExecResult(false, "Shizuku.newProcess unavailable");
            // ShizukuRemoteProcess 的 isAlive/exitValue 在进程退出的瞬间会抛
            // "process hasn't exited"（远程跟踪竞态），这里直接轮询 exitValue 直到进程退出
            long deadline = System.currentTimeMillis() + 25000;
            int exit = -1;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = p.exitValue();
                    break;
                } catch (Throwable notExited) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (exit < 0) {
                try { p.destroy(); } catch (Throwable ignored) {}
                return new ExecResult(false, "command timeout: " + String.join(" ", cmd));
            }
            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            String merged = (out != null && !out.isEmpty() ? out : "")
                + (err != null && !err.isEmpty() ? (out != null && !out.isEmpty() ? "\n" : "") + err : "");
            return new ExecResult(exit == 0, merged);
        } catch (Throwable t) {
            return new ExecResult(false, t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /**
     * 反射调用 Shizuku 内部 private newProcess：以 shell 身份启动进程。
     * 13.x 中该方法被标记为 private，但仍是官方库的稳定实现。
     */
    private static Process newProcess(String[] cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, (Object) cmd, null, null);
        } catch (Throwable t) {
            Log.e(TAG, "newProcess failed", t);
            return null;
        }
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}

