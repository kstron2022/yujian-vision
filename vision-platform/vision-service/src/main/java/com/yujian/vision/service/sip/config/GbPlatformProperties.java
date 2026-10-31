package com.yujian.vision.service.sip.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台 SIP 身份配置（vision.gb）。
 * server-id/domain/realm 生产环境必须按分配的国标编码配置（docs/10 安全基线）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.gb")
public class GbPlatformProperties {

    /** 平台国标编码（作为 SIP 用户） */
    private String serverId = "34020000002000000001";

    /** 平台国标域 */
    private String domain = "3402000000";

    /** Digest realm */
    private String realm = "3402000000";

    /** 自动建档设备的默认接入密码（生产必须预配置强密码, docs/10） */
    private String defaultDevicePassword = "12345678";

    /** 出站信令使用的栈 */
    private String stackId = "main";
}
