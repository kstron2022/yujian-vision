package com.yujian.vision.service.ha;

import com.yujian.vision.sip.config.SipStackProperties;
import com.yujian.vision.sip.transport.SipTransportProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * HA 备用身份启动收口（M3-B6 阶段2）：HaRuntimeService.run()（@Order(0) ApplicationRunner）
 * 判定为 STANDBY 时, 在其余 runner 之前撤掉 autoStart 已拉起的栈——A 故障期自愈重启且 B 已接管
 * （租约被持）场景, A 不得持 5060 与 B 抢口。HA 未启用/ACTIVE 身份零操作。
 * （ApplicationRunner 全部先于 ApplicationReadyEvent; RecoveryService 等事件监听在判定后运行。）
 */
@Slf4j
@Component
@Order(10)
public class HaBootstrapper implements ApplicationRunner {

    private final HaProperties props;
    private final HaRuntimeService ha;
    private final SipTransportProvider transport;
    private final SipStackProperties sipProps;

    public HaBootstrapper(HaProperties props, HaRuntimeService ha,
            SipTransportProvider transport, SipStackProperties sipProps) {
        this.props = props;
        this.ha = ha;
        this.transport = transport;
        this.sipProps = sipProps;
    }
    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled() || !ha.isStandby() || sipProps.getStacks().isEmpty()) {
            return;
        }
        for (SipStackProperties.StackSpec spec : sipProps.getStacks()) {
            if (spec.getStackId() == null) {
                continue;  // 命令行索引覆盖等场景可能产生未完整绑定的占位元素
            }
            transport.stop(spec.getStackId());
        }
        log.warn("HA 备用身份启动: 已停用 SIP 栈(租约被他人持有), 等 /api/ha/internal/sip-stack/start 接管");
    }
}
