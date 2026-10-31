package com.yujian.vision.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 敏感字段 AES-256-GCM 可逆加密（docs/02 §0）：设备接入密码 / 节点 sign_secret。
 * 密文格式 "enc:v1:" + Base64(12B 随机 IV || 密文||tag(128bit))。
 * 兼容性：decrypt 对不带前缀 legacy 明文原样返回（滚动加密可用, sim 脚本链路等价）。
 * 纯 JDK 实现, 无 Spring 依赖。
 */
public class AesCipher {

    /** 密文前缀（带版本, 便于后续轮换/格式升级识别） */
    public static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesCipher(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalArgumentException("AES 密钥材料不能为空");
        }
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AES 密钥初始化失败", e);
        }
    }

    /** null/blank 原样返回; 已加密原样返回(幂等); 其余加密 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    /** null/blank 原样返回; 不带前缀 = 存量明文, 原样返回; 解密失败(篡改/换钥)抛 IllegalStateException */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败(密文被篡改或密钥不符)", e);
        }
    }

    /** 是否本工具密文格式 */
    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }
}
