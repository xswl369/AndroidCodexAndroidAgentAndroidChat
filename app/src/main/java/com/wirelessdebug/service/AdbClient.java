package com.wirelessdebug.service;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import android.util.Base64;
import java.util.zip.CRC32;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 adb 协议客户端：支持 Android 11+ 无线调试 STLS 升级流程
 * （明文 CNXN -> STLS -> 回发 STLS -> TLS1.3 带客户端证书 -> CNXN 授权），
 * 以及旧版明文 AUTH 认证（root adbd / 模拟器）。
 */
public class AdbClient {
    private static final String TAG = "AdbClient";
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int AUTH_OK = 0;
    /** adb AUTH 签名负载：SHA1 DigestInfo 前缀（OpenSSL RSA_sign(NID_sha1, token) 语义，token 即 digest）。 */
    private static final byte[] SHA1_DIGESTINFO_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    /** 是否使用 TLS（Android 11+ 无线调试必须；走 STLS 升级流程）。 */
    public boolean tls = false;

    /** TLS 客户端证书：私钥（PKCS#8 PEM）+ 官方格式证书（PEM）。 */
    private PrivateKey clientKey;
    private String clientCertPem;

    /** 设置 TLS 客户端证书（PEM，Android 原生解析，避免 PKCS#12 PBES2 兼容问题）。 */
    public void setClientCert(PrivateKey key, String certPem) {
        clientKey = key;
        clientCertPem = certPem;
    }

    /** 加载 PKCS#8 RSA 私钥（adbkey PEM）。 */
    public static PrivateKey loadKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der2 = Base64.decode(body, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der2));
    }

    /** 连接 + 认证 + 返回是否通过。 */
    public boolean connect(String host, int port, PrivateKey key) throws Exception {
        try {
            // 官方 adb 客户端 CNXN 负载（明文 AUTH 与 STLS 触发均使用）
            byte[] cnxnPayload = ("host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,"
                    + "fixed_push_symlink_timestamp,abb_exec,split_bulk,ls_v2,apex").getBytes(StandardCharsets.UTF_8);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(30000);
            if (tls) {
                // 1) 明文 CNXN 触发 STLS
                in = new DataInputStream(socket.getInputStream());
                out = socket.getOutputStream();
                writePacket("CNXN", 0x01000001, 0x00100000, cnxnPayload);
                Packet p = readPacket();
                if (!"STLS".equals(p.command)) {
                    // 明文 AUTH 模式的设备（root adbd）可能直接回 AUTH TOKEN
                    if ("AUTH".equals(p.command)) return authLoop(key, p);
                    throw new IllegalStateException("expected STLS, got " + p.command);
                }
                // 2) 回发 STLS
                writePacket("STLS", p.arg0, 0, null);
                // 3) 同一 socket 上升级 TLS（带客户端证书）
                upgradeTls(host, port);
            } else {
                in = new DataInputStream(socket.getInputStream());
                out = socket.getOutputStream();
            }

            // 4) TLS 握手成功后设备主动发 CNXN（授权在证书验证阶段完成），读取即完成；
            //    不能再回发 CNXN，否则设备会再次回 STLS 导致死锁。
            if (tls) {
                while (true) {
                    Packet p = readPacket();
                    if ("CNXN".equals(p.command)) return true;
                    if ("AUTH".equals(p.command)) return authLoop(key, p);
                }
            }

            // 明文模式：发 CNXN 走 AUTH/CNXN 循环；
            // 若设备回 STLS（无线调试 TLS 连接端口）则就地升级 TLS（带客户端证书），授权在证书验证阶段完成
            writePacket("CNXN", 0x01000001, 0x00100000, cnxnPayload);
            while (true) {
                Packet p = readPacket();
                if ("CNXN".equals(p.command)) return true;
                if ("AUTH".equals(p.command)) return authLoop(key, p);
                if ("STLS".equals(p.command)) {
                    writePacket("STLS", p.arg0, 0, null);
                    upgradeTls(host, port);
                    while (true) {
                        Packet p2 = readPacket();
                        if ("CNXN".equals(p2.command)) return true;
                        if ("AUTH".equals(p2.command)) return authLoop(key, p2);
                    }
                }
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** 同一 socket 上升级 TLS（带内置客户端证书），用于无线调试 STLS 连接端口。 */
    private void upgradeTls(String host, int port) throws Exception {
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(buildKeyManagers(), new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        SSLSocket tlsSock = (SSLSocket) sc.getSocketFactory().createSocket(socket, host, port, true);
        tlsSock.setUseClientMode(true);
        tlsSock.setSoTimeout(30000);
        tlsSock.startHandshake();
        in = new DataInputStream(tlsSock.getInputStream());
        out = tlsSock.getOutputStream();
        socket = tlsSock;
    }

    /** 处理明文 AUTH 认证循环（旧式 adbd / root adbd）。 */
    private boolean authLoop(PrivateKey key, Packet first) throws Exception {
        Packet p = first;
        int authTries = 0;
        while (authTries++ < 4) {
            if (p.command.equals("CNXN")) return true;
            if (p.command.equals("AUTH")) {
                if (p.arg0 == AUTH_TOKEN && p.data != null && p.data.length > 0) {
                    // 官方 adb 客户端签名：RSA_sign(NID_sha1, token)，即对
                    // "DigestInfo(sha1) + token" 做 PKCS#1 v1.5 原始签名（token 本身就是 20 字节 digest）。
                    // 不能使用 SHA1withRSA（会再哈希一次导致 adbd 校验失败）。
                    Signature sig = Signature.getInstance("NONEwithRSA");
                    sig.initSign(key);
                    byte[] di = new byte[SHA1_DIGESTINFO_PREFIX.length + p.data.length];
                    System.arraycopy(SHA1_DIGESTINFO_PREFIX, 0, di, 0, SHA1_DIGESTINFO_PREFIX.length);
                    System.arraycopy(p.data, 0, di, SHA1_DIGESTINFO_PREFIX.length, p.data.length);
                    sig.update(di);
                    writePacket("AUTH", AUTH_SIGNATURE, 0, sig.sign());
                } else if (p.arg0 == AUTH_OK) {
                    return true;
                } else if (p.arg0 == AUTH_RSAPUBLICKEY) {
                    throw new IllegalStateException("adb key not authorized on device");
                }
            }
            p = readPacket();
        }
        throw new IllegalStateException("adb auth failed: key not accepted");
    }

    /** 构造 KeyManager：直接用 PEM key + 证书，忽略服务器 CA 列表过滤，强制发送客户端证书。 */
    private KeyManager[] buildKeyManagers() throws Exception {
        if (clientKey == null || clientCertPem == null) return null;
        final java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(clientCertPem.getBytes(StandardCharsets.UTF_8)));
        final X509Certificate[] chain = new X509Certificate[]{cert};
        final PrivateKey key = clientKey;
        return new KeyManager[]{new X509KeyManager() {
            public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, Socket socket) { return "adbkey"; }
            public String chooseServerAlias(String keyType, java.security.Principal[] issuers, Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) { return "adbkey".equals(alias) ? chain : null; }
            public PrivateKey getPrivateKey(String alias) { return "adbkey".equals(alias) ? key : null; }
            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) { return new String[]{"adbkey"}; }
            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return null; }
        }};
    }

    /** 通过 sync: 通道推送文件到设备（shell 身份可写 /data/local/tmp）。 */
    public void push(byte[] content, String remotePath, String mode) throws Exception {
        writePacket("OPEN", 1, 0, "sync:".getBytes(StandardCharsets.UTF_8));
        Packet p = readPacket();
        if (!"OKAY".equals(p.command)) throw new IllegalStateException("sync open failed: " + p.command);
        long localId = p.arg1, remoteId = p.arg0;
        writeSyncWrapped(localId, remoteId, "SEND", (remotePath + "," + mode).getBytes(StandardCharsets.UTF_8));
        int off = 0;
        while (off < content.length) {
            int n = Math.min(content.length - off, 64 * 1024);
            byte[] chunk = new byte[n];
            System.arraycopy(content, off, chunk, 0, n);
            writeSyncWrapped(localId, remoteId, "DATA", chunk);
            off += n;
        }
        byte[] mtime = new byte[4];
        putLeInt(mtime, 0, (int) (System.currentTimeMillis() / 1000));
        writeSyncWrapped(localId, remoteId, "DONE", mtime);
        String status = readSyncWrapped(localId, remoteId);
        if (!"OKAY".equals(status)) throw new IllegalStateException("push failed: " + status);
    }

    private void writeSyncWrapped(long localId, long remoteId, String id, byte[] payload) throws Exception {
        byte[] msg = new byte[8 + (payload != null ? payload.length : 0)];
        System.arraycopy(id.getBytes(StandardCharsets.US_ASCII), 0, msg, 0, 4);
        putLeInt(msg, 4, payload != null ? payload.length : 0);
        if (payload != null) System.arraycopy(payload, 0, msg, 8, payload.length);
        writePacket("WRTE", (int) localId, (int) remoteId, msg);
    }

    private String readSyncWrapped(long localId, long remoteId) throws Exception {
        while (true) {
            Packet p = readPacket();
            if ("CLSE".equals(p.command)) throw new IllegalStateException("sync channel closed");
            if (!"WRTE".equals(p.command)) continue;
            writePacket("OKAY", (int) p.arg1, (int) p.arg0, null);
            byte[] msg = p.data;
            if (msg == null || msg.length < 8) continue;
            String id = new String(msg, 0, 4, StandardCharsets.US_ASCII);
            if ("FAIL".equals(id)) {
                int ln = leInt(msg, 4);
                String err = (ln > 0 && msg.length >= 8 + ln) ? new String(msg, 8, ln, StandardCharsets.UTF_8) : "";
                throw new IllegalStateException("push failed: " + err);
            }
            return id;
        }
    }

    /** 执行 shell 命令，返回合并输出（连接保持，可复用）。 */
    public String shell(String cmd) throws Exception {
        writePacket("OPEN", 1, 0, ("shell:" + cmd).getBytes(StandardCharsets.UTF_8));
        long localId = 0, remoteId = 0;
        StringBuilder sb = new StringBuilder();
        boolean closed = false;
        int guard = 0;
        while (!closed && guard++ < 2000) {
            Packet p = readPacket();
            if (p.command.equals("OKAY")) {
                if (localId == 0) { localId = p.arg1; remoteId = p.arg0; }
            } else if (p.command.equals("WRTE")) {
                // 只接收属于当前 shell 流的数据，避免上一条命令残留输出混入
                if (localId != 0 && p.arg1 == localId && p.data != null) {
                    sb.append(new String(p.data, StandardCharsets.UTF_8));
                }
                writePacket("OKAY", p.arg1, p.arg0, null);
            } else if (p.command.equals("CLSE")) {
                closed = true;
                break;
            }
        }
        return sb.toString();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private static class Packet {
        String command;
        int arg0, arg1;
        byte[] data;
    }

    private Packet readPacket() throws Exception {
        byte[] hdr = readFully(24);
        if (hdr == null) throw new EOFException("connection closed");
        String cmd = new String(hdr, 0, 4, StandardCharsets.US_ASCII);
        int arg0 = leInt(hdr, 4), arg1 = leInt(hdr, 8), len = leInt(hdr, 12);
        byte[] data = len > 0 ? readFully(len) : null;
        Packet p = new Packet();
        p.command = cmd; p.arg0 = arg0; p.arg1 = arg1; p.data = data;
        return p;
    }

    private void writePacket(String cmd, int arg0, int arg1, byte[] data) throws Exception {
        int len = data != null ? data.length : 0;
        byte[] cbytes = cmd.getBytes(StandardCharsets.US_ASCII);
        int cmdInt = (cbytes[0] & 0xff) | ((cbytes[1] & 0xff) << 8) | ((cbytes[2] & 0xff) << 16) | ((cbytes[3] & 0xff) << 24);
        int crc = 0;
        if (data != null) { CRC32 c = new CRC32(); c.update(data); crc = (int) c.getValue(); }
        byte[] hdr = new byte[24];
        System.arraycopy(cbytes, 0, hdr, 0, 4);
        putLeInt(hdr, 4, arg0); putLeInt(hdr, 8, arg1); putLeInt(hdr, 12, len); putLeInt(hdr, 16, crc);
        putLeInt(hdr, 20, cmdInt ^ 0xffffffff);
        out.write(hdr);
        if (data != null) out.write(data);
        out.flush();
    }

    private byte[] readFully(int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return off == 0 ? null : buf;
            off += r;
        }
        return buf;
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }
}


