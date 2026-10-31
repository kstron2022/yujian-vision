package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话对账任务（docs/06 §7）：平台侧会话只是账本, 周期与 ZLM 实际流状态比对（60s 一轮）。
 * 正向: ZLM 无此流 → 断流兜底关会话; viewer_count 与 ZLM 不一致 → 以 ZLM 为准修正。
 * 反向: ZLM 上平台无主流的 rtp 流 → 强制注销（防外来/残留流串流）。
 * 多实例部署时以 Redisson 分布式锁保证单实例执行（docs/02 调度任务表格: 分布式锁=是）。
 * HA 门禁(M3-B6 阶段2): 判死须读主台进程内的暂停豁免态(isPauseProtected), 备台整轮跳过——
 * 否则备台抢到锁后会把主台暂停中的回放按"ZLM 无此流"误杀。
 */
@Slf4j
@Component
public class SessionReconcileTask {

    private static final String LOCK_KEY = "gb:lock:task:session-reconcile";
    private static final String STREAMING = "STREAMING";
    /** 活跃态全集（反向对账保护口径: 与 DB 生成列 active_flag 一致） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");
    private static final String RTP_APP = "rtp";
    /** 端口容量告警阈值: 当前流数占端口段容量的比例 */
    private static final double CAPACITY_ALARM_RATIO = 0.8;
    /** 端口容量告警 30 分钟去重键前缀（docs/09 M2 #8: 告警升级为站内通知后防刷屏） */
    static final String PORT_CAPACITY_NOTICE_KEY = "gb:notice:port-capacity:";
    /** 端口容量告警通知 type（命名沿用现有告警类通知风格: DEVICE_OFFLINE/CATALOG_SYNC_FAIL） */
    static final String PORT_CAPACITY_NOTICE_TYPE = "NODE_CAPACITY";
    /** 新建会话宽限期: 创建后该时长内不做正向判死/viewer 修正（防 ZLM 流表短暂不一致误杀, 如节点重启重注册窗口） */
    static final long GRACE_SECONDS = 90;

    private final GbMediaSessionMapper sessionMapper;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final SessionCleanupService cleanup;
    private final MediaSessionService mediaSessionService;
    private final StreamEventPublisher publisher;
    private final RedissonClient redisson;
    private final NoticeService notices;
    /** SIP 主备（M3-B6 阶段2）: 备台身份不做对账判死（暂停豁免态在主台进程内存） */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;
    private final StreamProxyService streamProxies;

    public SessionReconcileTask(GbMediaSessionMapper sessionMapper, GbMediaServerMapper serverMapper,
            ZlmApiClient zlm, SessionCleanupService cleanup, MediaSessionService mediaSessionService,
            StreamEventPublisher publisher, RedissonClient redisson, NoticeService notices,
            com.yujian.vision.service.ha.HaRuntimeService ha, StreamProxyService streamProxies) {
        this.sessionMapper = sessionMapper;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.cleanup = cleanup;
        this.mediaSessionService = mediaSessionService;
        this.publisher = publisher;
        this.redisson = redisson;
        this.notices = notices;
        this.ha = ha;
        this.streamProxies = streamProxies;
    }

    /** 60s 周期对账（fixedDelay: 上一轮结束后再计时, 慢轮次不会堆积重叠） */
    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        if (!ha.sipGatesOpen()) {
            return;
        }
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
            doReconcile();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void doReconcile() {
        // 活跃态全查: STREAMING 走正向对账; INIT/INVITING/CLOSING 仅参与反向保护
        // (评审修复 RF2: INVITE 进行中/CLOSING 窗口内 ZLM 已见流, 反向集合不含会被误判"无主流"而强关合法流)
        List<GbMediaSessionEntity> active = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>().in(GbMediaSessionEntity::getStatus, ACTIVE));
        Map<String, List<GbMediaSessionEntity>> sessionsByServer = active.stream()
                .filter(s -> s.getServerId() != null)
                .collect(Collectors.groupingBy(GbMediaSessionEntity::getServerId));
        Map<String, GbMediaServerEntity> nodes = serverMapper.selectList(null).stream()
                .filter(n -> n.getServerId() != null)
                .collect(Collectors.toMap(GbMediaServerEntity::getServerId, Function.identity(), (a, b) -> a));
        // 有会话的节点(含节点行已删除的残留会话)
        for (Map.Entry<String, List<GbMediaSessionEntity>> entry : sessionsByServer.entrySet()) {
            reconcileServer(entry.getKey(), entry.getValue(), nodes.get(entry.getKey()));
        }
        // 评审修复 RF1: 空闲节点(无活跃会话)也参与反向清理与 stream_count 落库
        for (Map.Entry<String, GbMediaServerEntity> n : nodes.entrySet()) {
            if (!sessionsByServer.containsKey(n.getKey())) {
                reconcileServer(n.getKey(), List.of(), n.getValue());
            }
        }
    }

    /**
     * 单节点对账。ZLM 摸不到不代表会话已死: 节点不可达时本轮整节点跳过, 绝不误杀会话
     * （对端可能只是网络抖动, 下一轮再对; 崩溃恢复/none_reader 另有兜底）。
     */
    private void reconcileServer(String serverId, List<GbMediaSessionEntity> sessions, GbMediaServerEntity node) {
        List<ZlmMediaInfo> medias;
        try {
            medias = zlm.listMedia(serverId);
        } catch (Exception e) {
            log.warn("对账: 节点不可达, 本轮跳过: {} ({})", serverId, e.getMessage());
            return;
        }
        Map<String, ZlmMediaInfo> byStream = (medias == null ? List.<ZlmMediaInfo>of() : medias).stream()
                .filter(m -> RTP_APP.equals(m.app()))
                .collect(Collectors.toMap(ZlmMediaInfo::stream, Function.identity(), (a, b) -> a));

        // 1. 正向: 仅 STREAMING 会话参与断流兜底与 viewer_count 修正
        //    (INVITING/CLOSING 窗口内 ZLM 可能尚无流或流未就绪, 不据此判死; 新建会话有宽限期)
        LocalDateTime graceBefore = LocalDateTime.now().minusSeconds(GRACE_SECONDS);
        for (GbMediaSessionEntity s : sessions) {
            if (!STREAMING.equals(s.getStatus())) {
                continue;
            }
            if (s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_TALK) {
                // M3-B5: 对讲设备腿无设备回传时不产 ZLM 流, 正向断流判死不适用;
                // 对讲活性由 begin 推流与 send_rtp_stopped 兜底, 音频源流在 app=talk 不入 byStream
                continue;
            }
            if (s.getCreateTime() != null && s.getCreateTime().isAfter(graceBefore)) {
                continue;
            }
            ZlmMediaInfo media = byStream.get(s.getStreamId());
            if (media == null) {
                if (mediaSessionService.isPauseProtected(s.getId())) {
                    // 暂停豁免（B2 必改 #1）: 暂停超过 ZLM 收流超时后 ZLM 侧无此流属预期,
                    // 会话由 RESUME/SEEK 重开续播, 对账不得判死
                    continue;
                }
                if (mediaSessionService.isSeeking(s.getStreamId())) {
                    // M4-B3 SEEK 豁免: ONVIF 回放重建代理的 del→add 间隙 ZLM 无此流属预期,
                    // 标记 TTL 20s 兜底收敛, 对账不得判死（评审 B3-P2-1）
                    continue;
                }
                // 60s 周期任务, 清理串行可接受（清理含 ZLM HTTP 调用, 不为此自建线程池）
                if (cleanup.close(s.getId(), "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)")) {
                    publisher.streamClosed(s.getStreamId(), "stream_lost");
                }
                mediaSessionService.forgetPause(s.getId());
            } else if (media.totalReaderCount() != null
                    && !media.totalReaderCount().equals(s.getViewerCount())) {
                updateViewerCount(s, media.totalReaderCount());
            }
        }

        // 2. 节点流数落库 + 端口容量告警
        if (node != null) {
            persistStreamCount(node, byStream.size());
        }

        // 3. 反向: ZLM 上 app=rtp 但平台无对应活跃会话 → 强制注销（防外来/残留流串流）
        //    保护口径取全部活跃态会话的 streamId + 对讲音频源流 ID: INIT/INVITING/CLOSING 的流不判"无主流"
        //    M5-B1(评审 P0-2): 并入常驻拉流 streamId——常驻流无观众时平台侧无会话行,
        //    不豁免会被本对账强关 → 收敛循环重拉 → 60s 再关的死循环
        Set<String> platformStreams = sessions.stream()
                .map(GbMediaSessionEntity::getStreamId)
                .collect(Collectors.toSet());
        platformStreams.addAll(streamProxies.alwaysOnStreamIds());
        for (String stream : byStream.keySet()) {
            if (!platformStreams.contains(stream)) {
                zlm.closeStreams(serverId, RTP_APP, stream);
                log.warn("对账: 注销无主流(防串流兜底): server={} stream={}", serverId, stream);
            }
        }
    }

    /** viewer_count 修正（乐观条件更新: 仅当会话仍 STREAMING 才生效, 不覆盖并发落库的终态） */
    private void updateViewerCount(GbMediaSessionEntity s, Integer readers) {
        LambdaUpdateWrapper<GbMediaSessionEntity> uw = new LambdaUpdateWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getId, s.getId())
                .eq(GbMediaSessionEntity::getStatus, STREAMING)
                .set(GbMediaSessionEntity::getViewerCount, readers);
        int updated = sessionMapper.update(null, uw);
        if (updated > 0) {
            log.info("对账修正 viewer_count: stream={} {} -> {}", s.getStreamId(), s.getViewerCount(), readers);
        }
    }

    /** 节点当前 rtp 流数落库（管理端/容量评估数据源） + 端口段 80% 容量告警 */
    private void persistStreamCount(GbMediaServerEntity node, int rtpCount) {
        GbMediaServerEntity up = new GbMediaServerEntity();
        up.setId(node.getId());
        up.setStreamCount(rtpCount);
        serverMapper.updateById(up);
        Integer start = node.getRtpPortStart();
        Integer end = node.getRtpPortEnd();
        if (start == null || end == null || start <= 0 || end <= 0) {
            return;
        }
        int capacity = end - start + 1;
        if (rtpCount >= capacity * CAPACITY_ALARM_RATIO) {
            log.warn("对账: 端口容量告警: server={} 当前 {} 流, 超过端口段 80% ({}/{})",
                    node.getServerId(), rtpCount, rtpCount, capacity);
            alertPortCapacity(node.getServerId(), rtpCount, capacity);
        }
    }

    /**
     * 端口容量告警升级（docs/09 M2 #8）：原 log.warn 升级为站内通知（消息中心可见, WS 推送）。
     * Redisson setIfAbsent 30 分钟去重 —— 60s 周期任务在容量持续超限时必然反复触发,
     * 命中去重窗口只 log.debug, 不重复打扰用户; Redis 异常 fail-open 照常发布（告警可见性优先）。
     */
    private void alertPortCapacity(String serverId, int rtpCount, int capacity) {
        try {
            Boolean first = redisson.<String>getBucket(PORT_CAPACITY_NOTICE_KEY + serverId)
                    .setIfAbsent("1", Duration.ofMinutes(30));
            if (!Boolean.TRUE.equals(first)) {
                log.debug("对账: 端口容量告警 30 分钟去重命中, 静默: server={}", serverId);
                return;
            }
        } catch (Exception e) {
            log.warn("端口容量告警去重失败(fail-open 照常发布): server={} err={}", serverId, e.getMessage());
        }
        notices.publish(PORT_CAPACITY_NOTICE_TYPE, "端口容量告警: " + serverId,
                "节点 " + serverId + " 当前 rtp 流数 " + rtpCount + ", 已达端口段容量 " + capacity + " 的 80%",
                2, serverId);
    }
}
