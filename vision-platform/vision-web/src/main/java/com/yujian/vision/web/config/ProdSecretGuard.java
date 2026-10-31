package com.yujian.vision.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产密钥守卫（docs/10 §1, C4 安全基线）：prod profile 下使用开发默认弱密钥直接拒绝启动。
 * JWT secret / 设备密码 AES key 泄漏后果 = 伪造会话 / 解出全部设备接入密码, 必须环境变量注入。
 */
@Component
@Profile("prod")
public class ProdSecretGuard implements ApplicationRunner {

    private static final String DEV_JWT_DEFAULT = "dev-only-secret-change-me-0123456789abcdef";
    private static final String DEV_AES_DEFAULT = "dev-only-aes-key-change-me-0123456789abcdef";
    private static final String DEV_ALERT_WEBHOOK_DEFAULT = "dev-alert-token-CHANGE-IN-PROD";
    private static final String DEV_HA_TOKEN_DEFAULT = "dev-ha-token-CHANGE-IN-PROD";
    private static final String DEV_SM4_DEFAULT = SmCipherConfig.DEV_SM4_DEFAULT;

    private final String jwtSecret;
    private final String aesKey;
    private final String alertWebhookToken;
    private final String haToken;
    private final boolean haEnabled;
    private final String sm4Key;
    private final boolean openapiEnabled;

    public ProdSecretGuard(
            @Value("${vision.security.jwt.secret}") String jwtSecret,
            @Value("${vision.ha.token}") String haToken,
            @Value("${vision.ha.enabled:false}") boolean haEnabled,
            @Value("${vision.security.aes.key}") String aesKey,
            @Value("${vision.security.alert-webhook-token}") String alertWebhookToken,
            @Value("${vision.crypto.sm4-key:}") String sm4Key,
            @Value("${vision.openapi.enabled:false}") boolean openapiEnabled) {
        this.jwtSecret = jwtSecret;
        this.haToken = haToken;
        this.haEnabled = haEnabled;
        this.aesKey = aesKey;
        this.alertWebhookToken = alertWebhookToken;
        this.sm4Key = sm4Key;
        this.openapiEnabled = openapiEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isWeak(jwtSecret, DEV_JWT_DEFAULT, 32)) {
            throw new IllegalStateException("生产环境检测到开发默认 JWT secret, 拒绝启动: 请通过环境变量 vision.jwt.secret 注入强密钥(>=32字符)");
        }
        if (isWeak(aesKey, DEV_AES_DEFAULT, 32)) {
            throw new IllegalStateException("生产环境检测到开发默认 AES key, 拒绝启动: 请通过环境变量 vision.aes.key 注入强密钥(>=32字符)");
        }
        if (isWeak(alertWebhookToken, DEV_ALERT_WEBHOOK_DEFAULT, 16)) {
            throw new IllegalStateException("生产环境检测到开发默认告警 webhook 令牌, 拒绝启动: 请通过环境变量 vision.alert-webhook-token 注入强令牌(>=16字符)");
        }
        // 仅 HA 启用时强制（单实例 prod 不启用 HA 则不要求配 token）
        if (haEnabled && isWeak(haToken, DEV_HA_TOKEN_DEFAULT, 16)) {
            throw new IllegalStateException("生产环境启用 HA 时检测到开发默认 HA 令牌, 拒绝启动: 请通过环境变量 VISION_HA_TOKEN 注入强令牌(>=16字符)");
        }
        // 仅开放接口启用时强制（docs/15 决策 D3: app_secret SM4 主密钥拒绝默认/弱值）
        if (openapiEnabled && isWeak(sm4Key, DEV_SM4_DEFAULT, 16)) {
            throw new IllegalStateException("生产环境启用开放接口时检测到默认/弱 SM4 主密钥, 拒绝启动: 请通过环境变量 vision.sm4.key 注入强密钥(>=16字符)");
        }
    }

    private static boolean isWeak(String value, String devDefault, int minLength) {
        return value == null || value.isBlank() || value.equals(devDefault) || value.length() < minLength;
    }
}
