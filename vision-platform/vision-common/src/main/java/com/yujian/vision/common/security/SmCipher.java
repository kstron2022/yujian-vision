package com.yujian.vision.common.security;

import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * 敏感字段 SM4-GCM 可逆加密（docs/15 决策 D3）：open_app.app_secret 落库加密。
 * 密文格式 "sm4$" + Base64(12B 随机 IV || 密文||tag(128bit))——形制逐行对齐 AesCipher
 * （AES-256-GCM 前缀 "enc:v1:"），前缀区分两代算法密文, 可共存同库。
 * 密钥派生：SM3(主密钥) 前 128bit（SM4 固定 128bit 密钥; 对比 AesCipher 为 SHA-256 全量 256bit）。
 * JCE Cipher + 显式 BC provider 实例（不注册 Security 全局 provider, docs/15 风险表）。
 */
public class SmCipher {

    /** 密文前缀（D3: sm4$ 区分算法; AesCipher 为 enc:v1:） */
    public static final String PREFIX = "sm4$";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LEN = 16;

    /** 显式 provider 实例（线程安全; 不调用 Security.addProvider 注册全局） */
    private static final Provider BC = new BouncyCastleProvider();

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SmCipher(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalArgumentException("SM4 密钥材料不能为空");
        }
        try {
            byte[] k = Arrays.copyOf(Sm3.digest(keyMaterial.getBytes(StandardCharsets.UTF_8)), KEY_LEN);
            this.key = new SecretKeySpec(k, "SM4");
        } catch (Exception e) {
            throw new IllegalStateException("SM4 密钥初始化失败", e);
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
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", BC);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("SM4 加密失败", e);
        }
    }

    /** null/blank 原样返回; 不带前缀 = 存量明文, 原样返回; 解密失败(篡改/换钥)抛 IllegalStateException */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", BC);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("SM4 解密失败(密文被篡改或密钥不符)", e);
        }
    }

    /** 是否本工具密文格式 */
    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }
}
