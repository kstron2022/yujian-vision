package com.yujian.vision.web.config;

import com.yujian.vision.common.security.AesCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 敏感字段加密器装配（docs/02 §0）：密钥取 vision.security.aes.key（生产环境变量注入,
 * 弱默认值由 ProdSecretGuard 拒绝启动）。纯工具 Bean, 无依赖, 无循环依赖风险。
 *
 * <p>密钥轮换（docs/10 §1）：可选旧密钥 legacyAesCipher 仅在轮换窗口存在——运维把
 * VISION_AES_OLD_KEY 临时写入 vision.env 后重启, SecurityDataSweep 用它识别存量旧密文
 * 并迁移到当前密钥; 迁移完成移除 OLD_KEY 后该 Bean 不再装配, 行为与既往版本一致。
 * 平时不配置 = 功能不启用（装配方法返回 null, 注入点得到 null）。
 */
@Slf4j
@Configuration
public class AesCipherConfig {

    /** 当前密钥加密器（业务加解密唯一入口; @Primary 使既有注入点不受 legacy Bean 影响共用它） */
    @Bean
    @Primary
    public AesCipher aesCipher(@Value("${vision.security.aes.key}") String key) {
        return new AesCipher(key);
    }

    /** 旧密钥加密器（仅轮换窗口装配, 供 SecurityDataSweep 迁移存量旧密文; 平时不配置返回 null） */
    @Bean(name = "legacyAesCipher")
    public AesCipher legacyAesCipher(@Value("${vision.security.aes.key}") String key,
            @Value("${vision.security.aes.old-key:}") String oldKey) {
        if (oldKey == null || oldKey.isBlank()) {
            return null;
        }
        if (oldKey.equals(key)) {
            log.warn("vision.security.aes.old-key 与当前密钥相同, 无轮换必要, 忽略");
            return null;
        }
        return new AesCipher(oldKey);
    }
}
