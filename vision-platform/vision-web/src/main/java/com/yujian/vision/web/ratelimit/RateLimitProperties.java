package com.yujian.vision.web.ratelimit;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 接口限流配置（application.yaml vision.rate-limit）。
 * <pre>
 * enabled: true|false        # 总开关, false 时放行且不计数
 * limits:                    # 生效阈值表, 覆盖注解兜底值（login=60/min per IP, play=120/min per 用户）
 *   login: 60
 *   play: 120
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.rate-limit")
public class RateLimitProperties {

    /** 总开关, false 放行且不计数 */
    private boolean enabled = true;

    /** 生效阈值表: key=注解 name, 覆盖注解 limit 兜底值 */
    private Map<String, Integer> limits = new HashMap<>();
}
