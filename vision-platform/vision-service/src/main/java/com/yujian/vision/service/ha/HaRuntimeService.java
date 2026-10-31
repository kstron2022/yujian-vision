package com.yujian.vision.service.ha;

import com.yujian.vision.sip.config.SipStackProperties;
import com.yujian.vision.sip.transport.SipStackDescriptor;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * HA 运行时：角色仲裁 + SIP 栈启停编排（M3-B6 阶段2, docs/01 §HA）。
 *
 * <p><b>角色与租约</b>：Redis 单 key 租约 {@code vision.ha.leader-key}(TTL lease-seconds, 值=selfId)。
 * 启动时 role=active 先抢租约：抢到=ACTIVE, 被他人持有=STANDBY（A 故障期自愈重启时 B 已接管,
 * A 以备用身份启动, 不跑 RecoveryService/不起栈——防脑裂双杀, docs/06 §8 门禁）。
 * ACTIVE 每 10s 仅自持续期; promote/demote 由内部管理口(/api/ha/internal/sip-stack/*)
 * 被 keepalived notify 脚本调用完成接管/回切。租约只解决"谁是活跃"的仲裁,
 * 与设备注册绑定(gb_device 表, 共享 MySQL)无关。</p>
 *
 * <p><b>为什么 ApplicationRunner 而非 ApplicationReadyEvent</b>：RecoveryService 的门禁依据
 * mayRecoverAtBoot 在 RecoveryService 自身(@EventListener(ApplicationReadyEvent))之前就必须就绪,
 * 而 ApplicationRunner 全部先于 ApplicationReadyEvent 执行——本类 @Order(0) 最先跑,
 * HaBootstrapper @Order(10) 依判定结果撤栈, SipListenerBinding 等无序 runner 随后。</p>
 *
 * <p><b>栈补起</b>：ensureStacks 每 5s 对 ACTIVE 身份补起缺失的配置栈——覆盖 A 启动窗口内
 * 端口被（尚未 demote 的）B 占有的竞态：bind 失败不致命, notify 回切链完成后下轮自动补起。
 * HA 未启用时不跑, 行为与单机部署一致（autoStart 失败不重试的历史语义）。</p>
 */
@Slf4j
@Service
@Order(0)
public class HaRuntimeService implements ApplicationRunner {

    enum Role { ACTIVE, STANDBY }

    private final HaProperties props;
    private final SipStackProperties sipProps;
    private final SipTransportProvider transport;
    private final RedissonClient redisson;
    private final org.springframework.core.env.Environment env;

    private final String selfId;
    /**
     * 运行时角色（volatile: 定时任务与管理口线程并发读写）。默认 STANDBY 而非 ACTIVE:
     * @EnableScheduling 随 context refresh 启动、先于 ApplicationRunner——若默认 ACTIVE,
     * run() 判定前 ensureStacks 的在途 tick 会以"活跃"身份起栈(HA 备台抢口实测踩坑);
     * 默认 STANDBY 只让 run() 前的数秒窗口闸门全关, 无功能影响(autoStart 在 refresh 期已完成)。
     */
    private volatile Role current = Role.STANDBY;
    /** 启动判定缓存: 本次启动是否允许执行崩溃恢复（RecoveryService 门禁依据） */
    private volatile boolean mayRecoverAtBoot = true;

    public HaRuntimeService(HaProperties props, SipStackProperties sipProps,
            SipTransportProvider transport, RedissonClient redisson,
            org.springframework.core.env.Environment env) {
        this.props = props;
        this.sipProps = sipProps;
        this.transport = transport;
        this.redisson = redisson;
        this.env = env;
        this.selfId = resolveSelfId();
    }

    // ------------------------------------------------------------- 启动仲裁

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            current = Role.ACTIVE;
            mayRecoverAtBoot = true;
            return;
        }
        if (isStandbyRoleConfigured()) {
            current = Role.STANDBY;
            mayRecoverAtBoot = false;
            log.info("HA 启动身份: STANDBY(配置 role=standby, SIP 栈等接管指令拉起)");
            return;
        }
        boolean got;
        String leader = null;
        try {
            got = bucket().setIfAbsent(selfId, Duration.ofSeconds(props.getLeaseSeconds()));
            if (!got) {
                leader = bucket().get();
            }
        } catch (Exception e) {
            // Redis 不可用 fail-open 为 ACTIVE: 保持单机部署历史行为（Redis 挂时平台本已全面降级）
            log.warn("HA 租约抢定失败(fail-open 按 ACTIVE 启动): {}", e.getMessage());
            got = true;
        }
        current = got ? Role.ACTIVE : Role.STANDBY;
        mayRecoverAtBoot = got;
        if (got) {
            log.info("HA 启动身份: ACTIVE(抢到租约 selfId={})", selfId);
        } else {
            log.warn("HA 启动身份: STANDBY(租约被他人持有 leader={}, 不做崩溃恢复/不起 SIP 栈)", leader);
        }
    }

    /** 启动角色是否配置为 standby（供测试与 run 使用） */
    boolean isStandbyRoleConfigured() {
        return "standby".equalsIgnoreCase(props.getRole());
    }

    // ------------------------------------------------------------- 接管/回切

    /**
     * 升为活跃: 拉起全部配置栈（任一失败全量回滚后抛出, 供 notify 脚本重试）→ 置 ACTIVE → 写租约。
     * 幂等: 栈已运行时 start 内部按 stackId 跳过。
     */
    public synchronized Map<String, Object> promote() {
        List<String> started = new ArrayList<>();
        try {
            for (SipStackProperties.StackSpec spec : sipProps.getStacks()) {
                transport.start(new SipStackDescriptor(spec.getStackId(), spec.getHost(),
                        spec.getPort(), spec.getTransport()));
                started.add(spec.getStackId());
            }
        } catch (RuntimeException e) {
            for (SipStackProperties.StackSpec spec : sipProps.getStacks()) {
                transport.stop(spec.getStackId());
            }
            log.error("HA promote 失败已回滚(全部栈停用): {}", e.getMessage());
            throw e;
        }
        current = Role.ACTIVE;
        try {
            bucket().set(selfId, Duration.ofSeconds(props.getLeaseSeconds()));
        } catch (Exception e) {
            log.warn("HA promote 写租约失败(fail-open, 角色已 ACTIVE): {}", e.getMessage());
        }
        log.warn("HA promote: 升为 ACTIVE, SIP 栈已拉起 {}", started);
        return status();
    }

    /**
     * 降为备用: 先关闸门（inviteTimeoutScan/级联 tick 等即时停）再停栈, 最后仅自持租约时删除。
     * notify 回切链先调本方 demote 再 promote 对端, 5060 释放后对端 bind 才能成功。
     */
    public synchronized Map<String, Object> demote() {
        current = Role.STANDBY;
        for (SipStackProperties.StackSpec spec : sipProps.getStacks()) {
            transport.stop(spec.getStackId());
        }
        try {
            if (selfId.equals(bucket().get())) {
                bucket().delete();
            }
        } catch (Exception e) {
            log.warn("HA demote 清租约失败(30s TTL 自愈): {}", e.getMessage());
        }
        log.warn("HA demote: 降为 STANDBY, SIP 栈已停用");
        return status();
    }

    // ------------------------------------------------------------- 周期任务

    /** 租约续期（10s）: 仅自持时 CAS 续期; 被他人持有不覆盖(双活竞态交 keepalived 仲裁), 只告警 */
    @Scheduled(fixedDelay = 10_000)
    public void refreshLease() {
        if (!props.isEnabled() || current != Role.ACTIVE) {
            return;
        }
        try {
            // 这版 Redisson 的 compareAndSet 无 TTL 重载: get 确认自持后带 TTL 覆盖写。
            // 覆盖窗口仅当租约已被他人抢走才可能误写——本方 10s 一续、TTL 30s, 实践不可达
            if (selfId.equals(bucket().get())) {
                bucket().set(selfId, Duration.ofSeconds(props.getLeaseSeconds()));
            } else {
                log.error("HA 租约被他人持有(双活竞态?): leader={}, 本机保持 ACTIVE 等外部仲裁", bucket().get());
            }
        } catch (Exception e) {
            log.warn("HA 租约续期失败(fail-open): {}", e.getMessage());
        }
    }

    /** 栈补起（5s）: ACTIVE 身份下补起缺失配置栈; STANDBY/未启用不跑（见类注释） */
    @Scheduled(fixedDelay = 5_000)
    public void ensureStacks() {
        if (!props.isEnabled() || current != Role.ACTIVE) {
            return;
        }
        for (SipStackProperties.StackSpec spec : sipProps.getStacks()) {
            if (transport.isRunning(spec.getStackId())) {
                continue;
            }
            try {
                transport.start(new SipStackDescriptor(spec.getStackId(), spec.getHost(),
                        spec.getPort(), spec.getTransport()));
                log.info("HA 栈补起成功: {}", spec.getStackId());
            } catch (Exception e) {
                log.warn("HA 栈补起失败(5s 后重试): {} {}", spec.getStackId(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------- 查询

    /** SIP 侧副作用闸门是否开放（inviteTimeoutScan/级联与订阅 tick/恢复门禁共用） */
    public boolean sipGatesOpen() {
        return current == Role.ACTIVE;
    }

    public boolean isStandby() {
        return current == Role.STANDBY;
    }

    /** 本次启动是否允许崩溃恢复（RecoveryService 门禁） */
    public boolean mayRecoverAtBoot() {
        return mayRecoverAtBoot;
    }

    /** 运行态观测（/api/ha/internal/status） */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("role", current == Role.ACTIVE ? "active" : "standby");
        m.put("selfId", selfId);
        m.put("activeStacks", transport.activeStacks());
        try {
            m.put("leader", bucket().get());
        } catch (Exception e) {
            m.put("leader", null);
        }
        m.put("peerBaseUrl", props.getPeerBaseUrl());
        return m;
    }

    String selfId() {
        return selfId;
    }

    private RBucket<String> bucket() {
        return redisson.getBucket(props.getLeaderKey());
    }

    /** 实例标识 hostname:server.port（租约值/日志用, 需在人读日志时能区分 A/B） */
    private String resolveSelfId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + ":" + env.getProperty("server.port", "8080");
    }
}
