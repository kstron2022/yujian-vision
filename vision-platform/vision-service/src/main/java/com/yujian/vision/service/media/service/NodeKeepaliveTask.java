package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 节点保活检查任务（docs/06 §7）：on_server_keepalive 超时 → 节点离线, 节点上会话批量清理。
 * Redis 保活键 TTL 45s = 3 × ZLM keepAliveSecond(15s)；节点已死时 tryZlm=false 跳过远端清理
 * （对死节点不做无谓远端调用, 残留远端资源由对账/none_reader 兜底）。
 */
@Slf4j
@Component
public class NodeKeepaliveTask {

    private static final String LOCK_KEY = "gb:lock:task:node-keepalive";
    /** 活跃态集合（与 DB 生成列 active_flag 口径一致） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");
    /** 节点离线通知 30 分钟去重键前缀（docs/09 M2 #8） */
    static final String NODE_OFFLINE_NOTICE_KEY = "gb:notice:node-offline:";
    /** 节点离线通知 type（命名对齐 DEVICE_OFFLINE, 文档口径: 节点=media server, 设备=GB 设备） */
    static final String NODE_OFFLINE_NOTICE_TYPE = "NODE_OFFLINE";

    private final GbMediaServerMapper serverMapper;
    private final SessionCleanupService cleanup;
    private final GbMediaSessionMapper sessionMapper;
    private final RedissonClient redisson;
    private final NoticeService notices;

    public NodeKeepaliveTask(GbMediaServerMapper serverMapper,
            SessionCleanupService cleanup, GbMediaSessionMapper sessionMapper, RedissonClient redisson,
            NoticeService notices) {
        this.serverMapper = serverMapper;
        this.cleanup = cleanup;
        this.sessionMapper = sessionMapper;
        this.redisson = redisson;
        this.notices = notices;
    }

    /** 30s 周期检查（fixedDelay: 上一轮结束后再计时, 慢轮次不会堆积重叠） */
    @Scheduled(fixedDelay = 30_000)
    public void reconcile() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            // 非阻塞抢锁(waitTime=0): 拿不到 = 另一实例正在跑, 本轮直接放弃
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            check();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void check() {
        List<GbMediaServerEntity> online = serverMapper.selectList(
                new LambdaQueryWrapper<GbMediaServerEntity>().eq(GbMediaServerEntity::getStatus, 1));
        for (GbMediaServerEntity node : online) {
            String key = HookService.NODE_KEEPALIVE_KEY + node.getServerId();
            if (redisson.<String>getBucket(key).get() != null) {
                continue;
            }
            markOffline(node);
        }
    }

    /**
     * 节点判离线: 状态落库 + 该节点活跃会话批量清理（B6 F2: TYPE_TALK 豁免——对讲媒体面为平台直推, 不依赖节点存活, SSRC 释放随对讲自身生命周期）。
     * tryZlm=false: 节点已死, 不做无谓远端调用(死节点 HTTP 调用只会白等 3s×N 拖长本轮)。
     */
    private void markOffline(GbMediaServerEntity node) {
        log.warn("节点离线: {} (保活超时)", node.getServerId());
        GbMediaServerEntity up = new GbMediaServerEntity();
        up.setId(node.getId());
        up.setStatus(0);
        serverMapper.updateById(up);
        List<GbMediaSessionEntity> active = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .ne(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_TALK)
                        .eq(GbMediaSessionEntity::getServerId, node.getServerId()));
        int cleaned = 0;
        for (GbMediaSessionEntity s : active) {
            try {
                cleanup.closeAfterRestart(s.getId(), "CLOSED", "stream_lost", "ZLM 节点离线(保活超时)", false);
                cleaned++;
            } catch (Exception e) {
                log.error("节点离线会话清理失败(继续下一条): id={} stream={}", s.getId(), s.getStreamId(), e);
            }
        }
        noticeOffline(node.getServerId(), cleaned);
    }

    /**
     * 节点离线站内通知（docs/09 M2 #8）：markOffline 每 30s 重入直到节点恢复, 必须 30 分钟
     * Redisson 去重（键 gb:notice:node-offline:{serverId}）——否则保活失联期间每轮都刷屏。
     * 命中去重窗口只 log.debug; Redis 异常 fail-open 照常发布（告警可见性优先于刷屏抑制）。
     */
    private void noticeOffline(String serverId, int cleanedSessions) {
        try {
            Boolean first = redisson.<String>getBucket(NODE_OFFLINE_NOTICE_KEY + serverId)
                    .setIfAbsent("1", Duration.ofMinutes(30));
            if (!Boolean.TRUE.equals(first)) {
                log.debug("节点离线通知 30 分钟去重命中, 静默: server={}", serverId);
                return;
            }
        } catch (Exception e) {
            log.warn("节点离线通知去重失败(fail-open 照常发布): server={} err={}", serverId, e.getMessage());
        }
        notices.publish(NODE_OFFLINE_NOTICE_TYPE, "节点离线: " + serverId,
                "节点 " + serverId + " 保活超时已判离线, 已批量清理其上活跃会话 " + cleanedSessions + " 条",
                2, serverId);
    }
}
