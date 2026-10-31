package com.yujian.vision.service.ha;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SIP 主备高可用配置（M3-B6 阶段2, docs/01 §HA / deploy/README §8）。
 *
 * <p>默认 enabled=false = 单机部署历史行为零变化。双实例部署时 A/B 差异全部走环境变量
 * （SERVER_ADDRESS / VISION_HA_* / VISION_SIP_AUTOSTART），不加 profile 不加第二份 yaml。
 * 部署形态与演练步骤见 deploy/README §8；keepalived 只做健康状态机与 notify 编排,
 * 平台侧机制（租约/接管/门禁）不依赖 keepalived 存在。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.ha")
public class HaProperties {

    /** HA 总开关: false 时本进程恒为活跃身份, 行为与单机部署完全一致 */
    private boolean enabled = false;

    /** 启动角色: active=抢租约定身份(生产 A); standby=恒备用等接管指令(生产 B)。默认 active */
    private String role = "active";

    /**
     * 内部管理 API 令牌（/api/ha/internal/*, 请求头 X-Vision-Ha-Token 恒定时间比对）。
     * dev 默认占位; prod 启用 HA 时必须 VISION_HA_TOKEN 注入强令牌（ProdSecretGuard 拒弱值）
     */
    private String token = "dev-ha-token-CHANGE-IN-PROD";

    /** 备台转发对端(活跃实例)的 base url: HaForwardFilter 把 SIP 耦合写操作转发到这里 */
    private String peerBaseUrl = "http://127.0.0.1:8082";

    /**
     * 备台态转发前缀（requestURI startsWith）。SIP 耦合的写操作类端点——备台本地无栈必失败,
     * 转发给活跃实例执行; GET 查询/登录/hooks/WS 不在其中(共享 DB 本地可答)。
     * 录像检索等 GET 类 SIP 耦合操作不在转发范围: A 宕机且 B 未接管的窗口内不可用(文档挂账)。
     */
    private List<String> forwardPrefixes = List.of(
            "/api/play", "/api/playback", "/api/talk", "/api/records", "/api/record",
            "/api/ptz", "/api/devices", "/api/channels", "/api/cascade");

    /** 备台态转发谓词的 HTTP 方法白名单 */
    private List<String> forwardMethods = List.of("POST", "PUT", "DELETE");

    /** 转发超时毫秒（活跃实例不可达时快速失败回 503"正在切换"） */
    private int forwardTimeoutMs = 8000;

    /** Redis 领导租约键（单 key, 值=当前活跃实例 selfId; 与设备注册绑定 gb_device 无关） */
    private String leaderKey = "gb:ha:leader";

    /** 租约 TTL 秒（refreshLease 每 10s 续期, 3 倍冗余） */
    private int leaseSeconds = 30;
}
