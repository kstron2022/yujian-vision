package com.yujian.vision.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Provider;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SM4-GCM 加密单元测试（docs/15 步骤1）：GB/T 32907-2016 标准向量（钉 BC 的 SM4 引擎）
 * + RFC 8998 SM4-GCM 标准向量（钉 GCM 形制）+ 加解密回环/前缀/篡改（对齐 AesCipherTest 全套）。
 * 纯 JUnit, 无 Spring。
 */
class SmCipherTest {

    private static final String KEY = "unit-test-sm4-key-0123456789abcdef";
    private static final Provider BC = new BouncyCastleProvider();

    @Test
    @DisplayName("GB/T 32907-2016 SM4 标准向量: 单块 ECB 加密")
    void sm4StandardVector() throws Exception {
        byte[] kv = hex("0123456789ABCDEFFEDCBA9876543210");
        Cipher c = Cipher.getInstance("SM4/ECB/NoPadding", BC);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kv, "SM4"));
        byte[] ct = c.doFinal(kv);
        assertEquals("681edf34d206965e86b3e94f536e4246", bytesToHex(ct));
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kv, "SM4"));
        assertArrayEquals(kv, c.doFinal(ct));
    }

    @Test
    @DisplayName("RFC 8998 A.1 SM4-GCM 标准向量: GCM 形制(AAD+128bit tag)")
    void sm4GcmRfc8998Vector() throws Exception {
        Cipher c = Cipher.getInstance("SM4/GCM/NoPadding", BC);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hex("0123456789ABCDEFFEDCBA9876543210"), "SM4"),
                new javax.crypto.spec.GCMParameterSpec(128, hex("00001234567800000000ABCD")));
        c.updateAAD(hex("FEEDFACEDEADBEEFFEEDFACEDEADBEEFABADDAD2"));
        byte[] out = c.doFinal(hex(
                "AAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBCCCCCCCCCCCCCCCCDDDDDDDDDDDDDDDD"
                        + "EEEEEEEEEEEEEEEEFFFFFFFFFFFFFFFFEEEEEEEEEEEEEEEEAAAAAAAAAAAAAAAA"));
        assertEquals("17f399f08c67d5ee19d0dc9969c4bb7d"
                        + "5fd46fd3756489069157b282bb200735"
                        + "d82710ca5c22f0ccfa7cbf93d496ac15"
                        + "a56834cbcf98c397b4024a2691233b8d"
                        + "83de3541e4c2b58177e065a9bf7b62ec",
                bytesToHex(out));
    }

    @Test
    @DisplayName("加解密回环 + sm4$ 前缀")
    void roundTripAndPrefix() {
        SmCipher c = new SmCipher(KEY);
        String enc = c.encrypt("open-app-secret-48chars");
        assertTrue(SmCipher.isEncrypted(enc));
        assertTrue(enc.startsWith("sm4$"));
        assertNotEquals("open-app-secret-48chars", enc);
        assertEquals("open-app-secret-48chars", c.decrypt(enc));
    }

    @Test
    @DisplayName("前缀与 AesCipher 密文互不误判")
    void prefixDistinctFromAes() {
        assertFalse(SmCipher.isEncrypted("enc:v1:AAAA"));
        assertFalse(AesCipher.isEncrypted("sm4$AAAA"));
    }

    @Test
    @DisplayName("null/空串/空白原样返回")
    void blankPassthrough() {
        SmCipher c = new SmCipher(KEY);
        assertEquals(null, c.encrypt(null));
        assertEquals("", c.encrypt(""));
        assertEquals("  ", c.encrypt("  "));
        assertEquals(null, c.decrypt(null));
        assertEquals("", c.decrypt(""));
        assertEquals("  ", c.decrypt("  "));
    }

    @Test
    @DisplayName("幂等: 重复 encrypt 不双重加密")
    void idempotentEncrypt() {
        SmCipher c = new SmCipher(KEY);
        String once = c.encrypt("secret-123");
        assertEquals(once, c.encrypt(once));
        assertEquals("secret-123", c.decrypt(c.encrypt(once)));
    }

    @Test
    @DisplayName("IV 随机: 同一明文两次加密产生不同密文, 但均可解回")
    void randomIv() {
        SmCipher c = new SmCipher(KEY);
        String e1 = c.encrypt("secret-123");
        String e2 = c.encrypt("secret-123");
        assertNotEquals(e1, e2);
        assertEquals("secret-123", c.decrypt(e1));
        assertEquals("secret-123", c.decrypt(e2));
    }

    @Test
    @DisplayName("密文篡改 → 解密抛 IllegalStateException 且保留 cause")
    void tamperedCiphertextThrows() {
        SmCipher c = new SmCipher(KEY);
        String enc = c.encrypt("secret-123");
        String tampered = SmCipher.PREFIX + "AAAA" + enc.substring(SmCipher.PREFIX.length() + 4);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("不同密钥解不开 → 抛 IllegalStateException")
    void wrongKeyFails() {
        SmCipher good = new SmCipher(KEY);
        SmCipher other = new SmCipher(KEY + "-x");
        String enc = good.encrypt("secret-123");
        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }

    @Test
    @DisplayName("中文内容回环")
    void chineseContent() {
        SmCipher c = new SmCipher(KEY);
        String enc = c.encrypt("开放接口密钥-测试123");
        assertNotEquals("开放接口密钥-测试123", enc);
        assertEquals("开放接口密钥-测试123", c.decrypt(enc));
    }

    @Test
    @DisplayName("非法密钥材料构造抛 IllegalArgumentException")
    void blankKeyMaterialRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SmCipher(null));
        assertThrows(IllegalArgumentException.class, () -> new SmCipher("  "));
        assertDoesNotThrow(() -> new SmCipher(KEY));
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
