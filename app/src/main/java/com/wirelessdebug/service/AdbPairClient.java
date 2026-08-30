package com.wirelessdebug.service;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import com.wirelessdebug.WdbContext;
import org.conscrypt.Conscrypt;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * 无线调试配对协议客户端（Android 11+ 官方 adb pair）。
 * 流程：TLS1.3(客户端证书=内置adbkey) -> 导出 key material("adb-label",64)
 *     -> SPAKE2(密码=6位码+导出密钥) -> AES-128-GCM 加密交换 PeerInfo(内置公钥)
 * 配对成功后设备把内置公钥写入 adb_keys 白名单，之后 AdbShellController 可直连。
 * 无 root、无 Shizuku、无需安装任何程序。
 */
public class AdbPairClient {
    private static final String TAG = "AdbPairClient";
    private static final byte TYPE_SPAKE2_MSG = 0;
    private static final byte TYPE_PEER_INFO = 1;
    private static final int PEER_INFO_SIZE = 8192;
    private static final byte TYPE_ADB_RSA_PUB_KEY = 0;
    private static final byte TYPE_ADB_DEVICE_GUID = 1;
    private static final int EXPORTED_KEY_SIZE = 64;
    // AOSP tls_connection.cpp: kExportedKeyLabel[] = "adb-label"，sizeof 含结尾 NUL = 10 字节，必须原样带上
    private static final String EXPORTED_KEY_LABEL = "adb-label\u0000";

    public static class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    /** 执行配对（阻塞，需后台线程调用）。自动尝试 IPv4/IPv6 回环地址，适配不同 ROM 的监听方式。 */
    public static Result pair(String host, int pairPort, String code) {
        java.util.List<String> hosts = new java.util.ArrayList<>();
        if (host != null && !host.trim().isEmpty()) hosts.add(host);
        if (!hosts.contains("::1")) hosts.add("::1");
        Result last = null;
        for (String h : hosts) {
            last = pairOnce(h, pairPort, code);
            if (last.ok) return last;
        }
        return last != null ? last : new Result(false, "配对失败：无可用地址");
    }

    private static Result pairOnce(String host, int pairPort, String code) {
        try {
            String codeTrim = code == null ? "" : code.trim();
            if (codeTrim.length() != 6) return new Result(false, "请输入 6 位配对码");
            byte[] codeBytes = codeTrim.getBytes(StandardCharsets.US_ASCII);

            java.io.File dir = new java.io.File(WdbContext.get().getFilesDir(), ShizukuBootstrap.ASSET_DIR);
            ShizukuBootstrap.ensureAssets(WdbContext.get());
            java.io.File keyFile = new java.io.File(dir, ShizukuBootstrap.ADB_KEY);
            java.io.File certFile = new java.io.File(dir, ShizukuBootstrap.ADB_CERT);
            if (!keyFile.exists() || !certFile.exists()) return new Result(false, "内置 adb key 缺失");

            PrivateKey key = AdbClient.loadKey(new String(readBytes(keyFile), StandardCharsets.UTF_8));
            String certPem = new String(readBytes(certFile), StandardCharsets.UTF_8);

            // 1) TLS 连接配对端口（带客户端证书）
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, pairPort), 3000);
            raw.setSoTimeout(8000);
            SSLContext sc = SSLContext.getInstance("TLS", Conscrypt.newProvider()); // 捆绑 Conscrypt，ROM 无系统 Conscrypt 也可导出密钥
            sc.init(buildKeyManagers(key, certPem), new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            SSLSocket sock = (SSLSocket) sc.getSocketFactory().createSocket(raw, host, pairPort, true);
            sock.setUseClientMode(true);
            sock.setEnabledProtocols(new String[]{"TLSv1.3"}); // adbd 配对服务器仅允许 TLS1.3
            sock.setSoTimeout(8000);
            try {
                sock.startHandshake();
            } catch (Throwable t) {
                sock.close();
                return new Result(false, "TLS 握手失败（请确认已开启「使用配对码配对设备」页面）：" + shortMsg(t));
            }
            diag("tls proto=" + sock.getSession().getProtocol() + " cipher=" + sock.getSession().getCipherSuite());
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();

            // 2) 导出 key material（Conscrypt）
            byte[] exported;
            try {
                exported = exportKeyingMaterial(sock, EXPORTED_KEY_LABEL, EXPORTED_KEY_SIZE);
            } catch (Throwable t) {
                sock.close();
                try {
                    java.io.File f = new java.io.File(WdbContext.get().getFilesDir(), "pip_diag.txt");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
                    java.io.PrintWriter pw = new java.io.PrintWriter(fo);
                    pw.println(System.currentTimeMillis() + " export exc: " + t.getClass().getName() + " msg=" + t.getMessage());
                    t.printStackTrace(pw);
                    pw.flush(); pw.close();
                } catch (Throwable ignored) {}
                return new Result(false, "TLS 导出密钥失败（设备 TLS 实现不支持）：" + shortMsg(t));
            }
            diag("exported len=" + (exported == null ? -1 : exported.length) + " sha256=" + (exported == null ? "null" : hexShort(sha256(exported))));
            if (exported == null || exported.length != EXPORTED_KEY_SIZE) {
                sock.close();
                return new Result(false, "TLS 导出密钥长度异常");
            }

            // 3) SPAKE2（密码 = 6位码 + 导出密钥）
            byte[] pswd = new byte[codeBytes.length + exported.length];
            System.arraycopy(codeBytes, 0, pswd, 0, codeBytes.length);
            System.arraycopy(exported, 0, pswd, codeBytes.length, exported.length);

            Spake2 spake = Spake2.newClient();
            byte[] myMsg = spake.generateMsg(pswd);

            // 4) 发送 SPAKE2_MSG
            writeFrame(out, TYPE_SPAKE2_MSG, myMsg);
            // 5) 读对端 SPAKE2_MSG
            byte[] theirMsg = readFrame(in);
            if (theirMsg == null) { sock.close(); return new Result(false, "配对超时：未收到对端消息"); }
            byte[] keyMaterial;
            try {
                keyMaterial = spake.processMsg(theirMsg, 64);
            } catch (Throwable t) {
                sock.close();
                return new Result(false, "SPAKE2 失败（配对码可能已过期或错误）：" + shortMsg(t));
            }

            // 6) AES-128-GCM：HKDF-SHA256 派生密钥
            diag("spake2 key sha256=" + hexShort(sha256(keyMaterial)));
            byte[] aesKey = hkdfSha256(keyMaterial, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16);
            AesGcmBox enc = new AesGcmBox(aesKey);
            AesGcmBox dec = new AesGcmBox(aesKey);

            // 7) 加密 PeerInfo（type=0 + ssh-rsa 公钥）
            byte[] peerInfo = new byte[PEER_INFO_SIZE];
            peerInfo[0] = TYPE_ADB_RSA_PUB_KEY;
            // PeerInfo 公钥 = 客户端证书里的公钥（私钥可能为非 CRT 格式，无法直接取 e）
            java.security.cert.X509Certificate certObj = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
            byte[] pubLine = sshRsaPublicKeyLine((java.security.interfaces.RSAPublicKey) certObj.getPublicKey());
            System.arraycopy(pubLine, 0, peerInfo, 1, Math.min(pubLine.length, PEER_INFO_SIZE - 2));
            byte[] encrypted = enc.encrypt(peerInfo);
            writeFrame(out, TYPE_PEER_INFO, encrypted);

            // 8) 读对端 PEER_INFO 并解密
            byte[] theirEnc = readFrame(in);
            if (theirEnc == null) { sock.close(); return new Result(false, "配对超时：未收到对端确认"); }
            byte[] theirInfo;
            try {
                theirInfo = dec.decrypt(theirEnc);
                diag("decrypt ok len=" + (theirInfo == null ? -1 : theirInfo.length));
            } catch (Throwable t) {
                diag("decrypt FAIL: " + t.getClass().getName() + " " + t.getMessage());
                sock.close();
                return new Result(false, "配对失败：配对码错误或已过期");
            }
            sock.close();
            if (theirInfo == null || theirInfo.length == 0 || theirInfo[0] != TYPE_ADB_DEVICE_GUID) {
                return new Result(false, "配对失败：设备返回异常响应");
            }
            // 设备已把内置公钥加入白名单；清除旧的端口缓存并记录已配对
            AdbShellController.markPaired();
            return new Result(true, "配对成功！现在可以直接控制手机");
        } catch (Throwable t) {
            Log.e(TAG, "pair failed", t);
            return new Result(false, "配对失败：" + shortMsg(t));
        }
    }

    private static void diag(String line) {
        try {
            java.io.File f = new java.io.File(WdbContext.get().getFilesDir(), "pip_diag.txt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fo);
            pw.println(System.currentTimeMillis() + " " + line);
            pw.flush(); pw.close();
        } catch (Throwable ignored) {}
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    private static String hexShort(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b == null ? 0 : b.length, 16);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
    }

    // ================= TLS =================

    /** 捆绑 Conscrypt 的 exportKeyingMaterial(socket, label, context, length)。 */
    static byte[] exportKeyingMaterial(SSLSocket sock, String label, int length) throws Exception {
        return Conscrypt.exportKeyingMaterial(sock, label, null, length);
    }

    private static KeyManager[] buildKeyManagers(final PrivateKey key, final String certPem) throws Exception {
        final java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
        final X509Certificate[] chain = new X509Certificate[]{cert};
        return new KeyManager[]{new X509KeyManager() {
            public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, Socket socket) { return "adbkey"; }
            public String chooseServerAlias(String keyType, java.security.Principal[] issuers, Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) { return "adbkey".equals(alias) ? chain : null; }
            public PrivateKey getPrivateKey(String alias) { return "adbkey".equals(alias) ? key : null; }
            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) { return new String[]{"adbkey"}; }
            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return null; }
        }};
    }

    // ================= 帧协议 =================

    // AOSP PairingPacketHeader = version(1) + type(1) + payload(4 BE)，共 6 字节（packed）
    private static void writeFrame(OutputStream out, byte type, byte[] payload) throws Exception {
        byte[] hdr = new byte[6];
        hdr[0] = 1;           // version
        hdr[1] = type;        // type
        int len = payload.length;
        hdr[2] = (byte) (len >>> 24);
        hdr[3] = (byte) (len >>> 16);
        hdr[4] = (byte) (len >>> 8);
        hdr[5] = (byte) len;
        out.write(hdr);
        out.write(payload);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in) throws Exception {
        byte[] hdr = new byte[6];
        int off = 0;
        while (off < 6) {
            int r = in.read(hdr, off, 6 - off);
            if (r < 0) return null;
            off += r;
        }
        if (hdr[0] != 1) return null;
        long len = ((hdr[2] & 0xffL) << 24) | ((hdr[3] & 0xffL) << 16) | ((hdr[4] & 0xffL) << 8) | (hdr[5] & 0xffL);
        if (len <= 0 || len > 2 * PEER_INFO_SIZE) return null;
        byte[] payload = new byte[(int) len];
        off = 0;
        while (off < len) {
            int r = in.read(payload, off, (int) len - off);
            if (r < 0) return null;
            off += r;
        }
        return payload;
    }

    // ================= AES-128-GCM（BoringSSL 兼容） =================

    private static class AesGcmBox {
        private final byte[] key;
        private long encSeq = 0;
        private long decSeq = 0;
        AesGcmBox(byte[] key) { this.key = key; }

        byte[] encrypt(byte[] data) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(encSeq++)));
            return c.doFinal(data);
        }

        byte[] decrypt(byte[] data) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(decSeq++)));
            return c.doFinal(data);
        }

        private byte[] nonce(long seq) {
            byte[] n = new byte[12];
            for (int i = 0; i < 8; i++) n[i] = (byte) (seq >>> (8 * i));
            return n;
        }
    }

    /** HKDF-SHA256：extract(salt=zeros(32), ikm) + expand(info, 0x01)。 */
    static byte[] hkdfSha256(byte[] ikm, byte[] info, int outLen) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        byte[] okm = mac.doFinal();
        return Arrays.copyOf(okm, outLen);
    }

    // ================= PeerInfo 公钥 =================

    /**
     * AOSP 官方 adb pair 的 PeerInfo 公钥格式（adb/crypto/rsa_2048_key.cpp CalculatePublicKey）：
     * "<base64(ANDROID_PUBKEY 二进制)> <user@host>"，无 "ssh-rsa " 前缀。
     * 实测（RMX3031/realme Android11）：设备「已配对的设备」列表对无前缀行显示注释昵称，
     * 对带 "ssh-rsa " 前缀的行显示 base64 公钥本身——因此必须与官方格式完全一致。
     */
    static byte[] sshRsaPublicKeyLine(java.security.interfaces.RSAPublicKey rsa) throws Exception {
        byte[] androidKey = androidPubkeyBytes(rsa.getModulus(), rsa.getPublicExponent());
        String line = Base64.encodeToString(androidKey, Base64.NO_WRAP) + " Administrator@XS-Agent";
        return line.getBytes(StandardCharsets.US_ASCII);
    }

    /** AOSP libcrypto_utils android_pubkey_encode：modulus_size_words(LE) + n0inv(LE) + modulus(256B LE) + rr(256B LE) + exponent(LE)。 */
    static byte[] androidPubkeyBytes(java.math.BigInteger n, java.math.BigInteger e) throws Exception {
        final int MOD = 256; // 2048/8
        ByteArrayOutputStream b = new ByteArrayOutputStream(4 + 4 + MOD + MOD + 4);
        // 1) modulus_size_words = 64（2048 bit / 32）
        writeLeU32(b, 64);
        // 2) n0inv = (2^32 - (N mod 2^32)^-1 mod 2^32) mod 2^32（N 为奇数，与 2^32 互质）
        long n0 = n.mod(java.math.BigInteger.ONE.shiftLeft(32)).longValue() & 0xFFFFFFFFL;
        long inv = java.math.BigInteger.valueOf(n0).modInverse(java.math.BigInteger.ONE.shiftLeft(32)).longValue() & 0xFFFFFFFFL;
        writeLeU32(b, (0x100000000L - inv) & 0xFFFFFFFFL);
        // 3) modulus：256 字节小端
        writeLePadded(b, stripLeadingZero(n.toByteArray()), MOD);
        // 4) rr = 2^4096 mod N：256 字节小端（AOSP decode 忽略该字段，仍按真实值填充）
        writeLePadded(b, stripLeadingZero(java.math.BigInteger.ONE.shiftLeft(4096).mod(n).toByteArray()), MOD);
        // 5) exponent（RSA_F4 = 65537）
        writeLeU32(b, e.longValue() & 0xFFFFFFFFL);
        return b.toByteArray();
    }

    /** 以 size 字节小端写入（大端数组反转 + 高位补零）。 */
    private static void writeLePadded(ByteArrayOutputStream b, byte[] bigEndian, int size) {
        for (int i = 0; i < size; i++) {
            int src = bigEndian.length - 1 - i;
            b.write(src >= 0 ? (bigEndian[src] & 0xff) : 0);
        }
    }

    private static void writeLeU32(ByteArrayOutputStream b, long v) {
        b.write((int) (v & 0xff));
        b.write((int) ((v >> 8) & 0xff));
        b.write((int) ((v >> 16) & 0xff));
        b.write((int) ((v >> 24) & 0xff));
    }

    private static void writeSshString(ByteArrayOutputStream b, byte[] data) {
        b.write((data.length >>> 24) & 0xff);
        b.write((data.length >>> 16) & 0xff);
        b.write((data.length >>> 8) & 0xff);
        b.write(data.length & 0xff);
        b.write(data, 0, data.length);
    }

    private static byte[] stripLeadingZero(byte[] in) {
        int off = 0;
        while (off < in.length - 1 && in[off] == 0) off++;
        if (off == 0) return in;
        byte[] out = new byte[in.length - off];
        System.arraycopy(in, off, out, 0, out.length);
        return out;
    }

    private static byte[] readBytes(java.io.File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
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
}



