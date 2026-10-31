package com.yujian.vision.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * SM3 摘要与 HMAC-SM3（docs/15 步骤1, GB/T 32905-2016）：OpenAPI 签名原语。
 * 签名口径（docs/10 §3.2, 签名串结构不变）：X-Signature = Base64(HMAC-SM3(app_secret, 签名串))，
 * 签名串内的报文摘要用 SM3Hex 替代原 MD5（docs/15 决策 D2）。
 * 全部走 BouncyCastle 轻量 API（不注册 JCE 全局 provider, 规避与 JDK 内置 provider 冲突）。
 * 纯工具类, 无 Spring 依赖。
 */
public final class Sm3 {

    /** SM3 摘要输出长度（字节） */
    public static final int DIGEST_LEN = 32;

    private Sm3() {
    }

    /** SM3 摘要（32 字节） */
    public static byte[] digest(byte[] data) {
        SM3Digest d = new SM3Digest();
        d.update(data, 0, data.length);
        byte[] out = new byte[DIGEST_LEN];
        d.doFinal(out, 0);
        return out;
    }

    /** SM3 摘要小写 hex（签名串第 5 行报文摘要口径, docs/15 步骤3） */
    public static String digestHex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }

    /** HMAC-SM3（BC HMac; 块长 64B 与 HMAC-SHA256 结构同形） */
    public static byte[] hmac(byte[] secret, byte[] data) {
        HMac mac = new HMac(new SM3Digest());
        mac.init(new KeyParameter(secret));
        mac.update(data, 0, data.length);
        byte[] out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);
        return out;
    }

    /** HMAC-SM3 → Base64（开放接口 X-Signature 口径; secret 为 app_secret 明文） */
    public static String hmacBase64(String secret, byte[] data) {
        return Base64.getEncoder().encodeToString(
                hmac(secret.getBytes(StandardCharsets.UTF_8), data));
    }
}
