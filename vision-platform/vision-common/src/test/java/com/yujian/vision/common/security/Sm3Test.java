package com.yujian.vision.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SM3/HMAC-SM3 单元测试（docs/15 步骤1）：GB/T 32905-2016 标准向量 + 双口径交叉验证。
 * 纯 JUnit, 无 Spring。
 */
class Sm3Test {

    @Test
    @DisplayName("GB/T 32905-2016 标准向量: \"abc\"")
    void standardVectorAbc() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                Sm3.digestHex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("GB/T 32905-2016 标准向量: 空消息")
    void standardVectorEmpty() {
        assertEquals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
                Sm3.digestHex(new byte[0]));
    }

    @Test
    @DisplayName("跨块消息: 轻量 API 与 JCE provider 双口径一致(0~65B 边界)")
    void multiBlockCrossCheckWithJceProvider() throws Exception {
        MessageDigest jce = MessageDigest.getInstance("SM3", new BouncyCastleProvider());
        Random r = new Random(20260926L);
        for (int len : new int[]{0, 1, 3, 55, 56, 63, 64, 65, 128, 1000}) {
            byte[] data = new byte[len];
            r.nextBytes(data);
            assertArrayEquals(jce.digest(data), Sm3.digest(data), "len=" + len);
        }
    }

    @Test
    @DisplayName("digestHex 输出为 64 位小写 hex")
    void digestHexFormat() {
        String hex = Sm3.digestHex("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, hex.length());
        assertEquals(hex, hex.toLowerCase());
    }

    /** 手写 HMAC 结构（RFC 2104: 超长密钥先 hash, ipad/opad + SM3）, 用于与 BC HMac 交叉验证 */
    private static byte[] handRolledHmac(byte[] secret, byte[] data) {
        byte[] k = secret.length > 64 ? Sm3.digest(secret) : secret;
        byte[] block = new byte[64];
        System.arraycopy(k, 0, block, 0, k.length);
        byte[] inner = new byte[64 + data.length];
        byte[] outer = new byte[64 + Sm3.DIGEST_LEN];
        for (int i = 0; i < 64; i++) {
            inner[i] = (byte) (block[i] ^ 0x36);
            outer[i] = (byte) (block[i] ^ 0x5c);
        }
        System.arraycopy(data, 0, inner, 64, data.length);
        byte[] innerHash = Sm3.digest(inner);
        System.arraycopy(innerHash, 0, outer, 64, innerHash.length);
        return Sm3.digest(outer);
    }

    @Test
    @DisplayName("HMAC-SM3: 手写 HMAC 结构与 BC HMac 跨验证(多密钥/多消息长度)")
    void hmacCrossCheckWithHandRolled() {
        Random r = new Random(20260926L);
        for (int keyLen : new int[]{16, 20, 32, 64, 77}) {
            byte[] secret = new byte[keyLen];
            r.nextBytes(secret);
            for (int dataLen : new int[]{0, 3, 64, 65, 200}) {
                byte[] data = new byte[dataLen];
                r.nextBytes(data);
                assertArrayEquals(handRolledHmac(secret, data), Sm3.hmac(secret, data),
                        "keyLen=" + keyLen + " dataLen=" + dataLen);
            }
        }
    }

    @Test
    @DisplayName("hmacBase64: Base64 解回 == hmac 字节; 与 HMAC-SHA256 同构不同值")
    void hmacBase64DecodesToHmac() {
        byte[] mac = Sm3.hmac("secret-key".getBytes(StandardCharsets.UTF_8),
                "METHOD\nPATH".getBytes(StandardCharsets.UTF_8));
        assertEquals(Base64.getEncoder().encodeToString(mac),
                Sm3.hmacBase64("secret-key", "METHOD\nPATH".getBytes(StandardCharsets.UTF_8)));
        assertEquals(32, mac.length);
    }
}
