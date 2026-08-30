package com.wirelessdebug.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku 自动启动器：把电脑端 start_shizuku.bat 的流程内置到应用内。
 * 设备有 root 时每次启动全自动（root 模式 server 免授权）；无 root 时提示用电脑脚本。
 * 流程：确保内置 manager 已安装 -> 部署 starter/start.sh 到 /data/local/tmp -> 执行启动。
 */
public class ShizukuAutoStart {
    private static final String TAG = "ShizukuAutoStart";
    private static final String TMP_STARTER = "/data/local/tmp/shizuku_starter";
    private static final String TMP_START = "/data/local/tmp/start.sh";
    private static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /** 通过 mDNS 查询 Android 11+ 无线调试端口（adbd 广播 _adb-tls-connect._tcp），失败返回 -1。 */
    /** 通过 mDNS 查询 Android 11+ 无线调试端口（adbd 广播 _adb-tls-connect._tcp），失败返回 -1。 */
    /** 扫描 127.0.0.1 动态端口范围（30000-60000）找出可连接的监听端口（无线调试 adbd）。 */
    private static java.util.List<Integer> scanAdbPorts() {
        final java.util.concurrent.ConcurrentLinkedQueue<Integer> found = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(256);
        for (int port = 30000; port <= 60000; port++) {
            final int p = port;
            pool.execute(() -> {
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", p), 120);
                    s.close();
                    found.add(p);
                } catch (Throwable ignored) {}
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return new java.util.ArrayList<>(found);
    }

    /** 通过 NsdManager（系统 mDNS 栈）查询 Android 11+ 无线调试端口，失败返回 -1。 */
    private static int discoverWirelessAdbPort(Context ctx) {
        final java.util.concurrent.atomic.AtomicInteger port = new java.util.concurrent.atomic.AtomicInteger(-1);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            final android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) ctx.getApplicationContext()
                    .getSystemService(Context.NSD_SERVICE);
            final android.net.nsd.NsdManager.DiscoveryListener l = new android.net.nsd.NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(android.net.nsd.NsdServiceInfo info) {
                    int p = info.getPort();
                    Log.d(TAG, "nsd found " + info.getServiceName() + " port=" + p);
                    if (p > 0) { port.set(p); latch.countDown(); }
                    try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) {}
                }
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onServiceLost(android.net.nsd.NsdServiceInfo info) {}
                @Override public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onDiscoveryStopped(String t) {}
            };
            nsd.discoverServices("_adb-tls-connect", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l);
            latch.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
            try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) {}
            if (port.get() > 0) return port.get();
        } catch (Throwable t) {
            Log.d(TAG, "nsd discover failed: " + t.getMessage());
        }
        // 回退：原始 socket mDNS 查询（部分 ROM 的系统 mDNS 栈不暴露 adb 服务）
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        android.net.wifi.WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("shizuku-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable ignored) {}
        try {
            java.net.DatagramSocket sock = new java.net.DatagramSocket();
            sock.setSoTimeout(2500);
            byte[] q = buildMdnsPtrQuery();
            sock.send(new java.net.DatagramPacket(q, q.length,
                    java.net.InetAddress.getByName("224.0.0.251"), 5353));
            long deadline = System.currentTimeMillis() + 3000;
            java.util.List<String> instances = new java.util.ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[4096];
                java.net.DatagramPacket pkt = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                instances.clear();
                int off = parseMdnsPtr(buf, pkt.getLength(), instances);
                if (off > 0) {
                    for (String inst : instances) {
                        int p = queryMdnsSrv(sock, inst);
                        if (p > 0) return p;
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "mdns socket discover failed: " + t.getMessage());
        } finally {
            try { if (lock != null) lock.release(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static byte[] buildMdnsPtrQuery() {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeU16(b, 0);                    // transaction id
        writeU16(b, 0x0000);               // flags: standard query
        writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
        writeName(b, "_adb-tls-connect._tcp.local");
        writeU16(b, 12); writeU16(b, 1);   // PTR IN
        return b.toByteArray();
    }

    /** 解析 PTR 响应中的服务实例名；返回剩余可用偏移或 -1。 */
    private static int parseMdnsPtr(byte[] buf, int len, java.util.List<String> instances) {
        try {
            int off = 12; // skip header
            // skip question section (we asked 1 question)
            int qnameEnd = skipName(buf, off, len);
            if (qnameEnd < 0) return -1;
            off = qnameEnd + 4; // type + class
            while (off + 10 <= len) {
                int nameEnd = skipName(buf, off, len);
                if (nameEnd < 0) return -1;
                int type = u16(buf, nameEnd), clazz = u16(buf, nameEnd + 2);
                int rdlen = u16(buf, nameEnd + 6);
                int rd = nameEnd + 8;
                if (type == 12 && clazz == 1 && rd + rdlen <= len) {
                    int p = rd;
                    if ((buf[p] & 0xc0) == 0xc0) {
                        int nameOff = ((buf[p] & 0x3f) << 8) | (buf[p + 1] & 0xff);
                        String inst = readName(buf, nameOff, len);
                        if (inst != null) instances.add(inst);
                    } else {
                        String inst = readNameAt(buf, p, len);
                        if (inst != null) instances.add(inst);
                    }
                }
                off = rd + rdlen;
            }
            return off;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 查询指定实例的 SRV 记录获取端口。 */
    private static int queryMdnsSrv(java.net.DatagramSocket sock, String instance) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            writeU16(b, 0); writeU16(b, 0x0000);
            writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
            writeName(b, instance + "._adb-tls-connect._tcp.local");
            writeU16(b, 33); writeU16(b, 1);   // SRV IN
            byte[] q = b.toByteArray();
            sock.send(new java.net.DatagramPacket(q, q.length,
                    java.net.InetAddress.getByName("224.0.0.251"), 5353));
            long deadline = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[4096];
                java.net.DatagramPacket pkt = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                int off = 12;
                int qnameEnd = skipName(buf, off, pkt.getLength());
                if (qnameEnd < 0) continue;
                off = qnameEnd + 4;
                while (off + 10 <= pkt.getLength()) {
                    int nameEnd = skipName(buf, off, pkt.getLength());
                    if (nameEnd < 0) break;
                    int type = u16(buf, nameEnd), clazz = u16(buf, nameEnd + 2);
                    int rdlen = u16(buf, nameEnd + 6);
                    int rd = nameEnd + 8;
                    if (type == 33 && clazz == 1 && rd + rdlen <= pkt.getLength()) {
                        int port = u16(buf, rd + 2); // priority(2) weight(2) port(2)
                        if (port > 0) return port;
                    }
                    off = rd + rdlen;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "mdns srv failed: " + t.getMessage());
        }
        return -1;
    }

    private static void writeName(java.io.ByteArrayOutputStream b, String name) {
        for (String label : name.split("\\.")) {
            byte[] lb = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            b.write(lb.length);
            b.write(lb, 0, lb.length);
        }
        b.write(0);
    }

    private static void writeU16(java.io.ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 0xff); b.write(v & 0xff);
    }

    private static int u16(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    }

    private static int skipName(byte[] buf, int off, int len) {
        int p = off;
        while (p < len) {
            int l = buf[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xc0) == 0xc0) return p + 2;
            p += l + 1;
        }
        return -1;
    }

    private static String readName(byte[] buf, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int p = off;
        int jumps = 0;
        while (p < len && jumps++ < 20) {
            int l = buf[p] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) {
                p = ((buf[p] & 0x3f) << 8) | (buf[p + 1] & 0xff);
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(buf, p + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            p += l + 1;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String readNameAt(byte[] buf, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int p = off;
        int jumps = 0;
        while (p < len && jumps++ < 20) {
            int l = buf[p] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) {
                p = ((buf[p] & 0x3f) << 8) | (buf[p + 1] & 0xff);
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(buf, p + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            p += l + 1;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
    /** 通过设备本机无线调试端口启动 Shizuku server；成功返回 null，失败返回原因。 */

    /** 通过设备本机无线调试端口启动 Shizuku server；成功返回 null，失败返回原因。 */
    private static String startViaWirelessAdb(Context ctx) {
        StringBuilder diag = new StringBuilder();
        try {
            java.io.File dir = new java.io.File(ctx.getFilesDir(), ShizukuBootstrap.ASSET_DIR);
            java.io.File keyFile = new java.io.File(dir, ShizukuBootstrap.ADB_KEY);
            java.io.File starter = new java.io.File(dir, ShizukuBootstrap.STARTER);
            java.io.File startSh = new java.io.File(dir, ShizukuBootstrap.START_SH);
            java.io.File managerApk = new java.io.File(dir, ShizukuBootstrap.MANAGER_APK);
            if (!keyFile.exists() || !starter.exists() || !startSh.exists()) {
                writeDiag(ctx, "assets missing");
                return "内置 Shizuku 资产缺失，请重装应用";
            }
            java.security.PrivateKey key = null;
            try { key = AdbClient.loadKey(readText(keyFile)); } catch (Throwable ignored) {}
            diag.append("keyLoaded=").append(key != null).append("\n");
            int mdnsPort = discoverWirelessAdbPort(ctx);
            diag.append("mdnsPort=").append(mdnsPort).append("\n");
            java.util.List<Integer> scanPorts = mdnsPort > 0 ? java.util.Collections.<Integer>emptyList() : scanAdbPorts();
            diag.append("scanPorts=").append(scanPorts.toString()).append("\n");
            // 第一轮：5555（模拟器/USB 网络 adb）+ mDNS 端口，多 host 尝试
            java.util.LinkedHashSet<Integer> fastSet = new java.util.LinkedHashSet<>();
            fastSet.add(5555);
            if (mdnsPort > 0) fastSet.add(mdnsPort);
            AdbClient adb = null;
            boolean ok = false;
            String[] hosts = {"127.0.0.1", "10.0.2.15", "10.0.2.2"};
            for (int port : fastSet) {
                for (String host : hosts) {
                    for (boolean useTls : new boolean[]{false, true}) {
                        try {
                            adb = new AdbClient();
                            adb.tls = useTls;
                            if (adb.connect(host, port, key)) {
                                diag.append("CONNECT_OK host=").append(host).append(" port=").append(port).append(" tls=").append(useTls).append("\n");
                                ok = true; break;
                            }
                            diag.append("connect_false host=").append(host).append(":").append(port).append(" tls=").append(useTls).append("\n");
                        } catch (Throwable t) {
                            diag.append("connect_ERR host=").append(host).append(":").append(port).append(" tls=").append(useTls)
                                .append(" msg=").append(t.getMessage() != null ? t.getMessage() : t.toString()).append("\n");
                        } finally {
                            if (!ok && adb != null) { adb.close(); adb = null; }
                        }
                    }
                    if (ok) break;
                }
                if (ok) break;
            }
            // 第二轮：扫描发现的端口（只连 127.0.0.1，最多 8 个）
            if (!ok) {
                int tried = 0;
                for (int port : scanPorts) {
                    if (tried++ >= 8) break;
                    for (boolean useTls : new boolean[]{false, true}) {
                        try {
                            adb = new AdbClient();
                            adb.tls = useTls;
                            if (adb.connect("127.0.0.1", port, key)) {
                                diag.append("CONNECT_OK host=127.0.0.1 port=").append(port).append(" tls=").append(useTls).append("\n");
                                ok = true; break;
                            }
                            diag.append("connect_false host=127.0.0.1:").append(port).append(" tls=").append(useTls).append("\n");
                        } catch (Throwable t) {
                            diag.append("connect_ERR host=127.0.0.1:").append(port).append(" tls=").append(useTls)
                                .append(" msg=").append(t.getMessage() != null ? t.getMessage() : t.toString()).append("\n");
                        } finally {
                            if (!ok && adb != null) { adb.close(); adb = null; }
                        }
                    }
                    if (ok) break;
                }
            }
            if (!ok) {
                writeDiag(ctx, diag.toString());
                return "无线调试连接失败（请开启开发者选项中的无线调试）";
            }
            // 1) 确保内置 Shizuku 已安装
            String pkgs = adb.shell("pm list packages moe.shizuku.privileged.api");
            diag.append("pmList=").append(pkgs == null ? "null" : pkgs.trim()).append("\n");
            if (pkgs == null || !pkgs.contains("moe.shizuku.privileged.api")) {
                if (!managerApk.exists()) { adb.close(); writeDiag(ctx, diag.toString()); return "内置 Shizuku 包缺失"; }
                adb.push(readBytes(managerApk), "/data/local/tmp/shizuku_manager.apk", "0644");
                String inst = adb.shell("pm install -r /data/local/tmp/shizuku_manager.apk");
                diag.append("install=").append(inst == null ? "null" : inst.trim()).append("\n");
            }
            // 2) 部署引导程序
            adb.push(readBytes(starter), "/data/local/tmp/shizuku_starter", "0700");
            adb.push(readText(startSh).getBytes(java.nio.charset.StandardCharsets.UTF_8), "/data/local/tmp/start.sh", "0700");
            diag.append("deployed\n");
            // 3) 启动 server
            String startOut = adb.shell("sh /data/local/tmp/start.sh");
            diag.append("startOut=").append(startOut == null ? "null" : startOut.trim().replace("\n", "|")).append("\n");
            adb.close();
            // 4) 验证
            for (int i = 0; i < 10 && !ShizukuController.isAvailable(); i++) {
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            }
            diag.append("shizukuAvailable=").append(ShizukuController.isAvailable()).append("\n");
            writeDiag(ctx, diag.toString());
            if (!ShizukuController.isAvailable()) return "server 启动失败（无线调试），请查看日志";
            return null;
        } catch (Throwable t) {
            diag.append("EXCEPTION=").append(t.getMessage() != null ? t.getMessage() : t.toString()).append("\n");
            Log.e(TAG, "startViaWirelessAdb failed", t);
            writeDiag(ctx, diag.toString());
            return "无线调试启动失败：" + (t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /** 写诊断日志到应用私有目录（run-as 可读），规避 ColorOS 等 ROM 对第三方 logcat 的限制。 */
    private static void writeDiag(Context ctx, String content) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "shizuku_diag.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {}
    }

    private static String readText(java.io.File f) throws Exception {
        return new String(readBytes(f), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(java.io.File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        in.close();
        return buf;
    }

    /** 检测设备是否具有 root（su）。不能只看 su 文件是否存在，必须实际执行验证。 */
    public static boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            return code == 0 && out != null && out.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自动启动入口：后台线程执行。成功 -> server 运行；失败 -> 通过回调给出原因。 */
    public static void tryStart(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                ShizukuBootstrap.ensureAssets(ctx);
                if (ShizukuController.isAvailable()) {
                    cb.onResult(true, "Shizuku 已运行");
                    return;
                }
                if (!hasRoot()) {
                    // 无 root：直连本机无线调试端口（内置已授权 key + TLS 客户端证书），
                    // 以 shell 身份执行命令控制手机，无需安装 Shizuku/任何包。
                    ShizukuBootstrap.ensureAssets(ctx);
                    AdbShellController.Result r = AdbShellController.exec("id");
                    writeDiag(ctx, "exec id ok=" + r.ok + " out=" + (r.output == null ? "null" : r.output.replace("\n", "|")));
                    if (r.ok && r.output != null
                            && (r.output.contains("uid=2000") || r.output.contains("uid=0") || r.output.contains("shell"))) {
                        cb.onResult(true, "无线调试控制已就绪（免安装，可直接控制手机）");
                    } else {
                        cb.onResult(false, "无线调试连接失败：" + ((r.output == null || r.output.isEmpty())
                            ? "请开启开发者选项中的\u201c无线调试\u201d" : r.output));
                    }
                    return;
                }
                String step;
                step = "安装内置 Shizuku";
                ensureManagerInstalled(ctx);
                step = "部署引导程序";
                deployAssets(ctx);
                step = "启动 Shizuku server";
                execRoot("sh " + TMP_START);
                boolean ok = ShizukuController.isAvailable();
                cb.onResult(ok, ok ? "Shizuku 自动启动成功" : "启动失败（" + step + "），请查看日志");
            } catch (Throwable t) {
                Log.e(TAG, "tryStart failed", t);
                cb.onResult(false, "自动启动异常：" + (t.getMessage() != null ? t.getMessage() : t.toString()));
            }
        }, "shizuku-auto-start").start();
    }

    private static void ensureManagerInstalled(Context ctx) throws Exception {
        try {
            ctx.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return;
        } catch (PackageManager.NameNotFoundException ignored) {}
        File apk = new File(ctx.getFilesDir(), ShizukuBootstrap.ASSET_DIR + "/" + ShizukuBootstrap.MANAGER_APK);
        if (!apk.exists()) throw new IllegalStateException("内置 Shizuku 包缺失");
        String remote = "/data/local/tmp/shizuku_manager.apk";
        execRoot("cp " + apk.getAbsolutePath() + " " + remote + " && chmod 644 " + remote);
        String r = execRoot("pm install -r " + remote);
        if (r != null && (r.contains("Success") || r.contains("success"))) return;
        execRoot("pm install -r -t " + remote); // 部分系统需要 -t
    }

    private static void deployAssets(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), ShizukuBootstrap.ASSET_DIR);
        File starter = new File(dir, ShizukuBootstrap.STARTER);
        File startSh = new File(dir, ShizukuBootstrap.START_SH);
        if (!starter.exists() || !startSh.exists()) throw new IllegalStateException("内置资产缺失");
        execRoot("cp " + starter.getAbsolutePath() + " " + TMP_STARTER + " && chmod 700 " + TMP_STARTER
            + " && chown 2000 " + TMP_STARTER + " && chgrp 2000 " + TMP_STARTER);
        execRoot("cp " + startSh.getAbsolutePath() + " " + TMP_START + " && chmod 700 " + TMP_START);
    }

    private static String execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            p.waitFor();
            String merged = (out != null ? out : "") + (err != null && !err.isEmpty() ? "\n" + err : "");
            Log.d(TAG, "su> " + cmd + "\n" + merged.trim());
            return merged;
        } catch (Throwable t) {
            Log.e(TAG, "su exec failed: " + cmd, t);
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
        } catch (Exception e) {
            return "";
        }
    }
}




