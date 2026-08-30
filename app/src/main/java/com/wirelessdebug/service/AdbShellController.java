package com.wirelessdebug.service;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.wirelessdebug.WdbContext;
import com.wirelessdebug.PairState;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无 root 无线调试控制通道：app 用内置的已授权 adb key（PC key + 官方格式证书）
 * 直连本机 Android 11+ 无线调试端口（STLS 升级 + TLS1.3），以 shell 身份执行
 * input/am/uiautomator 等命令控制手机。无需 Shizuku、无需安装任何东西。
 */
public class AdbShellController {
    private static final String TAG = "AdbShellController";
    private static final Object LOCK = new Object();

    private static AdbClient adb;
    private static long lastUsedMs;
    private static long lastFailMs;
    /** 设备侧拒绝内置 key（授权失效/被撤销），需重新配对。 */
    private static volatile boolean authRejected = false;

    public static class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    public static boolean isConnected() {
        synchronized (LOCK) { return adb != null; }
    }

    /** 确保无线调试通道已连接；未连接时尝试建立（mDNS/扫描+握手，可能耗时数秒）。 */
    public static boolean ensureConnected() {
        synchronized (LOCK) {
            try {
                return getClient() != null;
            } catch (Throwable t) {
                Log.e(TAG, "ensureConnected failed", t);
                return false;
            }
        }
    }

    /** shell 命令输出判定：含异常/拒绝/未找到等失败特征时视为失败（部分 ROM 拒绝但仍返回 exit 0）。 */
    static boolean looksLikeError(String out) {
        if (out == null || out.isEmpty()) return false;
        String s = out.trim().toLowerCase(java.util.Locale.US);
        if (s.isEmpty()) return false;
        return s.contains("securityexception")
            || s.contains("permission denial")
            || s.contains("does not have")
            || s.contains("exception occurred")
            || s.contains("not found")
            || s.contains("unknown command")
            || s.contains("error:");
    }

    /** 空闲超时关闭连接（避免无线调试关闭后残留）。 */
    public static void maybeCloseIdle() {
        synchronized (LOCK) {
            if (adb != null && System.currentTimeMillis() - lastUsedMs > 60_000) close();
        }
    }

    /** 以 shell 身份执行命令；失败自动重连一次。 */
    public static Result exec(String cmd) {
        synchronized (LOCK) {
            try {
                AdbClient c = getClient();
                if (c == null) return new Result(false, "无线调试不可用：请开启开发者选项中的\"无线调试\"");
                String out = c.shell(cmd);
                lastUsedMs = System.currentTimeMillis();
                return new Result(!looksLikeError(out), out == null ? "" : out);
            } catch (Throwable t) {
                Log.e(TAG, "exec failed: " + cmd, t);
                writeDiag(WdbContext.get(), "EXEC_ERR[" + cmd + "] " + (t.getMessage() != null ? t.getMessage() : t.toString()));
                close();
                try {
                    AdbClient c = getClient();
                    if (c == null) return new Result(false, "无线调试连接失败：" + t.getMessage());
                    String out = c.shell(cmd);
                    lastUsedMs = System.currentTimeMillis();
                    return new Result(!looksLikeError(out), out == null ? "" : out);
                } catch (Throwable t2) {
                    close();
                    return new Result(false, t2.getMessage() != null ? t2.getMessage() : t2.toString());
                }
            }
        }
    }

    /**
     * 新连接执行单条命令（执行后立即断开）：绕开复用连接的 CLSE 未确认导致的输出错位，
     * 保证命令输出与本次命令严格对应（PiP 定位等依赖精确输出的场景必须用它）。
     */
    public static Result execFresh(String cmd) {
        synchronized (LOCK) {
            try {
                close();
                AdbClient c = getClient();
                if (c == null) return new Result(false, "无线调试不可用：请开启开发者选项中的\"无线调试\"");
                String out = c.shell(cmd);
                close();
                lastUsedMs = System.currentTimeMillis();
                return new Result(!looksLikeError(out), out == null ? "" : out);
            } catch (Throwable t) {
                close();
                return new Result(false, t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }
    }

    /** 获取（或建立）连接。 */
    /** 设备侧是否拒绝过内置 key（授权失效/被撤销）。 */
    public static boolean isAuthRejected() { return authRejected; }

    private static AdbClient getClient() throws Exception {
        if (adb != null) return adb;
        // 失败冷却：8 秒内不重复扫描/连接，快速回退
        if (lastFailMs > 0 && System.currentTimeMillis() - lastFailMs < 8_000) return null;
        Context ctx = WdbContext.get();
        if (ctx == null) return null;
        // 1) 加载内置 key + 客户端证书（PEM 直载，Android 原生解析）
        File dir = new File(ctx.getFilesDir(), ShizukuBootstrap.ASSET_DIR);
        ShizukuBootstrap.ensureAssets(ctx);
        File keyFile = new File(dir, ShizukuBootstrap.ADB_KEY);
        File certFile = new File(dir, ShizukuBootstrap.ADB_CERT);
        if (!keyFile.exists() || !certFile.exists()) return null;
        PrivateKey key = AdbClient.loadKey(readText(keyFile));
        String certPem = readText(certFile);
        // 2) 发现无线调试端口（缓存/mDNS/本机监听端口，含 IPv6）
        List<Integer> candidates = discoverPorts(ctx);
        // 3) 逐个尝试连接（IPv4/IPv6 双栈，鸿蒙等 ROM 的 adbd 常仅监听 IPv6）
        for (int port : candidates) {
            AdbClient c = tryConnectPort(port, key, certPem);
            if (c != null) {
                adb = c;
                lastUsedMs = System.currentTimeMillis();
                cachePort(ctx, port);
                PairState.markPaired(ctx);
                Log.i(TAG, "connected via wireless adb port " + port);
                writeDiag(ctx, "connected port=" + port + " host=" + hostProbe(port));
                return adb;
            }
        }
        // 候选全部失败：补充一次全量监听端口兜底（/proc 不可读时）
        for (int port : scanLocalPorts()) {
            if (candidates.contains(port)) continue;
            AdbClient c = tryConnectPort(port, key, certPem);
            if (c != null) {
                adb = c;
                lastUsedMs = System.currentTimeMillis();
                cachePort(ctx, port);
                PairState.markPaired(ctx);
                Log.i(TAG, "connected via scan port " + port);
                writeDiag(ctx, "connected port=" + port + " host=" + hostProbe(port));
                return adb;
            }
        }
        lastFailMs = System.currentTimeMillis();
        writeDiag(ctx, "no port connected. candidates=" + candidates + " mdns=" + discoverMdns(ctx));
        return null;
    }

    /** 双栈连接尝试：先 IPv4 后 IPv6，连接+认证成功才返回客户端。 */
    private static AdbClient tryConnectPort(int port, PrivateKey key, String certPem) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            AdbClient c = new AdbClient();
            c.tls = false;
            c.setClientCert(key, certPem);
            try {
                if (c.connect(host, port, key)) { authRejected = false; return c; }
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg != null && (msg.contains("not accepted") || msg.contains("not authorized")
                        || msg.contains("CERTIFICATE_UNKNOWN") || msg.contains("certificate unknown")
                        || msg.contains("auth failed") || msg.contains("SSLV3_ALERT"))) {
                    authRejected = true;
                }
                Log.d(TAG, "connect fail host=" + host + " port=" + port + ": " + msg);
            }
            try { c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 探测端口是否为无线调试 TLS 端口（明文 CNXN 回 STLS 即确认，IPv4/IPv6 双栈）。 */
    private static String hostProbe(int port) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 500);
                s.setSoTimeout(800);
                s.getOutputStream().write(buildPacket("CNXN", 0x01000000, 256 * 1024, "device::".getBytes(StandardCharsets.UTF_8)));
                s.getOutputStream().flush();
                byte[] hdr = new byte[24];
                int off = 0;
                while (off < 24) {
                    int r = s.getInputStream().read(hdr, off, 24 - off);
                    if (r < 0) break;
                    off += r;
                }
                if (off >= 4) return new String(hdr, 0, 4, StandardCharsets.US_ASCII);
            } catch (Throwable ignored) {}
        }
        return "none";
    }

    private static byte[] buildPacket(String cmd, int arg0, int arg1, byte[] payload) {
        try {
            byte[] cbytes = cmd.getBytes(StandardCharsets.US_ASCII);
            int cmdInt = (cbytes[0] & 0xff) | ((cbytes[1] & 0xff) << 8) | ((cbytes[2] & 0xff) << 16) | ((cbytes[3] & 0xff) << 24);
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(cbytes);
            putLeInt(b, arg0); putLeInt(b, arg1); putLeInt(b, payload != null ? payload.length : 0);
            putLeInt(b, 0); putLeInt(b, cmdInt ^ 0xffffffff);
            if (payload != null) b.write(payload);
            return b.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }

    private static void putLeInt(java.io.ByteArrayOutputStream b, int v) {
        b.write(v & 0xff); b.write((v >> 8) & 0xff); b.write((v >> 16) & 0xff); b.write((v >> 24) & 0xff);
    }

    private static void writeDiag(Context ctx, String content) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "adb_shell_diag.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 端口发现：缓存端口 -> mDNS -> 本机端口扫描（TCP 连通性）。 */
    private static List<Integer> discoverPorts(Context ctx) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        // 明文 AUTH 端口（adb tcpip 5555）：ColorOS 无线调试 TLS 端口系统性不可用，优先此通道
        set.add(5555);
        int cached = getCachedPort(ctx);
        if (cached > 0) set.add(cached);
        int mdns = discoverMdns(ctx);
        if (mdns > 0) set.add(mdns);
        // /proc 监听端口（含 IPv6 监听）始终纳入候选，避免 mDNS 不通导致漏扫
        for (int p : scanLocalPorts()) set.add(p);
        return new ArrayList<>(set);
    }

    private static final String PREF = "adb_shell";
    private static final String PREF_PORT = "wireless_port";

    private static int getCachedPort(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(PREF_PORT, 0);
        } catch (Throwable t) { return 0; }
    }

    private static void cachePort(Context ctx, int port) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(PREF_PORT, port).apply();
        } catch (Throwable ignored) {}
    }

    private static int discoverMdns(Context ctx) {
        try {
            final AtomicInteger port = new AtomicInteger(-1);
            final CountDownLatch latch = new CountDownLatch(1);
            final NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            final NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(NsdServiceInfo info) {
                    int p = info.getPort();
                    Log.d(TAG, "nsd found " + info.getServiceName() + " port=" + p);
                    if (p > 0) { port.set(p); latch.countDown(); }
                    try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) {}
                }
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onDiscoveryStopped(String t) {}
            };
            nsd.discoverServices("_adb-tls-connect", NsdManager.PROTOCOL_DNS_SD, l);
            latch.await(3500, TimeUnit.MILLISECONDS);
            try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) {}
            if (port.get() > 0) return port.get();
        } catch (Throwable t) {
            Log.d(TAG, "nsd failed: " + t.getMessage());
        }
        return socketMdns(ctx);
    }

    /** 自动发现本机无线调试配对端口（用户打开「使用配对码配对设备」页面后由 mDNS 通告）。 */
    public static int findPairPort(Context ctx) {
        try {
            final AtomicInteger port = new AtomicInteger(-1);
            final CountDownLatch latch = new CountDownLatch(1);
            final NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            final NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(NsdServiceInfo info) {
                    int p = info.getPort();
                    if (p > 0) { port.set(p); latch.countDown(); }
                    try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) {}
                }
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
                @Override public void onStopDiscoveryFailed(String t, int e) {}
                @Override public void onDiscoveryStopped(String t) {}
            };
            nsd.discoverServices("_adb-tls-pairing", NsdManager.PROTOCOL_DNS_SD, l);
            latch.await(3500, TimeUnit.MILLISECONDS);
            try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) {}
            if (port.get() > 0) return port.get();
        } catch (Throwable t) {
            Log.d(TAG, "pair nsd failed: " + t.getMessage());
        }
        return socketMdnsPair(ctx);
    }
    private static volatile long lastScanMs;

    /** 轻量检测无线调试是否已开启（缓存端口 + mDNS + 本机端口扫描兜底）。ColorOS 等 ROM 的 mDNS 常超时，必须扫描兜底。 */
    public static boolean wirelessDebugReady(Context ctx) {
        if (isConnected()) return true;
        try {
            int cached = getCachedPort(ctx);
            if (cached > 0 && canConnectLoopback(cached)) return true;
            int mdns = discoverMdns(ctx);
            if (mdns > 0) {
                cachePort(ctx, mdns);
                return true;
            }
            // mDNS 失败：并发扫描本机端口，发现监听端口即视为无线调试已开启（带 10 秒冷却防刷）
            if (System.currentTimeMillis() - lastScanMs < 10_000) return false;
            lastScanMs = System.currentTimeMillis();
            for (int p : scanLocalPorts()) {
                if (p > 0) {
                    cachePort(ctx, p);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
    /** 读取 /proc/net/tcp + /proc/net/tcp6 中所有 LISTEN(0A) 本地端口（IPv4/IPv6 全兼容，鸿蒙配对服务常仅监听 IPv6）。 */
    private static java.util.Set<Integer> procListenPorts() {
        java.util.Set<Integer> ports = new java.util.LinkedHashSet<>();
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(path))) {
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    if (first) { first = false; continue; }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 4 || !"0A".equals(parts[3])) continue; // 0A = LISTEN
                    String local = parts[1];
                    int ci = local.indexOf(':');
                    if (ci < 0) continue;
                    try { ports.add(Integer.parseInt(local.substring(ci + 1), 16)); } catch (Exception ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return ports;
    }

    /** 候选端口：/proc 监听列表优先（覆盖 IPv6 监听）；为空时并发范围扫描兜底。 */
    private static java.util.List<Integer> pairPortCandidates() {
        java.util.List<Integer> fromProc = new java.util.ArrayList<>(procListenPorts());
        if (!fromProc.isEmpty()) return fromProc;
        final int from = 30000, to = 65535;
        final java.util.List<Integer> open = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final ExecutorService pool = Executors.newFixedThreadPool(96);
        try {
            for (int port = from; port <= to; port++) {
                if (open.size() >= 8) break;
                final int p = port;
                pool.execute(() -> {
                    if (open.size() >= 8) return;
                    if (canConnectLoopback(p)) open.add(p);
                });
            }
            pool.shutdown();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.d(TAG, "scan pair ports failed: " + t.getMessage());
        } finally {
            pool.shutdownNow();
        }
        return new java.util.ArrayList<>(open);
    }

    /** 回环连通性探测（IPv4 + IPv6 双栈）。 */
    private static boolean canConnectLoopback(int port) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 100);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 返回全部候选端口，TLS 配对端口优先（适配 IPv4 与 IPv6 监听）。 */
    public static java.util.List<Integer> scanPairPorts() {
        java.util.List<Integer> candidates = pairPortCandidates();
        final java.util.List<Integer> tlsFirst = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<Integer> rest = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        if (!candidates.isEmpty()) {
            final ExecutorService pool = Executors.newFixedThreadPool(Math.min(24, Math.max(4, candidates.size())));
            try {
                for (int p : candidates) {
                    final int port = p;
                    pool.execute(() -> {
                        if (isPairingTlsPort(port)) tlsFirst.add(port); else rest.add(port);
                    });
                }
                pool.shutdown();
                pool.awaitTermination(12, TimeUnit.SECONDS);
            } catch (Throwable t) {
                Log.d(TAG, "probe pair ports failed: " + t.getMessage());
            } finally {
                pool.shutdownNow();
            }
        }
        java.util.Collections.sort(tlsFirst);
        java.util.Collections.sort(rest);
        tlsFirst.addAll(rest);
        Log.d(TAG, "scanPairPorts -> " + tlsFirst);
        return tlsFirst;
    }

    /** 兼容旧调用：返回第一个 TLS 配对端口，无则 -1。 */
    public static int scanPairPort() {
        java.util.List<Integer> list = scanPairPorts();
        if (list.isEmpty()) return -1;
        int first = list.get(0);
        return isPairingTlsPort(first) ? first : -1;
    }

    /** 返回该端口可完成 TLS 探测的回环地址（"127.0.0.1"/"::1"），探测失败返回 null。 */
    public static String pairHostFor(int port) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 300);
                if (isPairingTlsPort(s)) return host;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 连接后 TLS 探测。 */
    private static boolean isPairingTlsPort(int port) {
        for (String host : new String[]{"127.0.0.1", "::1"}) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 200);
                if (isPairingTlsPort(s)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** TLS 探测（已连接）：发送完整最小 TLS1.2 ClientHello，配对端口回 ServerHello（首字节 0x16），连接端口回明文 adb 协议。 */
    private static boolean isPairingTlsPort(Socket s) {
        try {
            s.setSoTimeout(800);
            java.io.OutputStream out = s.getOutputStream();
            java.util.Random rnd = new java.util.Random();
            byte[] hs = new byte[45];
            hs[0] = 0x01;                      // ClientHello
            hs[1] = 0x00; hs[2] = 0x00; hs[3] = 41; // 握手体长度
            hs[4] = 0x03; hs[5] = 0x03;        // TLS 1.2
            for (int i = 6; i < 38; i++) hs[i] = (byte) rnd.nextInt(256); // 32 字节随机数
            hs[38] = 0x00;                     // session id len
            hs[39] = 0x00; hs[40] = 0x02;      // cipher suites len
            hs[41] = 0x00; hs[42] = 0x2f;      // TLS_RSA_WITH_AES_128_CBC_SHA
            hs[43] = 0x01; hs[44] = 0x00;      // compression
            out.write(0x16); out.write(0x03); out.write(0x01); // 记录头
            out.write(0x00); out.write(45);    // 记录长度
            out.write(hs);
            out.flush();
            int first = s.getInputStream().read();
            return first == 0x16;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int socketMdnsPair(Context ctx) {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("adb-pair-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable ignored) {}
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(2500);
            byte[] q = buildPairMdnsQuery();
            sock.send(new DatagramPacket(q, q.length, InetAddress.getByName("224.0.0.251"), 5353));
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[4096];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                for (String inst : parsePtr(buf, pkt.getLength())) {
                    int p = querySrvPair(sock, inst);
                    if (p > 0) return p;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "pair socket mdns failed: " + t.getMessage());
        } finally {
            try { if (lock != null) lock.release(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static byte[] buildPairMdnsQuery() {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeU16(b, 0); writeU16(b, 0);
        writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
        writeName(b, "_adb-tls-pairing._tcp.local");
        writeU16(b, 12); writeU16(b, 1);
        return b.toByteArray();
    }

    private static int querySrvPair(DatagramSocket sock, String instance) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeU16(b, 0); writeU16(b, 0);
        writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
        writeName(b, instance + "._adb-tls-pairing._tcp.local");
        writeU16(b, 33); writeU16(b, 1);
        sock.send(new DatagramPacket(b.toByteArray(), b.size(), InetAddress.getByName("224.0.0.251"), 5353));
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            byte[] buf = new byte[4096];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            sock.receive(pkt);
            int off = 12;
            int qe = skipName(buf, off, pkt.getLength());
            if (qe < 0) continue;
            off = qe + 4;
            while (off + 10 <= pkt.getLength()) {
                int ne = skipName(buf, off, pkt.getLength());
                if (ne < 0) break;
                int type = u16(buf, ne), clazz = u16(buf, ne + 2);
                int rdlen = u16(buf, ne + 6);
                int rd = ne + 8;
                if (type == 33 && clazz == 1 && rd + rdlen <= pkt.getLength()) {
                    int port = u16(buf, rd + 2);
                    if (port > 0) return port;
                }
                off = rd + rdlen;
            }
        }
        return -1;
    }
    /** 原始 socket mDNS 查询 _adb-tls-connect PTR + SRV。 */
    private static int socketMdns(Context ctx) {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("adb-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Throwable ignored) {}
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(2500);
            byte[] q = buildMdnsQuery();
            sock.send(new DatagramPacket(q, q.length, InetAddress.getByName("224.0.0.251"), 5353));
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[4096];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                for (String inst : parsePtr(buf, pkt.getLength())) {
                    int p = querySrv(sock, inst);
                    if (p > 0) return p;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "socket mdns failed: " + t.getMessage());
        } finally {
            try { if (lock != null) lock.release(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** 扫描本机监听端口：优先 /proc 监听列表（覆盖鸿蒙 IPv6 监听）；不可读时双栈范围扫描兜底。 */
    private static List<Integer> scanLocalPorts() {
        List<Integer> fromProc = new ArrayList<>(procListenPorts());
        if (!fromProc.isEmpty()) return fromProc;
        ConcurrentLinkedQueue<Integer> found = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(256);
        for (int port = 30000; port <= 60000; port++) {
            final int p = port;
            pool.execute(() -> {
                if (canConnectLoopback(p)) found.add(p);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return new ArrayList<>(found);
    }

    private static byte[] buildMdnsQuery() {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeU16(b, 0); writeU16(b, 0);
        writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
        writeName(b, "_adb-tls-connect._tcp.local");
        writeU16(b, 12); writeU16(b, 1);
        return b.toByteArray();
    }

    private static int querySrv(DatagramSocket sock, String instance) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        writeU16(b, 0); writeU16(b, 0);
        writeU16(b, 1); writeU16(b, 0); writeU16(b, 0); writeU16(b, 0);
        writeName(b, instance + "._adb-tls-connect._tcp.local");
        writeU16(b, 33); writeU16(b, 1);
        sock.send(new DatagramPacket(b.toByteArray(), b.size(), InetAddress.getByName("224.0.0.251"), 5353));
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            byte[] buf = new byte[4096];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            sock.receive(pkt);
            int off = 12;
            int qe = skipName(buf, off, pkt.getLength());
            if (qe < 0) continue;
            off = qe + 4;
            while (off + 10 <= pkt.getLength()) {
                int ne = skipName(buf, off, pkt.getLength());
                if (ne < 0) break;
                int type = u16(buf, ne), clazz = u16(buf, ne + 2);
                int rdlen = u16(buf, ne + 6);
                int rd = ne + 8;
                if (type == 33 && clazz == 1 && rd + rdlen <= pkt.getLength()) {
                    int port = u16(buf, rd + 2);
                    if (port > 0) return port;
                }
                off = rd + rdlen;
            }
        }
        return -1;
    }

    private static List<String> parsePtr(byte[] buf, int len) {
        List<String> out = new ArrayList<>();
        try {
            int off = 12;
            int qe = skipName(buf, off, len);
            if (qe < 0) return out;
            off = qe + 4;
            while (off + 10 <= len) {
                int ne = skipName(buf, off, len);
                if (ne < 0) return out;
                int type = u16(buf, ne), clazz = u16(buf, ne + 2);
                int rdlen = u16(buf, ne + 6);
                int rd = ne + 8;
                if (type == 12 && clazz == 1 && rd + rdlen <= len) {
                    int p = rd;
                    String inst = null;
                    if ((buf[p] & 0xc0) == 0xc0) {
                        int nameOff = ((buf[p] & 0x3f) << 8) | (buf[p + 1] & 0xff);
                        inst = readName(buf, nameOff, len);
                    } else {
                        inst = readName(buf, p, len);
                    }
                    if (inst != null) out.add(inst);
                }
                off = rd + rdlen;
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void writeName(java.io.ByteArrayOutputStream b, String name) {
        for (String label : name.split("\\.")) {
            byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
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
            sb.append(new String(buf, p + 1, l, StandardCharsets.US_ASCII));
            p += l + 1;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String readText(File f) throws Exception {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) throws Exception {
        InputStream in = new FileInputStream(f);
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

    private static void close() {
        try { if (adb != null) adb.close(); } catch (Throwable ignored) {}
        adb = null;
    }
    /** 配对成功后调用：断开旧连接、清空端口缓存与失败冷却，使下一次连接重新发现新数据端口。 */
    public static void markPaired() {
        synchronized (LOCK) {
            close();
            lastFailMs = 0;
            Context ctx = WdbContext.get();
            if (ctx != null) {
                try {
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(PREF_PORT).apply();
                } catch (Throwable ignored) {}
                PairState.markPaired(ctx);
            }
        }
    }
}











