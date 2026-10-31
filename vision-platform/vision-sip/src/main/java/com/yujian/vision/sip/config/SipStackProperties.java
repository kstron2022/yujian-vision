package com.yujian.vision.sip.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SIP 栈配置（application-*.yaml 中 vision.sip.stacks）。
 * 多栈分片：按设备编码哈希分散到 N 个栈实例，规避 jain-sip 单栈锁竞争（docs/01 §5）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.sip")
public class SipStackProperties {

    private List<StackSpec> stacks = new ArrayList<>();

    /**
     * SIP 宣告地址（可选, prod 环境变量 VISION_SIP_PUBLIC_IP）: host=0.0.0.0 监听全部网卡时,
     * Via/Contact 等消息地址兜底取本机 IP（resolveLocalIp）——容器/NAT 部署下那是内网地址,
     * 规范实现的对端按 Via 回包即断。配置后所有栈优先宣告此地址（监听仍按各自 host）,
     * 单机裸跑无需配置（空 = 历史行为）。
     */
    private String publicIp = "";

    /**
     * 是否随进程自动启动全部配置栈（M3-B6 阶段2 SIP 主备）: HA 备台(B)置 false——
     * 端口由主台持有, 备台等 promote(/api/ha/internal/sip-stack/start) 时才绑定;
     * 主台(A)/单机部署保持 true(默认) = 历史行为。栈启动失败的补起重试由
     * HaRuntimeService.ensureStacks 负责(HA 启用时), 本模块不感知 HA 角色。
     */
    private boolean autostart = true;

    @Data
    public static class StackSpec {
        private String stackId;
        private String host = "0.0.0.0";
        private int port = 5060;
        /** UDP / TCP */
        private String transport = "UDP";
    }
}
