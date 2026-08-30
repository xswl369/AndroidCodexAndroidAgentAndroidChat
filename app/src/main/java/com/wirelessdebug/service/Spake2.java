package com.wirelessdebug.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * BoringSSL 兼容的 SPAKE2（Ed25519 群），用于 Android 无线调试配对协议。
 * 算法逐行对照 AOSP external/boringssl src/crypto/curve25519/spake25519.c：
 *  - 角色：客户端 = Alice("adb pair client")，服务端 = Bob("adb pair server")
 *  - 消息：32 字节 Ed25519 点编码（P* = k*B + h(pwd)*M/N）
 *  - 密钥派生：SHA-512(length-prefixed names/messages/DH/password_hash)
 * 纯 Java（BigInteger）实现，无原生依赖，全 Android 版本通用。
 */
public final class Spake2 {
    // ---- Ed25519 常量 ----
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger TWO_D = D.shiftLeft(1).mod(P);
    // Ed25519 群阶 l
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(
            P.subtract(BigInteger.ONE).shiftRight(2), P);

    // ---- SPAKE2 角色 ----
    public static final int ROLE_ALICE = 0; // 客户端
    public static final int ROLE_BOB = 1;   // 服务端

    // M / N 掩码点（BoringSSL kSpakeMSmallPrecomp / kSpakeNSmallPrecomp 首项，小端）
    private static final BigInteger MX = leHex("c8a663c597f1ee40ab6242ee256f326c752ca7d3bd323b1e119cbd04a9786f45");
    private static final BigInteger MY = leHex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    private static final BigInteger NX = leHex("201bc5b343177110441e73b3ae3fbf9ff544c8138fd101c28a1a6dea4d005d6e");
    private static final BigInteger NY = leHex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    // ---- 上下文 ----
    private final int role;
    private final byte[] myName;
    private final byte[] theirName;
    private BigInteger privateKey;       // 随机标量（8 的倍数）
    private BigInteger passwordScalar;   // SHA-512(pwd) reduce 后调整（8 的倍数）
    private byte[] passwordHash;         // SHA-512(pwd) 原始 64 字节
    private byte[] myMsg;                // 32 字节点编码
    private int state;                   // 0=init 1=msg_generated

    private Spake2(int role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName;
        this.theirName = theirName;
        this.state = 0;
    }

    public static Spake2 newClient() {
        return new Spake2(ROLE_ALICE, b("adb pair client\0"), b("adb pair server\0"));
    }

    public static Spake2 newServer() {
        return new Spake2(ROLE_BOB, b("adb pair server\0"), b("adb pair client\0"));
    }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    /** 生成我方 SPAKE2 消息（32 字节），并保存密码标量/哈希/私钥。 */
    public byte[] generateMsg(byte[] password) {
        byte[] rnd = new byte[64];
        new SecureRandom().nextBytes(rnd);
        return generateMsgWithRandom(password, rnd);
    }

    /** 测试/调试用：注入固定随机字节。 */
    public byte[] generateMsgWithRandom(byte[] password, byte[] rnd64) {
        if (state != 0) throw new IllegalStateException("state");
        byte[] rnd = rnd64;
        BigInteger priv = scReduce(rnd).shiftLeft(3);
        privateKey = priv;
        // 2) 密码标量
        byte[] pwdHash = sha512(password);
        passwordHash = pwdHash;
        BigInteger scalar = scReduce(pwdHash);
        // password_scalar hack：加 l/2l/4l 使最低 3 位为 0
        if (scalar.testBit(0)) scalar = scalar.add(L);
        if (scalar.testBit(1)) scalar = scalar.add(L.shiftLeft(1));
        if (scalar.testBit(2)) scalar = scalar.add(L.shiftLeft(2));
        if (!scalar.and(BigInteger.valueOf(7)).equals(BigInteger.ZERO)) {
            throw new IllegalStateException("password scalar hack failed");
        }
        passwordScalar = scalar;
        // 3) P* = priv*B + h(pwd)*maskPoint
        BigInteger[] P = scalarmultBase(priv);
        BigInteger[] mask = scalarmult(scalar, role == ROLE_ALICE ? new BigInteger[]{MX, MY} : new BigInteger[]{NX, NY});
        BigInteger[] Pstar = add(P, mask);
        myMsg = encode(Pstar);
        state = 1;
        return myMsg;
    }

    /** 处理对端消息，返回 64 字节密钥材料。 */
    public byte[] processMsg(byte[] theirMsg, int maxOut) {
        if (state != 1) throw new IllegalStateException("state");
        if (theirMsg == null || theirMsg.length != 32) throw new IllegalArgumentException("bad msg len");
        BigInteger[] Qstar = decode(theirMsg);
        BigInteger[] peersMask = scalarmult(passwordScalar,
                role == ROLE_ALICE ? new BigInteger[]{NX, NY} : new BigInteger[]{MX, MY});
        BigInteger[] Q = sub(fromAffine(Qstar), peersMask);
        BigInteger[] dh = scalarmult(privateKey, Q);
        byte[] dhEnc = encode(dh);
        // SHA-512( LE64(my_name_len)||my_name || LE64(their_name_len)||their_name
        //        || LE64(32)||my_msg || LE64(32)||their_msg || LE64(32)||dh || LE64(64)||pwd_hash )
        MessageDigest md = sha512Digest();
        if (role == ROLE_ALICE) {
            updateLenPref(md, myName);
            updateLenPref(md, theirName);
            updateLenPref(md, myMsg);
            updateLenPref(md, theirMsg);
        } else {
            updateLenPref(md, theirName);
            updateLenPref(md, myName);
            updateLenPref(md, theirMsg);
            updateLenPref(md, myMsg);
        }
        updateLenPref(md, dhEnc);
        updateLenPref(md, passwordHash);
        byte[] key = md.digest();
        if (maxOut < key.length) {
            byte[] out = new byte[maxOut];
            System.arraycopy(key, 0, out, 0, maxOut);
            return out;
        }
        return key;
    }

    // ================= Ed25519 群运算（射影坐标 X:Y:Z:T） =================

    private static BigInteger[] identity() { return new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO}; }

    private static BigInteger[] fromAffine(BigInteger[] aff) {
        BigInteger x = aff[0].mod(P), y = aff[1].mod(P);
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** double (dbl-2008-hwcd) */
    private static BigInteger[] dbl(BigInteger[] q) {
        BigInteger X1 = q[0], Y1 = q[1], Z1 = q[2];
        BigInteger A = X1.multiply(X1).mod(P);
        BigInteger B = Y1.multiply(Y1).mod(P);
        BigInteger C = Z1.multiply(Z1).mod(P).shiftLeft(1).mod(P);
        BigInteger D = P.subtract(A); // -A
        BigInteger E = X1.add(Y1).mod(P).pow(2).subtract(A).subtract(B).mod(P);
        BigInteger G = D.add(B).mod(P);
        BigInteger F = G.subtract(C).mod(P);
        BigInteger H = D.subtract(B).mod(P);
        return new BigInteger[]{
                E.multiply(F).mod(P),
                G.multiply(H).mod(P),
                F.multiply(G).mod(P),
                E.multiply(H).mod(P)};
    }

    /** add (add-2008-hwcd-3) */
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger X1 = p[0], Y1 = p[1], Z1 = p[2], T1 = p[3];
        BigInteger X2 = q[0], Y2 = q[1], Z2 = q[2], T2 = q[3];
        BigInteger A = Y1.subtract(X1).multiply(Y2.subtract(X2)).mod(P);
        BigInteger B = Y1.add(X1).multiply(Y2.add(X2)).mod(P);
        BigInteger C = T1.multiply(TWO_D).multiply(T2).mod(P);
        BigInteger D = Z1.shiftLeft(1).multiply(Z2).mod(P);
        BigInteger E = B.subtract(A).mod(P);
        BigInteger F = D.subtract(C).mod(P);
        BigInteger G = D.add(C).mod(P);
        BigInteger H = B.add(A).mod(P);
        return new BigInteger[]{
                E.multiply(F).mod(P),
                G.multiply(H).mod(P),
                F.multiply(G).mod(P),
                E.multiply(H).mod(P)};
    }

    /** sub: p - q（q 取负：Y->-Y, T->-T） */
    private static BigInteger[] sub(BigInteger[] p, BigInteger[] q) {
        BigInteger X1 = p[0], Y1 = p[1], Z1 = p[2], T1 = p[3];
        // twisted Edwards (a=-1) 逆元 = (-x, y)；sub = p + (-q)
        BigInteger X2 = q[0].negate().mod(P), Y2 = q[1], Z2 = q[2], T2 = q[3].negate().mod(P);
        BigInteger A = Y1.subtract(X1).multiply(Y2.subtract(X2)).mod(P);
        BigInteger B = Y1.add(X1).multiply(Y2.add(X2)).mod(P);
        BigInteger C = T1.multiply(TWO_D).multiply(T2).mod(P);
        BigInteger D = Z1.shiftLeft(1).multiply(Z2).mod(P);
        BigInteger E = B.subtract(A).mod(P);
        BigInteger F = D.subtract(C).mod(P);
        BigInteger G = D.add(C).mod(P);
        BigInteger H = B.add(A).mod(P);
        return new BigInteger[]{
                E.multiply(F).mod(P),
                G.multiply(H).mod(P),
                F.multiply(G).mod(P),
                E.multiply(H).mod(P)};
    }

    /** 标量乘：s * point（s 为 0..2^256 内非负，double-and-add） */
    private static BigInteger[] scalarmult(BigInteger s, BigInteger[] point) {
        BigInteger[] Q = point.length == 4 ? point : fromAffine(point);
        BigInteger[] R = identity();
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            R = dbl(R);
            if (s.testBit(i)) R = add(R, Q);
        }
        return R;
    }

    /** basepoint 编码（Ed25519 B，y=4/5） */
    private static final byte[] BASE_POINT_ENC = new byte[32];
    static {
        BASE_POINT_ENC[0] = 0x58; // LE：最低字节 0x58，其余 0x66
        for (int i = 1; i < 32; i++) BASE_POINT_ENC[i] = (byte) 0x66;
    }

    private static BigInteger[] scalarmultBase(BigInteger s) {
        return scalarmult(s, decode(BASE_POINT_ENC));
    }

    /** Ed25519 点编码：y | (x&1)<<255，小端 32 字节 */
    private static byte[] encode(BigInteger[] pt) {
        BigInteger[] aff = toAffine(pt);
        byte[] out = new byte[32];
        byte[] yb = aff[1].toByteArray();
        for (int i = 0; i < yb.length && i < 32; i++) out[i] = yb[yb.length - 1 - i];
        if (aff[0].testBit(0)) out[31] |= 0x80;
        return out;
    }

    private static BigInteger[] toAffine(BigInteger[] pt) {
        BigInteger zInv = pt[2].modInverse(P);
        return new BigInteger[]{pt[0].multiply(zInv).mod(P), pt[1].multiply(zInv).mod(P)};
    }

    /** Ed25519 点解码（x 恢复 + 奇偶 + on-curve 校验），失败抛异常 */
    private static BigInteger[] decode(byte[] b) {
        BigInteger y = leBytes(b).and(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE));
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger vInv = v.modInverse(P);
        BigInteger x = u.multiply(vInv).mod(P).modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        // x^2 * v == u ?
        if (!x.multiply(x).mod(P).multiply(v).mod(P).equals(u)) {
            x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).mod(P).multiply(v).mod(P).equals(u)) {
                throw new IllegalArgumentException("point not on curve");
            }
        }
        if (!x.testBit(0) == ((b[31] & 0x80) != 0)) x = P.subtract(x);
        // on-curve：x^2 + y^2 == 1 + d*x^2*y^2
        BigInteger x2 = x.multiply(x).mod(P);
        if (!y2.subtract(x2).mod(P).equals(BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P))) {
            throw new IllegalArgumentException("point not on curve");
        }
        return new BigInteger[]{x, y};
    }

    // ================= 工具 =================

    /** x25519_sc_reduce：64 字节 LE -> mod l -> 32 字节 LE（BigInteger） */
    static BigInteger scReduce(byte[] b64) {
        BigInteger v = leBytes(b64);
        return v.mod(L);
    }

    private static BigInteger leBytes(byte[] b) {
        byte[] rev = new byte[b.length];
        for (int i = 0; i < b.length; i++) rev[i] = b[b.length - 1 - i];
        return new BigInteger(1, rev);
    }

    private static BigInteger leHex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return leBytes(b);
    }

    private static byte[] sha512(byte[] in) {
        return sha512Digest().digest(in);
    }

    private static MessageDigest sha512Digest() {
        try { return MessageDigest.getInstance("SHA-512"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void updateLenPref(MessageDigest md, byte[] data) {
        byte[] len = new byte[8];
        long l = data.length;
        for (int i = 0; i < 8; i++) { len[i] = (byte) (l & 0xff); l >>= 8; }
        md.update(len);
        md.update(data);
    }

    /** 简易自检（Alice/Bob 密钥一致 + 错误密码不一致 + base 点乘向量） */
    public static String selfTest() {
        StringBuilder sb = new StringBuilder();
        // 2*B 编码已知向量
        BigInteger[] twoB = scalarmult(BigInteger.valueOf(2), decode(BASE_POINT_ENC));
        String twoBHex = hex(encode(twoB));
        boolean dblOk = "c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022".equals(twoBHex);
        sb.append("2B=").append(twoBHex).append(" ").append(dblOk ? "OK" : "FAIL").append('\n');
        byte[] pwd = "123456".getBytes(StandardCharsets.US_ASCII);
        Spake2 alice = newClient(), bob = newServer();
        byte[] aMsg = alice.generateMsg(pwd);
        byte[] bMsg = bob.generateMsg(pwd);
        byte[] aKey = alice.processMsg(bMsg, 64);
        byte[] bKey = bob.processMsg(aMsg, 64);
        boolean match = MessageDigest.isEqual(aKey, bKey);
        sb.append("alice/bob key match: ").append(match).append('\n');
        Spake2 alice2 = newClient(), bob2 = newServer();
        byte[] bMsg2 = bob2.generateMsg("654321".getBytes(StandardCharsets.US_ASCII));
        byte[] aMsg2 = alice2.generateMsg(pwd);
        byte[] aKey2 = alice2.processMsg(bMsg2, 64);
        byte[] bKey2 = bob2.processMsg(aMsg2, 64);
        sb.append("wrong pwd mismatch: ").append(!MessageDigest.isEqual(aKey2, bKey2)).append('\n');
        sb.append("aliceMsg=").append(hex(aMsg)).append('\n');
        sb.append("bobMsg=").append(hex(bMsg)).append('\n');
        sb.append("aliceKey=").append(hex(aKey)).append('\n');
        sb.append("bobKey=").append(hex(bKey)).append('\n');
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.print(selfTest());
    }
}






