package com.yujian.vision.web.config;

import com.yujian.vision.common.security.SmCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开放接口 SM4 加密器装配（docs/15 决策 D3/D7）：主密钥取 vision.crypto.sm4-key。
 * 仅 vision.openapi.enabled=true 时装配（D7: 未启用零行为变化——无 Bean、无过滤器、零副作用）;
 * 启用时密钥未配置/为开发默认值 → 启动 fail-fast（application.yaml 注释口径与环境变量注入说明）。
 * prod 下的弱密钥兜底另见 ProdSecretGuard。
 */
@Slf4j
@Configuration
public class SmCipherConfig {

    /** application.yaml 的开发占位默认值（与 yaml 中 ${vision.sm4.key:...} 保持一致） */
    static final String DEV_SM4_DEFAULT = "dev-only-sm4-key-change-me-0123456789ab";

    private final String sm4Key;

    public SmCipherConfig(
            @Value("${vision.crypto.sm4-key:}") String sm4Key,
            @Value("${vision.openapi.enabled:false}") boolean openapiEnabled) {
        this.sm4Key = sm4Key;
        // 仅配置了实际密钥（非 yaml 占位默认）却未开开关 → WARN 提醒（D7: 配置了不生效要让人知道）
        if (!openapiEnabled && sm4Key != null && !sm4Key.isBlank()
                && !DEV_SM4_DEFAULT.equals(sm4Key)) {
            log.warn("已配置 vision.crypto.sm4-key 但 vision.openapi.enabled=false, 开放接口不生效");
        }
    }

    /** SM4 加密器（open_app.app_secret 落库加密, docs/15 步骤2 消费）; 未启用开放接口不装配 */
    @Bean
    @ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
    public SmCipher smCipher() {
        if (sm4Key == null || sm4Key.isBlank() || DEV_SM4_DEFAULT.equals(sm4Key)) {
            throw new IllegalStateException(
                    "vision.openapi.enabled=true 但 vision.crypto.sm4-key 未配置或为开发默认值, 拒绝启动:"
                            + " 请通过环境变量 vision.sm4.key 注入强密钥(>=16字符)");
        }
        return new SmCipher(sm4Key);
    }
}
