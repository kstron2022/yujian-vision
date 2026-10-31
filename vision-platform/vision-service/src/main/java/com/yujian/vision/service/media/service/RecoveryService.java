package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 崩溃恢复（docs/06 §8）：平台进程重启后，ZLM 与 SIP 对话是外部资源、平台侧状态只是账本。
 * 启动完成后扫全部活跃态会话 → 逐条 best-effort 清理（跳过 BYE + closeRtpServer
 * + close_streams + 释放 SSRC）→ 重建 SSRC 占用表。宁可先关流让用户重新点播（秒级），
 * 也不保留可能已脏的会话。
 */
@Slf4j
@Component
public class RecoveryService {

    /** 活跃态集合（与 DB 生成列 active_flag 口径一致, docs/06 §8.1） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");

    private final GbMediaSessionMapper sessionMapper;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final SessionCleanupService cleanup;
    private final SsrcPoolService ssrcPool;
    private final com.yujian.vision.service.ha.HaRuntimeService ha;

    /** 进程启动时刻（评审修复 R2: Tomcat 就绪早于 ApplicationReadyEvent, 启动窗口内的新点播不得被误杀） */
    private final LocalDateTime startupAt = LocalDateTime.now();

    /** ApplicationReadyEvent 单次守卫（防 devtools 热重载等重复触发） */
    private final AtomicBoolean done = new AtomicBoolean(false);

    public RecoveryService(GbMediaSessionMapper sessionMapper, GbMediaServerMapper serverMapper,
            ZlmApiClient zlm, SessionCleanupService cleanup, SsrcPoolService ssrcPool,
            com.yujian.vision.service.ha.HaRuntimeService ha) {
        this.sessionMapper = sessionMapper;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.cleanup = cleanup;
        this.ssrcPool = ssrcPool;
        this.ha = ha;
    }

    /** 启动完成后执行：晚于 SipListenerBinding 等 ApplicationRunner, 此时信令/ZLM/Redis 均已就绪 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        // HA 门禁（M3-B6 阶段2）：本进程以备用身份启动（租约被另一活跃实例持有）时, 跳过崩溃恢复——
        // 否则会把对方在播的活跃会话清掉 + resetInUse 清掉对方仍占用的共享 SSRC 表。会话归其管,
        // 残留泄漏由 inviteTimeoutScan/none_reader/对账任务兜底（均 DB 驱动, 不依赖本进程状态）。
        if (!ha.mayRecoverAtBoot()) {
            log.warn("HA 门禁: 另一活跃实例在服(leader={}), 跳过崩溃恢复(会话归其管)", ha.status().get("leader"));
            return;
        }
        try {
            recover();
        } catch (Exception e) {
            // 恢复是 best-effort, 失败不阻断启动（对账任务/none_reader 仍会兜底）
            log.error("崩溃恢复执行异常(不阻断启动)", e);
        }
    }

    /** 包私有便于单测 */
    void recover() {
        List<GbMediaSessionEntity> stale = sessionMapper.selectList(buildStaleQuery(startupAt));
        if (stale.isEmpty()) {
            log.info("崩溃恢复: 无平台重启遗留的活跃会话");
        } else {
            log.warn("崩溃恢复: 发现 {} 条平台重启遗留的活跃态会话, 开始清理(跳过 BYE: 对话 tag 已随进程丢失)", stale.size());
        }
        // 节点预检（评审修复 R1: ZLM 不可达时每条会话各挂一次 3s connect 超时会拖死启动, 不可达节点直接降级）
        Set<String> reachable = new HashSet<>();
        for (GbMediaServerEntity node : serverMapper.selectList(null)) {
            try {
                zlm.getMediaList(node.getServerId());
                reachable.add(node.getServerId());
            } catch (Exception e) {
                log.warn("恢复预检: ZLM 节点不可达, 跳过该节点的远端清理: {} ({})", node.getServerId(), e.getMessage());
            }
        }
        int closed = 0;
        int failed = 0;
        for (GbMediaSessionEntity s : stale) {
            try {
                cleanup.closeAfterRestart(s.getId(), "CLOSED", "platform_restart", "platform-restart",
                        reachable.contains(s.getServerId()));
                closed++;
            } catch (Exception e) {
                failed++;
                log.error("崩溃恢复清理失败(继续下一条): id={} stream={}", s.getId(), s.getStreamId(), e);
            }
        }
        // 重建 SSRC 占用表（docs/06 §8.4）: 正常应已为空集, 清掉历史脏数据
        int wiped = 0;
        for (GbMediaServerEntity node : serverMapper.selectList(null)) {
            try {
                ssrcPool.resetInUse(node.getServerId());
                wiped++;
            } catch (Exception e) {
                log.warn("SSRC 占用表重建失败: server={}, {}", node.getServerId(), e.getMessage());
            }
        }
        if (closed > 0 || failed > 0) {
            log.warn("崩溃恢复完成: 清理 {} 条, 失败 {} 条, SSRC 占用表重建 {} 节点", closed, failed, wiped);
        }
    }

    /**
     * 平台重启遗留会话查询条件（评审修复 R2）：活跃态 且 创建早于进程启动时刻。
     * create_time &gt;= startupAt 的新点播由 SQL 排除, 不被恢复清理误杀。包私有静态便于单测断言。
     */
    static LambdaQueryWrapper<GbMediaSessionEntity> buildStaleQuery(LocalDateTime startupAt) {
        return new LambdaQueryWrapper<GbMediaSessionEntity>()
                .in(GbMediaSessionEntity::getStatus, ACTIVE)
                .lt(GbMediaSessionEntity::getCreateTime, startupAt);
    }
}
