package com.yujian.vision.web.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT/会话配置（application.yaml vision.security.jwt）
 * <pre>
 * expire-hours: 24  # token 硬上限（docs/03 §1.2）
 * idle-hours:   2   # 空闲滑动超时（docs/10 §1 会话超时）
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.security.jwt")
public class JwtProperties {
    private String secret;
    /** token 硬过期（小时） */
    private int expireHours = 24;
    /** 空闲滑动超时（小时）, 每次请求续期 */
    private int idleHours = 2;
}
