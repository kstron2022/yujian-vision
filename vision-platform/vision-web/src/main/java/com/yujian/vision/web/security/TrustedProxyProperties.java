package com.yujian.vision.web.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全相关可配置项（application.yaml vision.security）：当前仅"可信代理"。
 * <pre>
 * vision.security.trusted-proxies: [127.0.0.1, 172.16.0.0/12]   # 精确 IP 或 IPv4 CIDR
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.security")
public class TrustedProxyProperties {

    /** 可信代理地址列表（精确 IP 或 IPv4 CIDR）。空=不信任任何代理, 一律取 remoteAddr（XFF 视为不可信） */
    private List<String> trustedProxies = new ArrayList<>();
}
