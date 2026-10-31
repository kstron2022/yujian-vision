package com.yujian.vision.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 敏感字段加密单元测试（docs/02 §0）：纯 JUnit, 无 Spring/无外部依赖。
 */
class AesCipherTest {

    private static final String KEY = "unit-test-aes-key-0123456789abcdef";

    @Test
    @DisplayName("加解密回环")
    void roundTrip() {
        AesCipher c = new AesCipher(KEY);
        String enc = c.encrypt("12345678");
        assertTrue(AesCipher.isEncrypted(enc));
        assertNotEquals("12345678", enc);
        assertEquals("12345678", c.decrypt(enc));
    }

    @Test
    @DisplayName("null/空串/空白原样返回")
    void blankPassthrough() {
        AesCipher c = new AesCipher(KEY);
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
        AesCipher c = new AesCipher(KEY);
        String once = c.encrypt("12345678");
        String twice = c.encrypt(once);
        assertEquals(once, twice);
        assertEquals("12345678", c.decrypt(twice));
    }

    @Test
    @DisplayName("明文兼容: decrypt 对存量明文原样返回")
    void legacyPlaintextPassthrough() {
        AesCipher c = new AesCipher(KEY);
        assertEquals("12345678", c.decrypt("12345678"));
        assertFalse(AesCipher.isEncrypted("12345678"));
    }

    @Test
    @DisplayName("IV 随机: 同一明文两次加密产生不同密文, 但均可解回")
    void randomIv() {
        AesCipher c = new AesCipher(KEY);
        String e1 = c.encrypt("12345678");
        String e2 = c.encrypt("12345678");
        assertNotEquals(e1, e2);
        assertEquals("12345678", c.decrypt(e1));
        assertEquals("12345678", c.decrypt(e2));
    }

    @Test
    @DisplayName("密文篡改 → 解密抛 IllegalStateException 且保留 cause")
    void tamperedCiphertextThrows() {
        AesCipher c = new AesCipher(KEY);
        String enc = c.encrypt("12345678");
        String tampered = AesCipher.PREFIX + "AAAA" + enc.substring(AesCipher.PREFIX.length() + 4);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("不同密钥解不开 → 抛 IllegalStateException")
    void wrongKeyFails() {
        AesCipher good = new AesCipher(KEY);
        AesCipher other = new AesCipher(KEY + "-x");
        String enc = good.encrypt("12345678");
        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }

    @Test
    @DisplayName("中文内容回环")
    void chineseContent() {
        AesCipher c = new AesCipher(KEY);
        String enc = c.encrypt("设备接入密码-测试123");
        assertNotEquals("设备接入密码-测试123", enc);
        assertEquals("设备接入密码-测试123", c.decrypt(enc));
    }

    @Test
    @DisplayName("非法密钥材料构造抛 IllegalArgumentException")
    void blankKeyMaterialRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AesCipher(null));
        assertThrows(IllegalArgumentException.class, () -> new AesCipher("  "));
    }

    @Test
    @DisplayName("encrypt/decrypt 对已加密串不抛异常")
    void noThrowOnEncryptedInput() {
        AesCipher c = new AesCipher(KEY);
        String enc = c.encrypt("12345678");
        assertDoesNotThrow(() -> c.encrypt(enc));
        assertDoesNotThrow(() -> c.decrypt(c.encrypt("x")));
    }
}
