package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.media.zlm.ZlmAddStreamProxyRequest;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.media.zlm.ZlmOpenRtpServerRequest;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.monitor.VisionMetrics;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 媒体会话状态机与点播编排（docs/06 §2, docs/05 §4/§5）。
 */
@Slf4j
@Service
public class MediaSessionService {

    /** 活跃态集合（与 DB 生成列 active_flag 口径一致） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");

    /** 实况流 ID 前缀（streamId=gb_play_{channelId} 固定值, docs/06 §3; 与码流档无关） */
    public static final String LIVE_STREAM_PREFIX = "gb_play_";

    /** 回放控制命令（docs/03 §6: PAUSE/RESUME/SCALE/SEEK） */
    public static final String CMD_PAUSE = "PAUSE";
    public static final String CMD_RESUME = "RESUME";
    public static final String CMD_SCALE = "SCALE";
    public static final String CMD_SEEK = "SEEK";

    /** SCALE 合法档位（docs/03 §6） */
    private static final Set<java.math.BigDecimal> VALID_SCALES =
            Set.of(new java.math.BigDecimal("0.25"), new java.math.BigDecimal("0.5"),
                    new java.math.BigDecimal("1"), new java.math.BigDecimal("2"),
                    new java.math.BigDecimal("4"));

    /**
     * 回放会话位置钟 + 暂停态（docs/07 §3, B2 必改 #1）。
     * PAUSE 后设备停发 RTP, ZLM 收流侧按 RTP gap 超时(receiveTimeoutSec)自关并触发死亡 hook;
     * 暂停中(会话仍 STREAMING)豁免三条判死路径(on_stream_changed 注销 / on_rtp_server_timeout /
     * 60s 对账), 死亡 hook 只置 mediaDead 不判死; RESUME/SEEK 时若 mediaDead 或暂停已超阈值
     * (死亡 hook 失联兜底) 先重开收流端口, 再对话内 re-INVITE(t=续播点) 让设备重推。
     * 位置钟: pos(窗口时基)+锚点墙钟+倍速, 播放中推进、暂停冻结 —— 续播 re-INVITE 的 t= 必须
     * 等于设备暂停点, 否则设备从错位时间点重推; 重开后设备大多按 1x 起播, rate 复位 1 对齐。
     * key=sessionId; http 控制线程建/改(持锁), 死亡 hook 只写 mediaDead/读 paused(volatile)。
     */
    private static final class PauseCtx {
        LocalDateTime pos;            // 位置钟: 锚点内容位置(会话播放窗口时基)
        LocalDateTime anchoredAt;     // 位置钟: 锚点墙钟(播放中=上次推进; 暂停=暂停时刻)
        double rate = 1.0;            // 倍速(SCALE 档; 重开续播后复位 1, 前端按需再调)
        volatile boolean paused;      // 暂停中(PAUSE 置位; RESUME/SEEK/续播清)
        volatile boolean mediaDead;   // 暂停中收流侧已被 ZLM 自关(死亡 hook 置位; 续播流重注册清)
    }

    private final GbMediaSessionMapper sessionMapper;
    private final GbMediaServerMapper serverMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final SsrcPoolService ssrcPool;
    private final ZlmApiClient zlm;
    private final InviteService invite;
    private final PlayUrlService urlService;
    private final StreamEventPublisher publisher;
    private final SessionCleanupService cleanup;
    private final SipTransportProvider transport;
    private final GbPlatformProperties platform;
    private final MediaProperties media;
    private final RedissonClient redisson;
    private final VisionMetrics metrics;
    /** 点播收流节点调度（M3-B6 阶段1）: 替代原 status=1 LIMIT 1 直取 */
    private final MediaNodeScheduler nodeScheduler;
    /** SIP 主备运行时（M3-B6 阶段2）: 备台身份下停跑 inviteTimeoutScan(在途应答判定是进程内存态) */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;
    /** 跨节点代理（M3-B7 按需点播: 已有流在别的节点时给请求节点补一条回源代理） */
    private final StreamProxyService streamProxies;
    /** 接入协议注册表（M4-B2: ONVIF 点播分流取流地址） */
    private final DeviceAccessRegistry accessRegistry;

    /** callId → sessionId（INVITE 响应关联） */
    private final Map<String, Long> callIndex = new ConcurrentHashMap<>();
    /** 回放暂停态/位置钟（key=sessionId; 会话流就绪时建, 终态清理 forgetPause 移除） */
    private final Map<Long, PauseCtx> pauseCtx = new ConcurrentHashMap<>();
    /** 已下发的 videoCodec（sessionId→H264/H265, B5 迟到编码补投去重; 会话注销时清理） */
    private final Map<Long, String> publishedCodec = new ConcurrentHashMap<>();
    /** 信令回调/清理异步池（F4: SIP dispatch 线程内不做阻塞 IO） */
    private final java.util.concurrent.ExecutorService asyncPool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "media-async");
                t.setDaemon(true);
                return t;
            });

    public MediaSessionService(GbMediaSessionMapper sessionMapper, GbMediaServerMapper serverMapper,
            GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            SsrcPoolService ssrcPool, ZlmApiClient zlm, InviteService invite,
            PlayUrlService urlService, StreamEventPublisher publisher,
            SessionCleanupService cleanup, SipTransportProvider transport,
            GbPlatformProperties platform, MediaProperties media, RedissonClient redisson,
            VisionMetrics metrics, MediaNodeScheduler nodeScheduler,
            com.yujian.vision.service.ha.HaRuntimeService ha, StreamProxyService streamProxies,
            DeviceAccessRegistry accessRegistry) {
        this.sessionMapper = sessionMapper;
        this.serverMapper = serverMapper;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.ssrcPool = ssrcPool;
        this.zlm = zlm;
        this.invite = invite;
        this.urlService = urlService;
        this.publisher = publisher;
        this.cleanup = cleanup;
        this.transport = transport;
        this.platform = platform;
        this.media = media;
        this.redisson = redisson;
        this.metrics = metrics;
        this.nodeScheduler = nodeScheduler;
        this.ha = ha;
        this.streamProxies = streamProxies;
        this.accessRegistry = accessRegistry;
    }

    /**
     * 实时点播（docs/03 §4.1）：锁内查活跃会话 → 复用或新建 INVITE。
     * perms 为登录用户权限集（M3-B3 阶段2: 下级平台通道需 cascade:play，docs/08 §3.2）；
     * null=内部调用（B2 上级转推）跳过级联点播校验; 回放入口阶段3 起同样透传 perms。
     */
    public PlayResult play(long channelDbId, int tcpMode, int streamProfile, Long userId, Set<String> perms) {
        return doPlay(channelDbId, tcpMode, streamProfile, userId, perms, null, null, null);
    }

    /**
     * 回放点播（docs/03 §6 / docs/07 §2）：mirror play(), 复用同一条「通道锁 → 会话创建 → INVITE」路径,
     * 差异点: ①sessionType=2 ②streamId=gb_playback_{channelId}_{HHmmss}_{rand}(每次唯一, 不参与实况复用)
     * ③回放 SSRC(首位 1) ④session 落 playbackStart/End ⑤默认 TCP 被动（UDP 收流超时仍走降级 re-INVITE）。
     *
     * @param start 回放窗口起, 必须早于 end 且跨度 ≤31 天（对齐 RecordQueryService）
     */
    public PlayResult playPlayback(Long channelDbId, Integer tcpMode, Long userId, Set<String> perms,
            LocalDateTime start, LocalDateTime end) {
        validateWindow(start, end);
        // tcpMode/streamProfile: B2-a 不暴露给前端, 缺省 TCP 被动(与实况缺省一致) + 主码流;
        // 级联通道由 doPlay 按档案 mediaTcpMode 覆盖（M3-B3 阶段3, 与实况同策略）
        return doPlay(channelDbId, tcpMode == null ? 1 : tcpMode, 1, userId, perms, start, end, null);
    }

    /** 回放窗口校验（对齐 RecordQueryService 的 31 天上限） */
    static void validateWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)
                || java.time.Duration.between(start, end).toDays() > 31) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间范围非法（起止倒置或超过31天）");
        }
    }

    /** 点播/回放共用主干: playbackStart 非空 = 回放会话（差异点见 playPlayback 注释） */
    private PlayResult doPlay(long channelDbId, int tcpMode, int streamProfile, Long userId,
            Set<String> perms, LocalDateTime playbackStart, LocalDateTime playbackEnd,
            String preferredServerId) {
        boolean playback = playbackStart != null;
        GbDeviceChannelEntity ch = channelMapper.selectById(channelDbId);
        if (ch == null || ch.getDeletedTime() != null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "通道不存在: " + channelDbId);
        }
        // 目录节点（虚拟分组/行政区划/平台根）不可点播: 对其 INVITE 只会白等超时。
        // 判定与 ChannelQueryService.markDirectories 同口径——不信对端 parental 上报,
        // 以「是其它存活通道的 parentId」为准, parental=1 兜底空目录（内部调用一并拦截）。
        if (isDirectoryChannel(ch)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目录节点不支持点播: " + ch.getChannelId());
        }
        GbDeviceEntity dev = deviceMapper.selectById(ch.getDeviceDbId());
        if (dev == null || dev.getOnline() == null || dev.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线: " + ch.getChannelId());
        }
        // M4-B1 分流点(docs/13 §3.2): ONVIF 通道点播/回放由 B2 GetStreamUri→addStreamProxy 交付前给明确错误
        // M4-B2 分流(docs/13 §5.1): ONVIF 点播=档位选 profile→GetStreamUri→addStreamProxy 拉流代理,
        // 之后即 ZLM 标准流(播放 URL/鉴权/none_reader/编码判据全复用)
        // M4-B3 分流(docs/13 §5.3): ONVIF 回放=resolveReplayUrl→addStreamProxy, 控制定级 "seek"
        boolean onvif = DeviceProtocol.of(dev) == DeviceProtocol.ONVIF;
        boolean rtsp = DeviceProtocol.of(dev) == DeviceProtocol.RTSP;
        if (ch.getStatus() != null && ch.getStatus() != 1) {
            throw new BizException(ErrorCode.CHANNEL_OFFLINE, "通道离线: " + ch.getChannelId());
        }
        // M3-B3 阶段2（docs/08 §3.2）: 下级平台(deviceType=2)通道点播/回放要求 cascade:play 独立授权
        // （与本域 device:play/record:play 分开, 支持"允许看本域、限制看下级共享"的差异化部署）;
        // perms=null=内部调用跳过。收流模式: HTTP 入口点播/回放级联通道恒取设备档案 mediaTcpMode
        // （预建档/编辑配置, 不随请求切换; 阶段3 回放同策略）;
        // perms=null 的内部调用（B2 上级转推显式 tcpMode=0 UDP 拉流）不受覆盖影响。
        boolean cascade = dev.getDeviceType() != null && dev.getDeviceType() == 2;
        if (cascade && perms != null && !perms.contains("cascade:play")) {
            throw new BizException(ErrorCode.FORBIDDEN, "点播/回放下级平台通道需要级联点播权限(cascade:play)");
        }
        if (cascade && perms != null) {
            tcpMode = cascadeTcpMode(dev);
        }
        RLock lock = redisson.getLock("gb:lock:play:" + channelDbId);
        lock.lock();
        try {
            GbMediaSessionEntity active = findActive(channelDbId, streamProfile,
                    playback ? GbMediaSessionEntity.TYPE_PLAYBACK : GbMediaSessionEntity.TYPE_LIVE);
            if (active != null) {
                if (playback && !sameWindow(active, playbackStart, playbackEnd)) {
                    // 回放不参与实况式复用(docs/07 §2.2); 同通道同时只允许一个回放会话(uk_active 同口径),
                    // 异窗口请求直接拒绝, 防止静默复用导致播的窗口与请求不符
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "该通道已有进行中的回放会话(" + active.getPlaybackStart() + " ~ " + active.getPlaybackEnd()
                                    + "), 请先停止");
                }
                sessionMapper.update(null, new LambdaUpdateWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getId, active.getId())
                        .setSql("viewer_count = viewer_count + 1"));
                return reuseResult(active);
            }
            if (onvif) {
                return playback
                        ? onvifPlaybackNew(ch, dev, userId, preferredServerId, playbackStart, playbackEnd)
                        : onvifInviteNew(ch, dev, streamProfile, userId, preferredServerId);
            }
            if (rtsp) {
                if (playback) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "拉流代理不支持回放（RTSP 源无国标录像语义）");
                }
                return rtspInviteNew(ch, dev, streamProfile, userId, preferredServerId);
            }
            return inviteNew(ch, dev, tcpMode, streamProfile, userId, playbackStart, playbackEnd,
                    preferredServerId);
            // ^ tcpMode 已在上方按级联/本域策略解析为实际收流模式（会话行与 SDP 均用它）
        } finally {
            lock.unlock();
        }
    }

    /** 级联通道（deviceType=2）收流模式: 取设备档案 mediaTcpMode, 缺省 TCP 被动。
     *  HTTP 点播/回放（perms 非空）与按需点播内部建流共用同一口径。 */
    private static int cascadeTcpMode(GbDeviceEntity dev) {
        return dev.getMediaTcpMode() != null ? dev.getMediaTcpMode() : 1;
    }

    /**
     * 目录节点判定（与 ChannelQueryService.markDirectories 同口径）:
     * parental=1, 或本设备存在以该通道编码为 parentId 的存活通道。idx_parent 索引命中。
     */
    private boolean isDirectoryChannel(GbDeviceChannelEntity ch) {
        if (ch.getParental() != null && ch.getParental() == 1) {
            return true;
        }
        Long children = channelMapper.selectCount(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getDeviceDbId, ch.getDeviceDbId())
                .eq(GbDeviceChannelEntity::getParentId, ch.getChannelId())
                .isNull(GbDeviceChannelEntity::getDeletedTime));
        return children != null && children > 0;
    }

    /**
     * 按需点播（M3-B7, docs/06 §3.1）: ZLM on_stream_not_found 触发——播放器拉的流不存在,
     * 平台在 ZLM 等待窗口（maxStreamWaitMS, 默认 15s）内把流建起来。
     *
     * <p>与 HTTP 点播的差异: ①perms=null 内部调用（无登录用户; 准入由触发侧完成——hook 验签
     * 即准入, HTTP 内部口由 HA 令牌把关） ②流须建在 preferredServerId 这个"请求节点"上
     * （播放器的挂起请求在该节点, 建别处等于没建） ③该通道已有活跃实况会话（任意码流档）时
     * 直接复用——streamId 与码流档无关, 同 streamId 双开必撞 ZLM, 故复用判定不看档位;
     * 已有流在别的节点时给请求节点补一条跨节点回源代理, 让挂起的播放器能拉到。</p>
     *
     * @param streamId          gb_play_{channelId}（实况固定值）
     * @param preferredServerId 发起 on_stream_not_found 的节点（不可用时回落常规调度）
     * @param streamProfile     新建会话的码流档（复用已有会话时不生效）
     */
    public PlayResult playOnDemand(String streamId, String preferredServerId, int streamProfile) {
        if (streamId == null || !streamId.startsWith(LIVE_STREAM_PREFIX)
                || streamId.length() == LIVE_STREAM_PREFIX.length()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非实况流: " + streamId);
        }
        String channelId = streamId.substring(LIVE_STREAM_PREFIX.length());
        GbDeviceChannelEntity ch = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getChannelId, channelId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .orderByDesc(GbDeviceChannelEntity::getId)
                .last("LIMIT 1"));
        if (ch == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "通道不存在: " + channelId);
        }
        GbMediaSessionEntity active = findActiveLive(ch.getId());
        if (active != null) {
            proxyIfNeeded(active, preferredServerId);
            log.info("按需点播: 复用已有实况会话 stream={} status={} server={} viewer={}",
                    streamId, active.getStatus(), active.getServerId(), active.getViewerCount());
            return reuseResult(active);
        }
        GbDeviceEntity dev = deviceMapper.selectById(ch.getDeviceDbId());
        int tcpMode = (dev != null && dev.getDeviceType() != null && dev.getDeviceType() == 2)
                ? cascadeTcpMode(dev) : 1;
        PlayResult r = doPlay(ch.getId(), tcpMode, streamProfile, null, null, null, null, preferredServerId);
        log.info("按需点播: 已建流 channel={} stream={} status={} server={}",
                ch.getChannelId(), r.streamId(), r.status(), r.serverId());
        return r;
    }

    /**
     * 已有流不在请求节点时补一条跨节点回源代理（M3-B7）。会话未 STREAMING 时 ensureProxy 必失败
     * （源流尚未存在）, 只告警不抛——播放器重试或流就绪后由前端 /play/{streamId}/urls 路径自愈。
     */
    private void proxyIfNeeded(GbMediaSessionEntity s, String preferredServerId) {
        if (preferredServerId == null || preferredServerId.isBlank()
                || preferredServerId.equals(s.getServerId())) {
            return;
        }
        if (!"STREAMING".equals(s.getStatus())) {
            log.warn("按需点播: 已有会话未就绪, 暂不建跨节点代理 stream={} status={} 请求节点={}",
                    s.getStreamId(), s.getStatus(), preferredServerId);
            return;
        }
        try {
            streamProxies.ensureProxy(s.getStreamId(), preferredServerId);
        } catch (Exception e) {
            log.warn("按需点播: 跨节点代理创建失败(播放器本次可能失败, 重试可自愈): stream={} 请求节点={} err={}",
                    s.getStreamId(), preferredServerId, e.getMessage());
        }
    }

    /** 通道活跃实况会话（任意码流档）——按需点播复用判定与 M3-B7 抓图找流用 */
    public GbMediaSessionEntity findActiveLive(long channelDbId) {
        List<GbMediaSessionEntity> list = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getChannelDbId, channelDbId)
                        .eq(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_LIVE)
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 回放窗口是否与既有会话一致（复用判定） */
    private static boolean sameWindow(GbMediaSessionEntity s, LocalDateTime start, LocalDateTime end) {
        return start != null && start.equals(s.getPlaybackStart()) && end != null && end.equals(s.getPlaybackEnd());
    }

    /** 停止/退订（docs/03 §4.2）：viewer 原子递减, 归零走清理三件套。
     *  F3 修复: 与 play 同一把通道锁, 条件更新保证减量不丢、双重 stop 只清理一次 */
    public void stop(String streamId, Long userId) {
        GbMediaSessionEntity s = findActiveByStream(streamId);
        if (s == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已关闭: " + streamId);
        }
        RLock lock = redisson.getLock("gb:lock:play:" + s.getChannelDbId());
        lock.lock();
        try {
            int updated = sessionMapper.update(null, new LambdaUpdateWrapper<GbMediaSessionEntity>()
                    .eq(GbMediaSessionEntity::getId, s.getId())
                    .gt(GbMediaSessionEntity::getViewerCount, 0)
                    .setSql("viewer_count = viewer_count - 1"));
            if (updated > 0) {
                GbMediaSessionEntity fresh = sessionMapper.selectById(s.getId());
                int remaining = fresh != null && fresh.getViewerCount() != null ? fresh.getViewerCount() : 0;
                if (remaining > 0) {
                    log.info("退订: stream={} 剩余观看者 {}", streamId, remaining);
                    return;
                }
            }
            // 计数归零(或已为 0) → 最后一个观看者离开 → 关流（D14）。
            // 审计: 用户态入口 PlayController/PlaybackController 的 stop 已挂 @AuditLog（AuditAspect 统一记账）;
            // 本方法另有系统态调用方（级联断链/空闲抓图, userId=null）, 故不在服务层重复记账。
            // close 布尔守卫（B2 加固）: 与 none_reader/断流等触发源竞速时, 只有首个执行者对外发事件
            if (cleanup.close(s.getId(), "CLOSED", "user_stop", "")) {
                publisher.streamClosed(streamId, "user_stop");
            }
            purgeCallIndex(s.getId());
            forgetPause(s.getId());
        } finally {
            lock.unlock();
        }
    }

    /** 回放控制（docs/07 §3）：PAUSE/RESUME/SCALE → 对话内 INFO(MANSRTSP); SEEK → 对话内 re-INVITE（新起点~原终点, 端口/SSRC/CallID 不变）。
     *  编排放本类而非独立 PlaybackService: SEEK 后需重登记 callIndex 才能在 re-INVITE 200 OK 时补 ACK（该索引为本类私有）。 */
    public void playbackControl(String streamId, String cmd, java.math.BigDecimal scale, LocalDateTime seekTime) {
        validateControl(cmd, scale, seekTime);
        GbMediaSessionEntity s = findActiveByStream(streamId);
        if (s == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已关闭: " + streamId);
        }
        if (s.getSessionType() == null || s.getSessionType() != GbMediaSessionEntity.TYPE_PLAYBACK) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非回放会话, 不支持回放控制: " + streamId);
        }
        if (!"STREAMING".equals(s.getStatus())) {
            // INVITING 中设备尚未开始推流, 控制无意义
            throw new BizException(ErrorCode.BAD_REQUEST, "会话未就绪: " + streamId);
        }
        GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
        if (dev == null || dev.getIp() == null) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备不存在或地址缺失: " + s.getDeviceDbId());
        }
        int devicePort = dev.getPort() != null ? dev.getPort() : 5060;
        // M4-B3: ONVIF 回放走拉流代理, 无 SIP 对话语义——SEEK=重建代理, 其余控制明确拒绝(§5.3 定级)
        if (DeviceProtocol.of(dev) == DeviceProtocol.ONVIF) {
            onvifPlaybackControl(s, dev, cmd, seekTime);
            return;
        }
        try {
            switch (cmd) {
                case CMD_SEEK -> seekPlayback(s, dev, devicePort, seekTime);
                case CMD_PAUSE -> pausePlayback(s, dev, devicePort);
                case CMD_RESUME -> resumePlayback(s, dev, devicePort);
                case CMD_SCALE -> scalePlayback(s, dev, devicePort, scale);
                default -> throw new BizException(ErrorCode.BAD_REQUEST, "cmd 仅支持 PAUSE/RESUME/SCALE/SEEK");
            }
        } catch (BizException e) {
            // Dialog 已终结（对端停推未发 BYE / 跨网段 UDP 丢包致 ACK 缺失被对端静默终结——
            // M5-B2 真机 LiveGBS 级联回放实证: 流还在推/会话表仍 STREAMING, 但对话已死,
            // 控制指令连环报「SIP 发送失败」）。收尾会话让前端进终态——用户的下一步是
            // 重新回放而不是重试控制。close 幂等, 对话已死时 BYE 发送失败照常收尾。
            if (isDialogTerminated(e)) {
                log.warn("回放控制发现对话已终结, 收尾会话: stream={} cmd={} ({})",
                        s.getStreamId(), cmd, e.getMessage());
                if (cleanup.close(s.getId(), "FAILED", "dialog_terminated", "回放对话已终结: " + e.getMessage())) {
                    publisher.streamClosed(s.getStreamId(), "dialog_terminated");
                }
                throw new BizException(ErrorCode.SESSION_NOT_FOUND, "回放会话已结束, 请重新开始回放");
            }
            throw e;
        }
    }

    /** jain-sip 对话终结类异常判定（发送侧对话状态检查抛出, 异常消息为 jain-sip 英文原文） */
    private static boolean isDialogTerminated(BizException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("Terminated Dialog") || msg.contains("not yet established"));
    }

    /** 发回放控制 INFO（MANSRTSP, Seq 用全局 SN） */
    private void sendPlaybackInfo(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort, String action,
            java.math.BigDecimal scale) {
        invite.sendInfo(s.getChannelId(), s.getCallId(), dev, dev.getIp(), devicePort,
                ManScdpCodec.buildMansrtspControl(SipIds.nextSn(), action, scale));
        log.info("回放控制已发送: stream={} cmd={}", s.getStreamId(), action);
    }

    // ---------- 回放控制分发（docs/07 §3; 暂停/续播与收流生命周期的关系见 PauseCtx 注释） ----------

    /** PAUSE: 位置钟冻结于暂停点后发 INFO PAUSE; 暂停态豁免各判死路径, 设备停发 RTP 由 ZLM
     *  gap 超时自关收流侧仅记 mediaDead —— 等待中的 RESUME/SEEK 决定续播方式 */
    private void pausePlayback(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort) {
        PauseCtx ctx = pauseCtxOf(s);
        synchronized (ctx) {
            advancePosLocked(ctx);
            ctx.paused = true;
            ctx.anchoredAt = LocalDateTime.now();   // 冻结锚: 位置钟停在暂停点
        }
        sendPlaybackInfo(s, dev, devicePort, "PAUSE", null);
    }

    /** RESUME: 收流已死(mediaDead 或暂停时长超 ZLM 收流超时兜底) → 重开收流端口 + re-INVITE(t=暂停点)
     *  续播; 活体 → INFO RESUME 直接续播 */
    private void resumePlayback(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort) {
        PauseCtx ctx = pauseCtx.get(s.getId());
        if (ctx == null) {
            // 从未暂停: 幂等 INFO RESUME(设备 200 或忽略), 原行为
            sendPlaybackInfo(s, dev, devicePort, "RESUME", null);
            return;
        }
        if (needReopen(ctx)) {
            reopenAndContinue(s, dev, devicePort, ctx, null);
            return;
        }
        synchronized (ctx) {
            ctx.paused = false;                     // 活体恢复
            ctx.anchoredAt = LocalDateTime.now();   // 锚点=恢复时刻, 位置钟继续推进
        }
        sendPlaybackInfo(s, dev, devicePort, "RESUME", null);
    }

    /** SCALE: 死体(暂停中且收流已被 ZLM 自关) → 按 RESUME 重开续播(设备 re-INVITE 起播大多 1x,
     *  位置钟 rate 复位 1, 前端按需再调档; 暂停中调档设备行为厂商相关入 B6);
     *  活体 → INFO SCALE + 位置钟换挡 */
    private void scalePlayback(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort,
            java.math.BigDecimal scale) {
        PauseCtx ctx = pauseCtx.get(s.getId());
        boolean reopen;
        if (ctx == null) {
            reopen = false;
        } else {
            synchronized (ctx) {
                advancePosLocked(ctx);
                reopen = ctx.paused && ctx.mediaDead;
                if (!reopen) {
                    ctx.rate = scale.doubleValue();
                    ctx.anchoredAt = LocalDateTime.now();
                }
            }
        }
        if (reopen) {
            reopenAndContinue(s, dev, devicePort, ctx, null);
            log.info("暂停超时 SCALE: 已重开续播并复位 1x(前端可再次下发 {}x): stream={}", scale, s.getStreamId());
        } else {
            sendPlaybackInfo(s, dev, devicePort, "SCALE", scale);
        }
    }

    /** SEEK: re-INVITE(t=seekTime~原止)。暂停态一并解除(设备从 seekTime 起播); 收流已死(暂停超时/
     *  死亡 hook)时先重开收流端口 —— 否则 re-INVITE 后设备重推没有落点 */
    private void seekPlayback(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort,
            LocalDateTime seekTime) {
        // SEEK 只重定位起点: t=行 = seekTime ~ 原窗口止, 不改会话 playbackStart/End（前端地址不变）
        if (s.getPlaybackStart() == null || s.getPlaybackEnd() == null
                || seekTime.isBefore(s.getPlaybackStart()) || !seekTime.isBefore(s.getPlaybackEnd())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "seekTime 超出回放窗口: " + seekTime);
        }
        PauseCtx ctx = pauseCtx.get(s.getId());
        boolean reopen = ctx != null && needReopen(ctx);
        // re-INVITE 200 OK 需补 ACK: 先登记 callIndex 再发送（B2 评审 #5: put-after-send 在 TCP 直连下
        // 存在 200 OK 先于 put 到达的竞态窗口, 会丢 ACK; callId 在流就绪时已移除, 此处重登记;
        // reopen 分支在 reopenAndContinue 内部再次登记, 幂等无害）
        callIndex.put(s.getCallId(), s.getId());
        try {
            if (reopen) {
                reopenAndContinue(s, dev, devicePort, ctx, seekTime);
            } else {
                invite.sendReInvite(s, s.getCallId(), dev, dev.getIp(), devicePort, seekTime);
                if (ctx != null) {
                    synchronized (ctx) {
                        ctx.paused = false;     // SEEK 令设备从 seekTime 起播, 暂停态解除
                        ctx.pos = seekTime;     // 位置钟跳锚
                        ctx.anchoredAt = LocalDateTime.now();
                    }
                }
                log.info("回放 SEEK 已发起: stream={} seekTime={}", s.getStreamId(), seekTime);
            }
        } catch (RuntimeException e) {
            // 发送失败（无对话 tag/节点缺失）: 撤下登记, 会话原样保留（没发出就没有 200 OK 要 ACK）
            callIndex.remove(s.getCallId());
            throw e;
        }
    }

    /**
     * 暂停超时续播重开（B2 必改 #1）: 重开 ZLM 收流端口 + 对话内 re-INVITE(t=from~原止) 让设备从
     * 续播点重推。SSRC 复用(旧 RtpProcess 已被 ZLM gap 超时自关, 无残留绑定), 端口由 ZLM 新分配。
     * 内部登记 callIndex(200 OK 补 ACK, B2 评审 #5 同法; RESUME/SCALE/SEEK 三条重开路径共用,
     * SEEK 调用前已登记, 重复 put 幂等无害)。行条件更新(STREAMING 生效)挡
     * stop/终态清理竞态; 竞态败者关掉本轮新端口后抛"会话已关闭"。成功后位置钟锚到 from、rate 复位
     * 1(re-INVITE 起播大多 1x), mediaDead 保留至续播流实际重注册(regist hook 清除)。
     */
    private void reopenAndContinue(GbMediaSessionEntity s, GbDeviceEntity dev, int devicePort,
            PauseCtx ctx, LocalDateTime from) {
        LocalDateTime resumeFrom = from != null ? from : pausePos(s, ctx);
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, s.getServerId()).last("LIMIT 1"));
        if (node == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "承载节点不存在: " + s.getServerId());
        }
        int newPort;
        // 暂停期间 ZLM RTP 收流超时死亡后, rtpServer 端口对象仍占着 streamId(未 close 前重开必
        // 报 -300 already exists, M5-B2 真机实证)——重开前先幂等关闭, 失败继续(open 再报错走原路径)
        try {
            zlm.closeRtpServer(node.getServerId(), s.getStreamId());
        } catch (Exception e) {
            log.warn("续播重开前关旧端口失败(继续重开): stream={}, {}", s.getStreamId(), e.getMessage());
        }
        try {
            newPort = zlm.openRtpServer(node.getServerId(),
                    s.getTcpMode() != null && s.getTcpMode() == 1
                            ? ZlmOpenRtpServerRequest.tcpPassive(s.getStreamId(), s.getSsrc())
                            : ZlmOpenRtpServerRequest.udp(s.getStreamId(), s.getSsrc()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INVITE_FAILED, "续播重开收流端口失败: " + e.getMessage());
        }
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getId, s.getId())
                .eq(GbMediaSessionEntity::getStatus, "STREAMING")
                .set(GbMediaSessionEntity::getRtpPort, newPort)
                .set(GbMediaSessionEntity::getErrorMsg, "暂停超时续播: 已重开收流端口"));
        if (updated == 0) {
            // 竞态败者: stop/终态清理已接管 → 关掉本轮刚开的端口(cleanup 可能已按 streamId 关过, 幂等)
            try {
                zlm.closeRtpServer(node.getServerId(), s.getStreamId());
            } catch (Exception e) {
                log.warn("续播重开竞态败者关端口失败(残留由 ZLM timeoutSec 自愈): {}", e.getMessage());
            }
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "会话已关闭: " + s.getStreamId());
        }
        GbMediaSessionEntity fresh = sessionMapper.selectById(s.getId());
        callIndex.put(fresh.getCallId(), fresh.getId());   // 发送前登记: re-INVITE 200 OK 需补 ACK(B2 #5 同法)
        try {
            invite.sendReInvite(fresh, fresh.getCallId(), dev, dev.getIp(), devicePort, resumeFrom);
        } catch (RuntimeException e) {
            // 发送失败（无对话 tag 等）: 撤下登记, 会话原样保留（没发出就没有 200 OK 要 ACK）
            callIndex.remove(fresh.getCallId());
            throw e;
        }
        synchronized (ctx) {
            ctx.paused = false;                 // 设备将按 re-INVITE 的 t= 重推
            ctx.pos = resumeFrom;
            ctx.anchoredAt = LocalDateTime.now();
            ctx.rate = 1.0;                     // re-INVITE 起播大多 1x, 位置钟对齐
            // mediaDead 保留 true 至续播流重注册, 期间会话 STREAMING 不参与判死路径
        }
        log.info("回放暂停超时续播: stream={} from={} port={}", fresh.getStreamId(), resumeFrom, newPort);
    }

    // ---------- 暂停态/位置钟工具（B2 必改 #1） ----------

    /** 取/建暂停上下文（流就绪钩子与首条控制命令兜底: 位置钟须从窗口起点起算） */
    private PauseCtx pauseCtxOf(GbMediaSessionEntity s) {
        return pauseCtx.computeIfAbsent(s.getId(), id -> {
            PauseCtx c = new PauseCtx();
            c.pos = s.getPlaybackStart() != null ? s.getPlaybackStart() : LocalDateTime.now();
            c.anchoredAt = LocalDateTime.now();
            return c;
        });
    }

    /** 位置钟当前值（暂停中即冻结的暂停点; 未暂停防御性推进后返回） */
    private LocalDateTime pausePos(GbMediaSessionEntity s, PauseCtx ctx) {
        synchronized (ctx) {
            advancePosLocked(ctx);
            return ctx.pos != null ? ctx.pos : s.getPlaybackStart();
        }
    }

    /** 位置钟推进（调用方已持 ctx 锁）: 播放中按倍速推进锚点, 暂停中冻结 */
    private void advancePosLocked(PauseCtx ctx) {
        if (ctx.paused || ctx.pos == null || ctx.anchoredAt == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long ms = java.time.Duration.between(ctx.anchoredAt, now).toMillis();
        if (ms > 0 && ctx.rate > 0) {
            ctx.pos = ctx.pos.plusNanos(Math.round(ms * ctx.rate) * 1_000_000L);
            ctx.anchoredAt = now;
        }
    }

    /** 暂停中的收流侧是否已死: 收到死亡 hook(mediaDead), 或暂停时长已超 ZLM 收流超时
     *  (death hook 失联兜底 —— 超阈值后收流侧必已被 ZLM 自关, 无需等 hook) */
    private boolean needReopen(PauseCtx ctx) {
        synchronized (ctx) {
            if (!ctx.paused) {
                return false;
            }
            if (ctx.mediaDead) {
                return true;
            }
            long pausedMs = java.time.Duration.between(ctx.anchoredAt, LocalDateTime.now()).toMillis();
            return pausedMs >= media.getReceiveTimeoutSec() * 1000L;
        }
    }

    /**
     * 暂停豁免（B2 必改 #1）: 会话暂停态收到死亡信号(ZLM 收流侧按 gap 超时自关的注销/
     * on_rtp_server_timeout) → 只记 mediaDead, 不判死不 BYE —— 否则"暂停保会话"契约在暂停
     * 超过 ZLM 收流超时后破裂。
     * @return true=已按暂停豁免处理(调用方应直接返回, 不再走判死清理)
     */
    private boolean markPauseMediaDead(long sessionId) {
        PauseCtx ctx = pauseCtx.get(sessionId);
        if (ctx == null || !ctx.paused) {
            return false;
        }
        if (!ctx.mediaDead) {
            ctx.mediaDead = true;
            log.warn("回放暂停中收到死亡信号, 豁免判死并记收流已死(待 RESUME/SEEK 重开续播): session={}", sessionId);
        }
        return true;
    }

    /** 会话是否处于暂停豁免态（对账正向判死豁免读取, 同包可见） */
    boolean isPauseProtected(long sessionId) {
        PauseCtx ctx = pauseCtx.get(sessionId);
        return ctx != null && ctx.paused;
    }

    /** 移除暂停上下文（会话终态时调用; 幂等, 未暂停会话无条目直接返回） */
    void forgetPause(long sessionId) {
        pauseCtx.remove(sessionId);
    }

    /** control 入参校验（包私有便于单测）：cmd 白名单 + SCALE 档位 + SEEK 时间必填 */
    static void validateControl(String cmd, java.math.BigDecimal scale, LocalDateTime seekTime) {
        if (cmd == null || cmd.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cmd 不能为空");
        }
        switch (cmd) {
            case CMD_PAUSE, CMD_RESUME -> {
                // 无附加参数
            }
            case CMD_SCALE -> {
                if (scale == null || VALID_SCALES.stream().noneMatch(v -> v.compareTo(scale) == 0)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "scale 仅支持 0.25/0.5/1/2/4");
                }
            }
            case CMD_SEEK -> {
                if (seekTime == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "SEEK 必须携带 seekTime");
                }
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "cmd 仅支持 PAUSE/RESUME/SCALE/SEEK");
        }
    }

    /** SIP 响应关联（INVITE 200 OK → ACK; 4xx/5xx → FAILED） */
    public void onSipResponse(SipEvent event) {
        if ("INFO".equals(event.getMethod())) {
            onPlaybackInfoResponse(event);
            return;
        }
        if (!"INVITE".equals(event.getMethod())) {
            return;
        }
        Long sessionId = callIndex.get(event.getCallId());
        if (sessionId == null) {
            return;
        }
        GbMediaSessionEntity s = sessionMapper.selectById(sessionId);
        if (s == null || SessionCleanupService.isTerminal(s.getStatus())) {
            callIndex.remove(event.getCallId());
            return;
        }
        int code = event.getStatusCode() == null ? 0 : event.getStatusCode();
        if (code == 200) {
            invite.rememberPeerTag(event.getCallId(), event.getToTag());
            // 记录已应答（重传 200 OK 仍会走到这里补发 ACK, RFC 3261 §13.2.2.4; INVITE 超时扫描据此判定已应答）
            invite.markAnswered(event.getCallId());
            GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
            // ACK CSeq 回显 200 OK 携带的 INVITE CSeq（B2 评审修复, RFC 3261 §13.2.2.4）
            invite.sendAck(s.getChannelId(), event.getCallId(), dev, dev != null ? dev.getIp() : null,
                    dev != null && dev.getPort() != null ? dev.getPort() : 5060, event.getCseq());
            metrics.increment("vision.invite", "result", "answered");
            log.info("INVITE 200 OK, ACK 已发: stream={} callId={}", s.getStreamId(), event.getCallId());
            if ("STREAMING".equals(s.getStatus())) {
                // 回放 SEEK 的 re-INVITE 200 OK: ACK 已补发, 会话早已 STREAMING, 该索引不再需要
                // （初个 INVITE 的 200 OK 到达时仍为 INVITING, 不走此分支, 照旧由流就绪时移除）
                callIndex.remove(event.getCallId());
            }
        } else if (code >= 400) {
            callIndex.remove(event.getCallId());
            if ("STREAMING".equals(s.getStatus()) && code != 481 && code != 408) {
                // 对话内 re-INVITE(SEEK) 被设备拒绝(486/488/491 等, 双击 SEEK 易触 491):
                // RFC 3261 中 re-INVITE 被拒并不终止对话, 设备多半继续播原窗口 —— 保留健康会话,
                // 仅记 SEEK 失败（B2 评审修复: 旧逻辑对任何 INVITE 失败都销毁整会话）;
                // 481(对话不存在)/408(超时) 属对话级失效, 照旧清理。
                log.warn("回放 re-INVITE 被设备拒绝, 会话保留继续播原位置: stream={} code={}",
                        s.getStreamId(), code);
                return;
            }
            // F4 修复: 清理含阻塞 IO(closeRtpServer), 移出 SIP dispatch 线程
            asyncPool.submit(() -> {
                if (cleanup.close(s.getId(), "FAILED", "invite_rejected", "设备应答 " + code)) {
                    metrics.increment("vision.invite", "result", "failed");
                    publisher.streamClosed(s.getStreamId(), "invite_rejected");
                    forgetPause(s.getId());
                }
            });
        }
    }

    /** 回放控制 INFO 应答（MANSRTSP）: 设备拒绝(非 2xx)时向前端明确告警。
     *  242 真机 LiveGBS 级联实证: 不支持 MANSRTSP 的设备回「400 info device failed, res[400]」,
     *  旧逻辑不监听 INFO 应答 → 前端静默无效, 用户反复点击无反馈。2xx=已受理静默通过;
     *  会话查不到(已清理/重启)忽略。会话定位走 callId 查库——STREAMING 后 callIndex 已移除。 */
    private void onPlaybackInfoResponse(SipEvent event) {
        int code = event.getStatusCode() == null ? 0 : event.getStatusCode();
        if (code >= 200 && code < 300) {
            return;
        }
        GbMediaSessionEntity s = sessionMapper.selectOne(new LambdaQueryWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getCallId, event.getCallId())
                .ne(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_TALK)
                .in(GbMediaSessionEntity::getStatus, ACTIVE)
                .last("LIMIT 1"));
        if (s == null) {
            return;
        }
        log.warn("回放控制被设备拒绝: stream={} code={} callId={}（设备可能不支持 MANSRTSP 对话内控制）",
                s.getStreamId(), code, event.getCallId());
        publisher.playbackControlFailed(s.getStreamId(), code);
    }

    /** 设备主动 BYE（重启/挂断, F6）: 按 callId 关闭本地会话。由 SIP dispatch 调用, 清理异步执行 */
    public void onDeviceBye(String callId) {
        if (callId == null || callId.isBlank()) {
            return;
        }
        GbMediaSessionEntity s = sessionMapper.selectOne(new LambdaQueryWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getCallId, callId)
                .ne(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_TALK)
                .in(GbMediaSessionEntity::getStatus, ACTIVE)
                .last("LIMIT 1"));
        if (s == null) {
            log.debug("设备 BYE 无对应活跃会话: callId={}", callId);
            return;
        }
        callIndex.remove(callId);
        asyncPool.submit(() -> {
            // close 幂等: BYE 引发的 ZLM unregister hook 可能竞速再触一次, 后者返回 false 不再覆盖 stopReason/重复发事件
            if (cleanup.close(s.getId(), "CLOSED", "device_bye", "设备主动结束")) {
                publisher.streamClosed(s.getStreamId(), "device_bye");
            }
            purgeCallIndex(s.getId());
            forgetPause(s.getId());
        });
    }

    /** Hook: on_stream_changed（docs/04 §1）。videoCodec: 视频编码规范化名（H264/H265, 空=未知, B5） */
    public void onStreamChanged(String streamId, boolean regist, String videoCodec) {
        GbMediaSessionEntity s = findActiveByStream(streamId);
        if (s == null) {
            return;
        }
        if (s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_TALK) {
            // M3-B5: 对讲设备腿正常不产流, 音频源腿在 app=talk 不匹配此查询;
            // 对讲会话状态由 TalkSessionService 独立驱动, 点播语义(状态流转/断流判死/STREAM_READY)不适用
            return;
        }
        if (regist) {
            if (!"INVITING".equals(s.getStatus())) {
                // STREAMING 会话再次注册: 仅"暂停超时续播重开"场景(重开收流→续播流重注册), 清死标
                PauseCtx ctx = pauseCtx.get(s.getId());
                if (ctx != null && ctx.mediaDead) {
                    ctx.mediaDead = false;
                    log.info("暂停续播流已重注册: stream={}", streamId);
                }
                // B5: 后续 schema 注册(hook 带 tracks)常晚于首条注册——编码识别完成的迟到信号。
                // 去重补投 STREAM_READY: inviting 态前端幂等刷新, playing 态前端据此补做 HEVC 网关
                republishCodec(s, videoCodec == null ? "" : videoCodec);
                return;
            }
            GbMediaSessionEntity up = new GbMediaSessionEntity();
            up.setId(s.getId());
            up.setStatus("STREAMING");
            up.setStartTime(LocalDateTime.now());
            if (s.getViewerCount() == null || s.getViewerCount() < 1) {
                up.setViewerCount(1);
            }
            sessionMapper.updateById(up);
            purgeCallIndex(s.getId());
            // B5: 首条注册的 hook body 常 0~3s 无 tracks(ZLM 编码未识别完)。串行 hook 池内不做
            // 阻塞重试: 立即按"未知"下发 STREAM_READY, 异步池短重试 getMediaList, 成功且会话仍
            // STREAMING 时走与迟到补投相同的去重发布(前端已兼容"未知→迟到编码")
            String codec = videoCodec == null ? "" : videoCodec;
            if (!codec.isEmpty()) {
                publishedCodec.put(s.getId(), codec);
            }
            publisher.streamReady(streamId, s.getChannelId(),
                    urlService.buildUrls(s.getServerId(), streamId, 3600), codec);
            log.info("流就绪: stream={} -> STREAMING videoCodec={}", streamId, codec);
            if (codec.isEmpty()) {
                String sid = s.getServerId();
                codecRetryExecutor.execute(() -> retryCodecAndRepublish(sid, streamId));
            }
            if (s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_PLAYBACK) {
                pauseCtxOf(s);   // 回放位置钟从窗口起点起算(pausePlayback 也会兜底建)
            }
        } else {
            // M4-B3: ONVIF 回放 SEEK 重建代理期间的注销豁免（Redis 标记跨实例可见——hook 判死
            // 可能落备实例; 注册路径不受影响, 新代理注册即补投 STREAM_READY）
            if (isSeeking(streamId)) {
                log.info("SEEK 重建窗口内注销豁免: stream={}", streamId);
                return;
            }
            // HA 门禁(M3-B6 阶段2): 备台无 pauseCtx, 流注销判死会误杀主台暂停中的回放;
            // 注销判死交主台(对账兜底), 注册/STREAM_READY 豁免路径不受限
            if (!ha.sipGatesOpen()) {
                log.debug("HA 门禁: 备台跳过流注销判死: stream={}", streamId);
                return;
            }
            // 暂停豁免(B2 必改 #1): 暂停中 ZLM 收流侧按 gap 超时自关触发的注销只记死标, 不判死
            if (markPauseMediaDead(s.getId())) {
                return;
            }
            // close 幂等守卫: 平台自身清理(device BYE/user_stop 等)触发的 unregister 竞速到达时,
            // 后者返回 false, 不覆盖首个执行者的 stopReason 也不重复发 stream_lost 事件
            if (cleanup.close(s.getId(), "CLOSED", "stream_lost", "设备断流")) {
                publisher.streamClosed(streamId, "stream_lost");
            }
            purgeCallIndex(s.getId());
            forgetPause(s.getId());
        }
    }

    /** Hook: on_stream_none_reader 自动关流（D17 兜底层）。
     *  暂停态不豁免此路径: none_reader = 播放端已全部断开, 无人可 RESUME —— 与 ZLM 语义一致
     *  (暂停保会话的前提是播放端保持连接; docs/07 §3 注) */
    public void onNoneReader(String streamId) {
        GbMediaSessionEntity s = findActiveByStream(streamId);
        if (s == null) {
            return;
        }
        // M4-B3: SEEK 重建窗口内旧流无观众触发的 none_reader 豁免——重建完成后由前端
        // 实际观看状态决定去留; 窗口内恰逢用户关闭页面的场景由 stop 接口兜底关停。
        // （极端泄漏面: 浏览器崩溃在 SEEK 瞬间, 会话多活一个 TTL 周期, 不破坏正确性）
        if (isSeeking(streamId)) {
            log.info("SEEK 重建窗口内 none_reader 豁免: stream={}", streamId);
            return;
        }
        // M5-B3 关前复核: none_reader 是 ZLM 侧"连续 N 秒 0 读者"计时触发, 而 hook 在本平台是
        // 异步池投递——钩子到达时读者可能已回来（前端 3s 轮询起播/WS 断线重连/软解 WASM 冷加载
        // 都落在计时窗外; 流已注册但浏览器还没 attach 是最常见形态）。误杀代价是设备侧重 INVITE
        // (观众数秒黑屏), 故关前再查一次实时读者数: 有读者 → 本次不判死, 会话交正常 stop/对账路径。
        // 复核拿不到数据(节点瞬时不可达/超时/旧版无该字段)不豁免, 判死权照旧。
        // 残留面: 跳过本次后若播放端硬崩溃（无 stop 信令）且 ZLM 不再重发钩子, 会滞留一条
        // 无观众会话——此时流侧无观众、不占端口, 由设备离线/反向对账兜底。
        Integer readers = liveReaderCount(s);
        if (readers != null && readers > 0) {
            log.info("none_reader 关前复核有实时读者, 本次不判死: stream={} session={} readers={}",
                    streamId, s.getId(), readers);
            return;
        }
        if (cleanup.close(s.getId(), "CLOSED", "none_reader", "")) {
            publisher.streamClosed(streamId, "none_reader");
        }
        purgeCallIndex(s.getId());
        forgetPause(s.getId());
    }

    /**
     * none_reader 关前复核辅助: 会话所属节点上该流的实时读者数。
     * 与 SessionReconcileTask 同口径取 listMedia 的 totalReaderCount（旧版 ZLM 该字段可能为 null）。
     * @return null=查不到/异常（调用方按"无信息"处理, 照常判死）
     */
    private Integer liveReaderCount(GbMediaSessionEntity s) {
        if (s.getServerId() == null || s.getStreamId() == null) {
            return null;
        }
        try {
            return zlm.listMedia(s.getServerId()).stream()
                    .filter(m -> s.getStreamId().equals(m.stream()))
                    .map(ZlmMediaInfo::totalReaderCount)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("none_reader 关前读者复核失败(照常判死): stream={} err={}",
                    s.getStreamId(), e.getMessage());
            return null;
        }
    }

    /** Hook: on_rtp_server_timeout（openRtpServer 后无流/收流超时）。
     *  UDP 降级分流: 仅 INVITING + UDP + 开关开启的会话降级 TCP 被动 re-INVITE（docs/06 §6）, 其余失败关停 */
    public void onRtpServerTimeout(String streamId, String ssrc) {
        GbMediaSessionEntity s = findActiveByStream(streamId);
        if (s == null) {
            return;
        }
        // HA 门禁(M3-B6 阶段2): hooks 常驻落备台, 而暂停/INVITE 进程态在本会话所属实例——
        // 备台无 pauseCtx, 按收流超时判死会误杀主台暂停中的回放; 判死权收归主台(对账兜底)
        if (!ha.sipGatesOpen()) {
            log.debug("HA 门禁: 备台跳过收流超时判死: stream={}", streamId);
            return;
        }
        if (s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_TALK) {
            // M3-B5: 对讲设备腿无音频回传时 on_rtp_server_timeout 属预期, 不降级不判死
            return;
        }
        // 暂停豁免(B2 必改 #1): 暂停超 ZLM 收流超时后的 on_rtp_server_timeout 只记收流已死, 不判死
        if (markPauseMediaDead(s.getId())) {
            return;
        }
        if (shouldFallbackToTcp(s.getStatus(), s.getTcpMode(), media.isUdpFallbackEnabled())) {
            // BYE/ZLM API 是阻塞 IO, 移出 hook 串行线程（同 F4 原则）
            asyncPool.submit(() -> {
                try {
                    fallbackToTcp(s.getId());
                } catch (Exception e) {
                    log.error("UDP 降级异常, 按收流超时关停: stream={}", streamId, e);
                    if (cleanup.close(s.getId(), "FAILED", "no_stream", "UDP 降级失败: " + e.getMessage())) {
                        publisher.streamClosed(streamId, "no_stream");
                    }
                    purgeCallIndex(s.getId());
                    forgetPause(s.getId());
                }
            });
            return;
        }
        if (cleanup.close(s.getId(), "FAILED", "no_stream", "收流超时")) {
            publisher.streamClosed(streamId, "no_stream");
        }
        purgeCallIndex(s.getId());
        forgetPause(s.getId());
    }

    /** 降级判定（包私有便于单测）：INVITING + UDP(tcpMode=0) + 开关开启 */
    static boolean shouldFallbackToTcp(String status, Integer tcpMode, boolean enabled) {
        return enabled && "INVITING".equals(status) && tcpMode != null && tcpMode == 0;
    }

    /**
     * UDP → TCP 被动降级 re-INVITE（docs/06 §6）：全程持 play 通道锁（与 play/stop 同一把）, 防
     * 并发/重复 on_rtp_server_timeout 双开端口/误关对方端口; 锁内重读并复检降级条件（锁外/提交任务
     * 时的判定可能已过期, 此时尚未动任何资源）。
     * 流程: BYE 旧对话 → 关旧 UDP 端口 + 释放旧 SSRC → 新 SSRC + TCP 被动端口 →
     * 条件更新会话行(仍 INVITING+UDP 才生效; 用户 stop/10s 扫描竞态安全) → 新对话 re-INVITE。
     * 条件更新失败(竞态败者)只释放本轮新申请的 SSRC——不按 streamId 关端口: 按 streamId 关会误伤
     * 赢者/已被他人清理的会话, 残留端口由 ZLM timeoutSec 自愈。
     * 步骤 3 抛异常(无 SSRC/端口/ZLM 失败)由调用方兜底按 no_stream 关停。
     */
    private void fallbackToTcp(long sessionId) {
        // 锁键需要 channelDbId, 先做一次锁外预读（仅为取锁键; 真正的复检在锁内）
        GbMediaSessionEntity pre = sessionMapper.selectById(sessionId);
        if (pre == null) {
            return;
        }
        RLock lock = redisson.getLock("gb:lock:play:" + pre.getChannelDbId());
        lock.lock();
        try {
            // 锁内重读 + 复检: 并发/重复触发或已被 stop/扫描关停时, 在动任何资源前直接退出
            GbMediaSessionEntity s = sessionMapper.selectById(sessionId);
            if (s == null || SessionCleanupService.isTerminal(s.getStatus())
                    || !shouldFallbackToTcp(s.getStatus(), s.getTcpMode(), media.isUdpFallbackEnabled())) {
                return;
            }
            doFallbackToTcp(s);
        } finally {
            lock.unlock();
        }
    }

    /** 降级本体（调用方已持通道锁并完成复检） */
    private void doFallbackToTcp(GbMediaSessionEntity s) {
        long sessionId = s.getId();
        // 1. 旧对话 BYE（设备已应答 200 OK 对话已建立; 发送失败继续降级）
        if (s.getCallId() != null && !s.getCallId().isBlank()) {
            try {
                GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
                if (dev != null && dev.getIp() != null) {
                    invite.sendBye(s.getChannelId(), s.getCallId(), dev, dev.getIp(),
                            dev.getPort() != null ? dev.getPort() : 5060);
                }
            } catch (Exception e) {
                log.warn("降级 BYE 发送失败(继续): session={}, {}", sessionId, e.getMessage());
            }
            invite.forgetTags(s.getCallId());
            callIndex.remove(s.getCallId());
        }
        // 2. 关旧 UDP 端口 + 释放旧 SSRC
        try {
            zlm.closeRtpServer(s.getServerId(), s.getStreamId());
        } catch (Exception e) {
            log.warn("降级关旧 RTP 端口失败(继续): session={}, {}", sessionId, e.getMessage());
        }
        if (s.getSsrc() != null) {
            ssrcPool.release(s.getServerId(), s.getSsrc());
        }
        // 3. 新 SSRC + TCP 被动端口（可能抛 BizException → 调用方兜底关停）; SSRC 号段按会话类型分流(回放首位 1)
        String newSsrc = s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_PLAYBACK
                ? ssrcPool.allocPlayback(s.getServerId(), "playback:" + s.getChannelDbId())
                : ssrcPool.allocLive(s.getServerId(), "play:" + s.getChannelDbId());
        int newPort = zlm.openRtpServer(s.getServerId(),
                ZlmOpenRtpServerRequest.tcpPassive(s.getStreamId(), newSsrc));
        // 4. 条件更新(乐观守卫): 仅当仍是 INVITING+UDP 才生效
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getId, s.getId())
                .eq(GbMediaSessionEntity::getStatus, "INVITING")
                .eq(GbMediaSessionEntity::getTcpMode, 0)
                .set(GbMediaSessionEntity::getTcpMode, 1)
                .set(GbMediaSessionEntity::getSsrc, newSsrc)
                .set(GbMediaSessionEntity::getRtpPort, newPort)
                .set(GbMediaSessionEntity::getInviteTime, LocalDateTime.now())
                .set(GbMediaSessionEntity::getErrorMsg, "UDP 收流超时, 自动降级 TCP"));
        if (updated == 0) {
            // 竞态败者: 会话已被 stop/扫描/并发降级接管 → 只释放本轮新申请的 SSRC。
            // 不按 streamId 关端口: 那会误伤赢者(或已被他人清理的会话), 残留端口由 ZLM timeoutSec 自愈
            ssrcPool.release(s.getServerId(), newSsrc);
            return;
        }
        // 5. 新对话 re-INVITE（TCP SDP: 新 callId/新 tag, 旧对话已 BYE）
        GbMediaSessionEntity fresh = sessionMapper.selectById(s.getId());
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, s.getServerId()).last("LIMIT 1"));
        GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
        if (fresh == null || node == null || dev == null || dev.getIp() == null) {
            throw new BizException(ErrorCode.INVITE_FAILED, "降级 re-INVITE 前置数据缺失(节点/设备)");
        }
        var handle = invite.sendInvite(fresh, node, dev, dev.getIp(),
                dev.getPort() != null ? dev.getPort() : 5060);
        GbMediaSessionEntity upCallId = new GbMediaSessionEntity();
        upCallId.setId(s.getId());
        upCallId.setCallId(handle.callId());
        sessionMapper.updateById(upCallId);
        callIndex.put(handle.callId(), s.getId());
        publisher.streamFallback(s.getStreamId(), s.getChannelId());
        // 降级 re-INVITE 成功收口（docs/06 §6）: 单独计数, 观测 UDP 收流质量
        metrics.increment("vision.invite", "result", "fallback");
        log.info("UDP 降级 TCP 已重新 INVITE: stream={} port={} ssrc={} callId={}",
                s.getStreamId(), newPort, newSsrc, handle.callId());
    }

    /** INVITE 超时扫描（docs/06 §6）: 无最终响应 10s CANCEL; 已应答 200 但流未到 → answeredInviteMaxSeconds(60s) 兜底关停,
     *  正常由 ZLM on_rtp_server_timeout → 降级/失败处理, 兜底仅防 hook 失联时 INVITING 会话泄漏。
     *  HA 备台身份停跑: answered 判定走本进程 answeredCallIds 内存态, 备台跑会把主台刚应答的 INVITING 误 CANCEL。 */
    @Scheduled(fixedDelay = 1_000)
    public void inviteTimeoutScan() {
        if (!ha.sipGatesOpen()) {
            return;
        }
        List<GbMediaSessionEntity> inviting = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getStatus, "INVITING")
                        .lt(GbMediaSessionEntity::getInviteTime, LocalDateTime.now().minusSeconds(10)));
        for (GbMediaSessionEntity s : inviting) {
            boolean answered = s.getCallId() != null && invite.isAnswered(s.getCallId());
            long ageSec = java.time.Duration.between(s.getInviteTime(), LocalDateTime.now()).getSeconds();
            if (!answered) {
                // 10s 无最终响应 → CANCEL + CANCELLED(invite_timeout)（原逻辑）
                if (s.getCallId() != null) {
                    GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
                    String sid = transport.stackIdForTransport(dev != null ? dev.getTransport() : null);
                    transport.cancel(sid != null ? sid : platform.getStackId(), s.getCallId());
                }
                purgeCallIndex(s.getId());
                if (cleanup.close(s.getId(), "CANCELLED", "invite_timeout", "INVITE 10s 无最终响应")) {
                    publisher.streamClosed(s.getStreamId(), "invite_timeout");
                }
            } else if (ageSec >= media.getAnsweredInviteMaxSeconds()) {
                // 已应答 200 但流一直未到: 正常由 ZLM 15s rtp timeout → 降级/FAILED, 此兜底防 hook 失联泄漏
                purgeCallIndex(s.getId());
                if (cleanup.close(s.getId(), "CANCELLED", "invite_timeout",
                        "已应答但流未到达, " + media.getAnsweredInviteMaxSeconds() + "s 兜底关停")) {
                    publisher.streamClosed(s.getStreamId(), "invite_timeout");
                }
            }
        }
    }


    /** 会话列表（运维查询, docs/03 §4.3; 不限数据权限） */
    public List<Map<String, Object>> listSessions(String status) {
        return listSessions(status, null);
    }

    /**
     * 会话列表（B3 P1 数据权限）: filter 为 null 或 all=true 全量; 受限用户仅见自己 scope 内通道的会话。
     * 响应结构不变（仍不含 channelDbId, 仅用于 SQL 收口）。
     */
    public List<Map<String, Object>> listSessions(String status,
            com.yujian.vision.service.device.service.ChannelDataScopeService.ScopeFilter filter) {
        LambdaQueryWrapper<GbMediaSessionEntity> qw = new LambdaQueryWrapper<GbMediaSessionEntity>()
                .orderByDesc(GbMediaSessionEntity::getId)
                // 观测上限可配（vision.media.sessions-limit, 默认 100; B6 压测口径挂账参数化）, 下限 1 防 0/负值翻车
                .last("LIMIT " + Math.max(1, media.getSessionsLimit()));
        if (status != null && !status.isBlank()) {
            qw.eq(GbMediaSessionEntity::getStatus, status);
        }
        if (filter != null && !filter.all()) {
            if (filter.ids().isEmpty()) {
                return List.of();
            }
            qw.in(GbMediaSessionEntity::getChannelDbId, filter.ids());
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (GbMediaSessionEntity s : sessionMapper.selectList(qw)) {
            rows.add(Map.of("sessionId", s.getId(), "streamId", s.getStreamId(), "channelId", s.getChannelId(),
                    "serverId", s.getServerId() == null ? "" : s.getServerId(),
                    "status", s.getStatus(), "viewerCount", s.getViewerCount() == null ? 0 : s.getViewerCount(),
                    "ssrc", s.getSsrc() == null ? "" : s.getSsrc(), "stopReason", s.getStopReason() == null ? "" : s.getStopReason()));
        }
        return rows;
    }

    /** 活跃流判断（on_publish Hook 裁决用） */
    public boolean hasActiveStream(String streamId) {
        return findActiveByStream(streamId) != null;
    }

    /**
     * 按主键取会话状态（M3-B2 挂账清偿）: 内部轮询（级联转推 awaitStreaming 等）按 sessionId
     * 精确单查, 不再走 listSessions 观测窗口近似（窗口外会话未命中会误判未就绪）。
     * 返回 null = 会话不存在（未落库/已清理）。
     */
    public String getSessionStatus(long sessionId) {
        GbMediaSessionEntity s = sessionMapper.selectById(sessionId);
        return s != null ? s.getStatus() : null;
    }

    // ---------------------------------------------------------------- 私有

    /** callIndex 收敛（会话终态/流就绪后调用）: 移除该会话登记的全部 callId——终态后到达的
     *  200 OK/响应重传不应再关联已清理会话, 流就绪后无需再对初个 INVITE 补 ACK */
    private void purgeCallIndex(Long sessionId) {
        callIndex.values().removeIf(id -> id.equals(sessionId));
        // B5: 迟到补投去重条目与会话索引同生命周期（除对账任务直关外, 终态路径均经此处）
        publishedCodec.remove(sessionId);
    }

    /** 会话创建半途失败: 释放 SSRC + 关闭已开的 RTP 端口（F5） */
    private void releasePartial(String serverId, GbMediaSessionEntity s) {
        try {
            if (s.getStreamId() != null && s.getRtpPort() != null && s.getRtpPort() > 0) {
                zlm.closeRtpServer(serverId, s.getStreamId());
            }
        } catch (Exception e) {
            log.warn("半途失败关端口失败(等待 ZLM timeoutSec 自愈): {}", e.getMessage());
        }
        if (s.getSsrc() != null) {
            ssrcPool.release(serverId, s.getSsrc());
        }
    }

    /** 通道活跃会话（按会话类型分流: 实况与回放互不复用, docs/07 §2.2） */
    private GbMediaSessionEntity findActive(long channelDbId, int streamProfile, int sessionType) {
        List<GbMediaSessionEntity> list = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getChannelDbId, channelDbId)
                        .eq(GbMediaSessionEntity::getStreamProfile, streamProfile)
                        .eq(GbMediaSessionEntity::getSessionType, sessionType)
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private GbMediaSessionEntity findActiveByStream(String streamId) {
        List<GbMediaSessionEntity> list = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getStreamId, streamId)
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * streamId → 通道 dbId（B3 数据权限拦截用, 回放 control 端点凭 streamId 定位所在通道）。
     * 无活跃会话返回 null, 调用方放行交给业务层抛 1007（不越权泄露会话存在性）。
     */
    public Long channelDbIdOfStream(String streamId) {
        GbMediaSessionEntity s = findActiveByStream(streamId);
        return s == null ? null : s.getChannelDbId();
    }

    /** 新建会话: 选节点(失败换下一候选) → SSRC → openRtpServer → 落库(INVITING) → INVITE。playbackStart 非空 = 回放会话 */
    private PlayResult inviteNew(GbDeviceChannelEntity ch, GbDeviceEntity dev,
            int tcpMode, int streamProfile, Long userId,
            LocalDateTime playbackStart, LocalDateTime playbackEnd, String preferredServerId) {
        boolean playback = playbackStart != null;
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setSessionType(playback ? GbMediaSessionEntity.TYPE_PLAYBACK : GbMediaSessionEntity.TYPE_LIVE);
        s.setStreamId(playback ? newPlaybackStreamId(ch.getChannelId())
                : LIVE_STREAM_PREFIX + ch.getChannelId());
        s.setApp("rtp");
        s.setDeviceDbId(dev.getId());
        s.setChannelDbId(ch.getId());
        s.setDeviceId(dev.getDeviceId());
        s.setChannelId(ch.getChannelId());
        s.setTcpMode(tcpMode);
        s.setStreamProfile(streamProfile);
        s.setUserId(userId);
        s.setStatus("INVITING");
        s.setViewerCount(1);
        s.setInviteTime(LocalDateTime.now());
        if (playback) {
            s.setPlaybackStart(playbackStart);
            s.setPlaybackEnd(playbackEnd);
        }
        // M3-B6 阶段2 挂账①: 建流失败换下一候选节点重试（invite-node-retries, 默认 1; 0=历史行为）。
        // 仅 openRtpServer/SSRC 分配的 ZLM_API_FAILED/SSRC_ALLOC_FAILED 可重试——sendInvite 失败
        // （设备侧）与 insert 的 DB 冲突（通道已有活跃会话）换节点无意义, 不重试。
        int maxRetries = Math.max(0, media.getInviteNodeRetries());
        Set<String> failedNodes = new java.util.HashSet<>();
        GbMediaServerEntity node;
        boolean inserted = false;
        // do-while: 至少尝试一轮（select 无候选抛 BizException, 不返回 null, 循环后 node 必已赋值）
        do {
            // 按需点播（M3-B7）: 优先建在发起 hook 的节点上（preferredServerId, 其余入口传 null）;
            // 该节点已在 failedNodes 时自动回落常规调度（select 内部处理）
            node = nodeScheduler.select(preferredServerId, failedNodes);
            s.setServerId(node.getServerId());
            try {
                s.setSsrc(playback
                        // 回放 SSRC 首位固定 1（docs/07 §2.1）
                        ? ssrcPool.allocPlayback(node.getServerId(), "playback:" + ch.getId())
                        : ssrcPool.allocLive(node.getServerId(), "play:" + ch.getId()));
                s.setRtpPort(zlm.openRtpServer(node.getServerId(),
                        tcpMode == 1 ? ZlmOpenRtpServerRequest.tcpPassive(s.getStreamId(), s.getSsrc())
                                : ZlmOpenRtpServerRequest.udp(s.getStreamId(), s.getSsrc())));
                sessionMapper.insert(s);
                inserted = true;
            } catch (BizException e) {
                releasePartial(node.getServerId(), s);
                boolean retryable = e.getErrorCode() == ErrorCode.ZLM_API_FAILED
                        || e.getErrorCode() == ErrorCode.SSRC_ALLOC_FAILED;
                if (retryable && failedNodes.size() < maxRetries) {
                    failedNodes.add(node.getServerId());
                    metrics.increment("vision.invite", "result", "node-retry");
                    log.warn("节点 {} 建流失败({}), 换下一候选节点重试: stream={} 已败节点={}",
                            node.getServerId(), e.getMessage(), s.getStreamId(), failedNodes);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                // F5 修复: uk_active 唯一键冲突等非 BizException 也要释放资源, 并转 1003 语义
                releasePartial(node.getServerId(), s);
                throw new BizException(ErrorCode.INVITE_FAILED, "会话创建失败: " + e.getMessage());
            }
        } while (!inserted);
        // 点播/回放共用此处发 INVITE（docs/06 §2）: 发送前置 sent, 成功率按 answered/failed/fallback 分母核算
        metrics.increment("vision.invite", "result", "sent");
        var handle = invite.sendInvite(s, node, dev, dev.getIp(),
                dev.getPort() != null ? dev.getPort() : 5060);
        s.setCallId(handle.callId());
        sessionMapper.updateById(s);
        callIndex.put(handle.callId(), s.getId());
        log.info("{}已发起: channel={} stream={} ssrc={} port={} callId={} 窗口[{} ~ {}]",
                playback ? "回放" : "点播", ch.getChannelId(), s.getStreamId(), s.getSsrc(), s.getRtpPort(),
                handle.callId(), playbackStart, playbackEnd);
        return new PlayResult(s.getId(), s.getStreamId(), "INVITING",
                urlService.buildUrls(node.getServerId(), s.getStreamId(), 3600), "", node.getServerId(),
                playback ? "sip" : "");
    }

    /**
     * ONVIF 点播建流（M4-B2, docs/13 §5.1）: 档位→GetStreamUri→addStreamProxy 拉流代理,
     * 之后即 ZLM 标准流（streamId/app 与 GB 同规范 → 播放 URL/鉴权/none_reader/编码判据全复用）。
     * 会话无 SIP 语义: ssrc/callId 恒空, STREAMING 由 on_stream_changed(regist) 驱动,
     * 拉流失败由 inviteTimeoutScan 10s 兜底关停; 代理键登记源节点, 会话清理随之 delStreamProxy。
     */
    private PlayResult onvifInviteNew(GbDeviceChannelEntity ch, GbDeviceEntity dev,
            int streamProfile, Long userId, String preferredServerId) {
        // 取流地址是设备 SOAP 外呼: 失败直接抛, 不落半截会话行
        String pullUrl = accessRegistry.of(DeviceProtocol.ONVIF)
                .resolveStreamUrl(dev, ch, streamProfile);
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setSessionType(GbMediaSessionEntity.TYPE_LIVE);
        s.setStreamId(LIVE_STREAM_PREFIX + ch.getChannelId());
        s.setApp("rtp");
        s.setDeviceDbId(dev.getId());
        s.setChannelDbId(ch.getId());
        s.setDeviceId(dev.getDeviceId());
        s.setChannelId(ch.getChannelId());
        s.setTcpMode(1);
        s.setStreamProfile(streamProfile);
        s.setUserId(userId);
        s.setStatus("INVITING");
        s.setViewerCount(1);
        s.setSsrc("");
        s.setInviteTime(LocalDateTime.now());
        GbMediaServerEntity node = createProxySession(s, ZlmAddStreamProxyRequest.forOnvifPull("rtp", s.getStreamId(), pullUrl), preferredServerId);
        // M4-B2: 短轮询等待 ZLM 拉流注册(on_stream_changed hook 落哪个实例处理不确定,
        // 双实例共库下本实例轮询 DB 均可见)。可达 RTSP 源建流毫秒级, 正常 <1s;
        // 等到即以 STREAMING 应答 —— 与 GB 路径「响应即流就绪」对齐, 前端起播不再依赖
        // WS STREAM_READY 推送(事件不持久化, 登录瞬间/断线窗口发出即丢, 242 实测
        // 曾因此 15s 起播超时)。超时按原样返回 INVITING, 退化为事件驱动, 向后兼容。
        boolean ready = awaitStreaming(s.getStreamId());
        log.info("ONVIF 点播已发起: channel={} stream={} profile={} node={} status={}",
                ch.getChannelId(), s.getStreamId(), streamProfile, node.getServerId(),
                ready ? "STREAMING" : "INVITING");
        return new PlayResult(s.getId(), s.getStreamId(), ready ? "STREAMING" : "INVITING",
                urlService.buildUrls(node.getServerId(), s.getStreamId(), 3600),
                ready ? publishedCodec.getOrDefault(s.getId(), "") : "", node.getServerId());
    }
    /**
     * 拉流代理建流公共段（ONVIF 点播/回放与 RTSP 拉流代理共用, M5-B1 起参数化代理请求）:
     * 节点重试循环——insert + addStreamProxy + 失败补偿 + 换节点重试（清自增 id 防二次 insert
     * 主键冲突, 242 实测 Duplicate entry 教训）。返回承载节点。
     */
    private GbMediaServerEntity createProxySession(GbMediaSessionEntity s,
            ZlmAddStreamProxyRequest proxyReq, String preferredServerId) {
        int maxRetries = Math.max(0, media.getInviteNodeRetries());
        Set<String> failedNodes = new java.util.HashSet<>();
        GbMediaServerEntity node;
        boolean inserted = false;
        do {
            node = nodeScheduler.select(preferredServerId, failedNodes);
            s.setServerId(node.getServerId());
            try {
                sessionMapper.insert(s);
                streamProxies.registerSource(s.getStreamId(), node.getServerId(),
                        zlm.addStreamProxy(node.getServerId(), proxyReq));
                inserted = true;
            } catch (BizException e) {
                if (s.getId() != null) {
                    cleanup.close(s.getId(), "FAILED", "no_stream", "拉流代理建立失败: " + e.getMessage());
                }
                boolean retryable = e.getErrorCode() == ErrorCode.ZLM_API_FAILED;
                if (retryable && failedNodes.size() < maxRetries) {
                    failedNodes.add(node.getServerId());
                    s.setId(null);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (s.getId() != null) {
                    cleanup.close(s.getId(), "FAILED", "no_stream", "会话创建失败: " + e.getMessage());
                }
                throw new BizException(ErrorCode.INVITE_FAILED, "会话创建失败: " + e.getMessage());
            }
        } while (!inserted);
        return node;
    }

    /**
     * RTSP 拉流代理点播建流（M5-B1, docs/14 §5.2）: 解密档案 source_url → addStreamProxy 拉流,
     * 建流公共段复用 createProxySession（insert INVITING + addStreamProxy + 失败补偿 + 换节点重试）。
     * 与 ONVIF 路径差异: ①拉流地址=密文解密, 无设备外呼 ②rtp_type 按档案 rtsp_transport 映射
     * ③常驻复用——always_on 设备常驻索引命中且流活跃时建会话直接置 STREAMING（流已 regist,
     * 不会再有 hook 到来, 主动置位并即时返回）。会话无 SIP 语义（ssrc/callId 恒空）,
     * 拉流失败由 inviteTimeoutScan 10s 兜底。
     */
    private PlayResult rtspInviteNew(GbDeviceChannelEntity ch, GbDeviceEntity dev,
            int streamProfile, Long userId, String preferredServerId) {
        String streamId = LIVE_STREAM_PREFIX + ch.getChannelId();
        if (dev.getAlwaysOn() != null && dev.getAlwaysOn() == 1) {
            for (Map.Entry<String, String> e : streamProxies.alwaysOnEntries(streamId).entrySet()) {
                if (streamProxies.streamActiveOn(e.getKey(), streamId)) {
                    return alwaysOnReuse(ch, dev, streamProfile, userId, e.getKey());
                }
                // 死 key（ZLM 侧流已消失, 收敛循环未及清理）: 移除条目走新建路径
                streamProxies.removeAlwaysOn(streamId, e.getKey());
            }
        }
        // 取流地址=档案密文解密(接入面 SPI, 无设备外呼; 失败直接抛, 不落半截会话行)
        String pullUrl = accessRegistry.of(DeviceProtocol.RTSP).resolveStreamUrl(dev, ch, streamProfile);
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setSessionType(GbMediaSessionEntity.TYPE_LIVE);
        s.setStreamId(streamId);
        s.setApp("rtp");
        s.setDeviceDbId(dev.getId());
        s.setChannelDbId(ch.getId());
        s.setDeviceId(dev.getDeviceId());
        s.setChannelId(ch.getChannelId());
        s.setTcpMode(1);
        s.setStreamProfile(streamProfile);
        s.setUserId(userId);
        s.setStatus("INVITING");
        s.setViewerCount(1);
        s.setSsrc("");
        s.setInviteTime(LocalDateTime.now());
        var proxyReq = ZlmAddStreamProxyRequest.forRtspPull("rtp", streamId, pullUrl, dev.getRtspTransport());
        GbMediaServerEntity node = createProxySession(s, proxyReq, preferredServerId);
        boolean ready = awaitStreaming(streamId);
        log.info("RTSP 拉流代理点播已发起: channel={} stream={} transport={} node={} status={}",
                ch.getChannelId(), streamId, dev.getRtspTransport(), node.getServerId(),
                ready ? "STREAMING" : "INVITING");
        return new PlayResult(s.getId(), streamId, ready ? "STREAMING" : "INVITING",
                urlService.buildUrls(node.getServerId(), streamId, 3600),
                ready ? publishedCodec.getOrDefault(s.getId(), "") : "", node.getServerId());
    }

    /**
     * 常驻流复用（M5-B1）: 流已在 ZLM 注册（活跃已验证）, 会话行直接以 STREAMING 落库
     * （startTime 立即, viewer=1）——不等 on_stream_changed（常驻流的 regist=true 只在
     * 建流瞬间发生一次, 不会为观众重发）。会话关闭时 cleanupForStream 豁免常驻条目,
     * 常驻流保持不拆。
     */
    private PlayResult alwaysOnReuse(GbDeviceChannelEntity ch, GbDeviceEntity dev,
            int streamProfile, Long userId, String serverId) {
        String streamId = LIVE_STREAM_PREFIX + ch.getChannelId();
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setSessionType(GbMediaSessionEntity.TYPE_LIVE);
        s.setStreamId(streamId);
        s.setApp("rtp");
        s.setDeviceDbId(dev.getId());
        s.setChannelDbId(ch.getId());
        s.setDeviceId(dev.getDeviceId());
        s.setChannelId(ch.getChannelId());
        s.setServerId(serverId);
        s.setTcpMode(1);
        s.setStreamProfile(streamProfile);
        s.setUserId(userId);
        s.setStatus("STREAMING");
        s.setViewerCount(1);
        s.setSsrc("");
        s.setInviteTime(LocalDateTime.now());
        s.setStartTime(LocalDateTime.now());
        sessionMapper.insert(s);
        log.info("RTSP 常驻流复用: channel={} stream={} node={} viewer=1",
                ch.getChannelId(), streamId, serverId);
        return new PlayResult(s.getId(), streamId, "STREAMING",
                urlService.buildUrls(serverId, streamId, 3600),
                resolveVideoCodec(serverId, streamId), serverId);
    }


    /**
     * ONVIF 回放建流（M4-B3, docs/13 §5.3）: resolveReplayUrl（GetReplayUri 优先→失败降级
     * 海康 tracks 推导）→ addStreamProxy 拉流代理。与 onvifInviteNew 同构（公共段复用）, 差异:
     * sessionType=回放 / streamId=gb_playback_*（每次唯一, 不参与实况复用; 异窗口已有回放
     * 会话的拒绝在 doPlay 主干 active 分支已完成）/ playbackStart/End 落库 /
     * controlMode="seek"（拉流代理无暂停/倍速能力, docs/13 §5.3 定级, PAUSE/RESUME/SCALE
     * 由 playbackControl 明确拒绝）。
     */
    private PlayResult onvifPlaybackNew(GbDeviceChannelEntity ch, GbDeviceEntity dev, Long userId,
            String preferredServerId, LocalDateTime playbackStart, LocalDateTime playbackEnd) {
        // 回放地址解析是设备 SOAP 外呼: 失败直接抛, 不落半截会话行
        String pullUrl = accessRegistry.of(DeviceProtocol.ONVIF)
                .resolveReplayUrl(dev, ch, playbackStart, playbackEnd);
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setSessionType(GbMediaSessionEntity.TYPE_PLAYBACK);
        s.setStreamId(newPlaybackStreamId(ch.getChannelId()));
        s.setApp("rtp");
        s.setDeviceDbId(dev.getId());
        s.setChannelDbId(ch.getId());
        s.setDeviceId(dev.getDeviceId());
        s.setChannelId(ch.getChannelId());
        s.setTcpMode(1);
        s.setStreamProfile(1);
        s.setUserId(userId);
        s.setStatus("INVITING");
        s.setViewerCount(1);
        s.setSsrc("");
        s.setInviteTime(LocalDateTime.now());
        s.setPlaybackStart(playbackStart);
        s.setPlaybackEnd(playbackEnd);
        GbMediaServerEntity node = createProxySession(s, ZlmAddStreamProxyRequest.forOnvifPull("rtp", s.getStreamId(), pullUrl), preferredServerId);
        // 等流注册与点播同口径（双实例共库轮询可见, 超时退化 WS 事件驱动）
        boolean ready = awaitStreaming(s.getStreamId());
        log.info("ONVIF 回放已发起: channel={} stream={} node={} status={} 窗口[{} ~ {}]",
                ch.getChannelId(), s.getStreamId(), node.getServerId(),
                ready ? "STREAMING" : "INVITING", playbackStart, playbackEnd);
        return new PlayResult(s.getId(), s.getStreamId(), ready ? "STREAMING" : "INVITING",
                urlService.buildUrls(node.getServerId(), s.getStreamId(), 3600),
                ready ? publishedCodec.getOrDefault(s.getId(), "") : "", node.getServerId(), "seek");
    }

    /**
     * ONVIF 回放控制（M4-B3, docs/13 §5.3 定级）：拉流代理形态无 SIP 对话——
     * SEEK=同 streamId 重建代理（delStreamProxy → 换 starttime 拉流地址 → addStreamProxy,
     * 播放 URL/签名不变, 前端凭 STREAM_READY 新地址重挂; <b>不改写会话 playbackStart/End</b>——
     * 与 GB seekPlayback 同口径: SEEK 只重定位当前播放位置, 会话窗口保持原始请求窗, 否则
     * 向后拖动会被窗口下界校验拒绝）; PAUSE/RESUME/SCALE 明确拒绝（承载 ZLM build 无
     * setSpeed/代理暂停 API, RTSP Scale 透传路径不存在——cam1+ZLM 实测定级）。
     * <p>时序（评审 B3-P2-3）: 回放地址解析（SOAP 最坏 24s）在<b>锁外</b>完成, 之后取与
     * doPlay/stop 同一把通道锁——锁内复核会话终态（stop 竞速时不再重建代理, 防 ZLM 孤儿代理
     * 无限回源拉相机）→ 挂跨实例豁免标记（Redis, hook 判死可能落备实例）→ 拆建代理。
     * 并发 SEEK 被锁串行化。</p>
     */
    private void onvifPlaybackControl(GbMediaSessionEntity s, GbDeviceEntity dev, String cmd,
            LocalDateTime seekTime) {
        if (!CMD_SEEK.equals(cmd)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "ONVIF 回放拉流代理形态不支持" + cmd + "控制（仅支持拖动跳转）");
        }
        // 窗口校验对齐 GB seekPlayback 口径（评审 B3-P3-2）: 判空防 NPE; 严格 < end（==end 的
        // tracks URI 起止相同, 相机大概率拒绝）
        if (seekTime == null || s.getPlaybackStart() == null || s.getPlaybackEnd() == null
                || seekTime.isBefore(s.getPlaybackStart()) || !seekTime.isBefore(s.getPlaybackEnd())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "跳转时刻超出回放窗口");
        }
        GbDeviceChannelEntity ch = channelMapper.selectById(s.getChannelDbId());
        if (ch == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "通道不存在: " + s.getChannelDbId());
        }
        // 地址解析是设备 SOAP 外呼（GetReplayUri/GetStreamUri）, 放锁外防长时间阻塞同通道 play/stop
        String pullUrl = accessRegistry.of(DeviceProtocol.ONVIF)
                .resolveReplayUrl(dev, ch, seekTime, s.getPlaybackEnd());
        RLock lock = redisson.getLock("gb:lock:play:" + s.getChannelDbId());
        lock.lock();
        try {
            // 终态复核: SOAP 期间用户 stop / 并发操作已关会话并拆代理 → 不再重建（重建即孤儿代理泄漏）
            GbMediaSessionEntity cur = sessionMapper.selectById(s.getId());
            if (cur == null || SessionCleanupService.isTerminal(cur.getStatus())) {
                log.info("SEEK 时会话已终态, 放弃重建代理: stream={} status={}",
                        s.getStreamId(), cur == null ? "null" : cur.getStatus());
                return;
            }
            GbMediaServerEntity node = serverMapper.selectOne(
                    new LambdaQueryWrapper<GbMediaServerEntity>()
                            .eq(GbMediaServerEntity::getServerId, cur.getServerId()).last("LIMIT 1"));
            if (node == null) {
                throw new BizException(ErrorCode.ZLM_API_FAILED, "承载节点不存在: " + cur.getServerId());
            }
            // 先挂跨实例保护标记再动代理: del 触发的注销 hook 与 none_reader 可能先于 add 到达
            redisson.<String>getBucket(SEEK_GUARD_KEY + s.getStreamId())
                    .set("1", java.time.Duration.ofSeconds(SEEK_GUARD_TTL_SECONDS));
            try {
                streamProxies.cleanupForStream(s.getStreamId());
                streamProxies.registerSource(s.getStreamId(), node.getServerId(),
                        zlm.addStreamProxy(node.getServerId(),
                                ZlmAddStreamProxyRequest.forOnvifPull("rtp", s.getStreamId(), pullUrl)));
            } catch (RuntimeException e) {
                redisson.getBucket(SEEK_GUARD_KEY + s.getStreamId()).delete();
                // 旧代理已删且新代理未建成, 流不可恢复 → 会话补偿关闭（前端凭 STREAM_CLOSED 出错误态）
                asyncPool.submit(() -> {
                    if (cleanup.close(s.getId(), "FAILED", "seek_failed",
                            "跳转重建拉流失败: " + e.getMessage())) {
                        publisher.streamClosed(s.getStreamId(), "seek_failed");
                    }
                });
                if (e instanceof BizException be) {
                    throw be;
                }
                throw new BizException(ErrorCode.ZLM_API_FAILED, "跳转重建拉流失败: " + e.getMessage());
            }
            log.info("ONVIF 回放跳转: stream={} → {}, 窗口[{} ~ {}]",
                    s.getStreamId(), seekTime, s.getPlaybackStart(), s.getPlaybackEnd());
            // 评审 B3-P2-5: 清编码去重, 让新代理注册的 regist hook 真正补投 STREAM_READY
            // （republishCodec 按 videoCodec 去重, 同编码不发布则前端主路径收不到新地址事件）
            publishedCodec.remove(s.getId());
        } finally {
            lock.unlock();
        }
    }

    /** SEEK 重建窗口判定（Redis 标记跨实例可见; hook 注销判死/none_reader/对账正向判死豁免用, 同包可见） */
    boolean isSeeking(String streamId) {
        return redisson.<String>getBucket(SEEK_GUARD_KEY + streamId).isExists();
    }

    /**
     * ONVIF 建流就绪等待: 短轮询活跃会话直至 STREAMING。会话被并发判死/清理
     * (findActiveByStream 只查 ACTIVE)即提前退出。窗口内未就绪返回 false, 调用方退化 INVITING。
     */
    private boolean awaitStreaming(String streamId) {
        long deadline = System.currentTimeMillis() + onvifAwaitTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            GbMediaSessionEntity cur = findActiveByStream(streamId);
            if (cur == null) {
                return false;
            }
            if ("STREAMING".equals(cur.getStatus())) {
                return true;
            }
            try {
                Thread.sleep(onvifAwaitIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 回放 streamId（docs/03 §6 示例 gb_playback_{channelId}_{HHmmss}_{rand}）: 每次唯一, 不参与实况复用。
     * rand 取两位随机数（进程内毫秒级并发冲突由 uk_active 兜底）。
     */
    private static String newPlaybackStreamId(String channelId) {
        String hhmmss = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        int rand = java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
        return "gb_playback_" + channelId + "_" + hhmmss + "_" + String.format("%02d", rand);
    }

    private PlayResult reuseResult(GbMediaSessionEntity s) {
        boolean streaming = "STREAMING".equals(s.getStatus());
        boolean playback = s.getSessionType() != null
                && s.getSessionType() == GbMediaSessionEntity.TYPE_PLAYBACK;
        GbDeviceEntity dev = playback ? deviceMapper.selectById(s.getDeviceDbId()) : null;
        boolean onvif = playback && dev != null && DeviceProtocol.of(dev) == DeviceProtocol.ONVIF;
        return new PlayResult(s.getId(), s.getStreamId(), streaming ? "STREAMING" : s.getStatus(),
                streaming ? urlService.buildUrls(s.getServerId(), s.getStreamId(), 3600) : Map.of(),
                streaming ? resolveVideoCodec(s.getServerId(), s.getStreamId()) : "", s.getServerId(),
                playback ? (onvif ? "seek" : "sip") : "");
    }

    /** 编码重试执行器（默认复用 asyncPool; 测试可替换为同步执行器保证确定性） */
    private java.util.concurrent.Executor codecRetryExecutor = asyncPool::execute;

    /** ONVIF 受理后同步等流就绪的窗口/轮询间隔（包可见, 测试覆盖; 5s 足够覆盖相机 RTSP
     *  慢启动, 超时退化为 WS 事件驱动不阻塞响应） */
    volatile long onvifAwaitTimeoutMs = 5000;
    volatile long onvifAwaitIntervalMs = 100;

    /** ONVIF 回放 SEEK 重建代理的保护标记键前缀（Redis 跨实例可见——hook 判死可能落备实例, M4-B3） */
    static final String SEEK_GUARD_KEY = "gb:seek:";
    /** 保护窗口: 覆盖 del→add→注册典型耗时(<3s), TTL 自过期防泄漏 */
    static final long SEEK_GUARD_TTL_SECONDS = 20;

    /** 测试注入点: 替换编码重试执行器 */
    void setCodecRetryExecutor(java.util.concurrent.Executor e) {
        this.codecRetryExecutor = e;
    }

    /**
     * B5: 编码解析异步短重试（4 次间隔 500ms, 总窗 ~2s; 不占用 hook 串行池）。
     * 成功且会话仍 STREAMING 时走与迟到补投相同的去重发布; 会话已终态则丢弃。
     */
    void retryCodecAndRepublish(String serverId, String streamId) {
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            String c = resolveVideoCodec(serverId, streamId);
            if (!c.isEmpty()) {
                GbMediaSessionEntity s = findActiveByStream(streamId);
                if (s == null || !"STREAMING".equals(s.getStatus())) {
                    return;   // 会话已终态: 丢弃
                }
                republishCodec(s, c);
                return;
            }
        }
    }

    /** B5: 迟到编码去重补投 STREAM_READY（inviting 态前端幂等刷新, playing 态前端补网关） */
    private void republishCodec(GbMediaSessionEntity s, String codec) {
        if (codec.isEmpty() || codec.equals(publishedCodec.get(s.getId()))) {
            return;
        }
        if (publishedCodec.size() > 4096) {
            // 终态未覆盖路径(对账任务直关)的泄漏兜底: 清空最坏一次重复补投, 前端幂等无害
            publishedCodec.clear();
        }
        publishedCodec.put(s.getId(), codec);
        log.info("编码迟到补投: stream={} videoCodec={}", s.getStreamId(), codec);
        publisher.streamReady(s.getStreamId(), s.getChannelId(),
                urlService.buildUrls(s.getServerId(), s.getStreamId(), 3600), codec);
    }

    /** 复用点播时的视频编码（ZLM getMediaList tracks; 查询失败容错为空, 不阻塞起播） */
    private String resolveVideoCodec(String serverId, String streamId) {
        try {
            for (ZlmMediaInfo m : zlm.listMedia(serverId)) {
                if ("rtp".equals(m.app()) && streamId.equals(m.stream())) {
                    return m.videoCodec() == null ? "" : m.videoCodec();
                }
            }
        } catch (Exception e) {
            log.debug("查询流视频编码失败(忽略): stream={} err={}", streamId, e.getMessage());
        }
        return "";
    }

    /** 点播结果（docs/03 §4.1 响应契约; videoCodec: H264/H265, 空=未知, B5 多协议;
     *  serverId: 会话承载节点, B7 按需点播据此判定是否需要给请求节点补跨节点代理;
     *  controlMode: 回放控制能力定级(M4-B3 §5.3)——"sip"=GB 全控(INFO MANSRTSP+re-INVITE),
     *  "seek"=仅拖动(ONVIF 重建代理), ""=不适用(实况/点播) */
    public record PlayResult(long sessionId, String streamId, String status,
            Map<String, String> urls, String videoCodec, String serverId, String controlMode) {

        /** 兼容旧 6 参构造（controlMode 缺省空 = 无回放控制语义） */
        public PlayResult(long sessionId, String streamId, String status,
                Map<String, String> urls, String videoCodec, String serverId) {
            this(sessionId, streamId, status, urls, videoCodec, serverId, "");
        }
    }
}
