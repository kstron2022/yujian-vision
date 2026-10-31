package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.rtsp.RtspProbeClient;
import com.yujian.vision.media.zlm.ZlmAddStreamProxyRequest;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.onvif.DeviceProtocolConstants;
import com.yujian.vision.service.device.rtsp.RtspAccessProvider;
import com.yujian.vision.service.device.service.DeviceOfflineService;
import com.yujian.vision.service.ha.HaRuntimeService;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 常驻拉流收敛循环（M5-B1, docs/14 §7）: 对 always_on=1 的 RTSP 设备做周期收敛——
 * 「启动拉起」与「断线重建」统一为一个控制循环, 语义类似 K8s 控制面: 每轮扫描比对
 * 期望态（常驻流存在）与实际态（ZLM 侧流活跃）, 偏离即重建。
 * 每轮动作: ①常驻索引活性校验, 死 key（ZLM 侧流已消失）清除 ②流活着 → 刷 keepalive
 * +离线翻转上线（联动通道 ON）③无活跃常驻流且退避到期 → 先探测源可达（省 ZLM API 调用）
 * → 调度节点 addStreamProxy → 登记常驻索引; 失败指数退避 5s→300s 封顶。
 * 门禁: sipGatesOpen()（建流是重副作用, 防双实例重复拉流, 备台不跑; 备接管后由新主收敛）+
 * tryLock(0) 互斥。流断后不刷 keepalive → DeviceOfflineTask 按 interval×timeout 兜底判离线。
 */
@Slf4j
@Component
public class RtspAlwaysOnTask {

    private static final String LOCK_KEY = "gb:lock:task:rtsp-always-on";
    /** 退避基值/封顶（秒）: 失败 5s 起 ×2 递增, 封顶 300s */
    private static final long BACKOFF_BASE_SECONDS = 5;
    private static final long BACKOFF_MAX_SECONDS = 300;

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final RtspAccessProvider rtspProvider;
    private final StreamProxyService streamProxies;
    private final ZlmApiClient zlm;
    private final MediaNodeScheduler nodeScheduler;
    private final DeviceOfflineService offlineService;
    private final HaRuntimeService ha;
    private final MediaProperties media;
    private final GbMediaSessionMapper sessionMapper;
    private final RedissonClient redisson;

    /** 退避状态（deviceDbId → 下次尝试毫秒时间戳; 进程内存态, 单收敛实例自洽） */
    private final Map<Long, Long> nextAttemptAt = new ConcurrentHashMap<>();
    /** 收敛专用池(评审 P1-2): converge 含 ZLM/源侧阻塞 IO(探测超时+addStreamProxy), 全工程 @Scheduled
     *  共用单线程调度池, 直接在调度线程跑批量死源(最坏 200×6.5s)会拖停 HA 租约/离线判定等全部任务。
     *  in-flight 去重防上一轮未完成的设备本轮重复提交。 */
    private final java.util.concurrent.ExecutorService convergePool =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "rtsp-always-on");
                t.setDaemon(true);
                return t;
            });
    private final java.util.Set<Long> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 退避状态（deviceDbId → 当前退避秒数） */
    private final Map<Long, Long> backoffSeconds = new ConcurrentHashMap<>();

    public RtspAlwaysOnTask(GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            RtspAccessProvider rtspProvider, StreamProxyService streamProxies, ZlmApiClient zlm,
            MediaNodeScheduler nodeScheduler, DeviceOfflineService offlineService,
            HaRuntimeService ha, MediaProperties media, GbMediaSessionMapper sessionMapper,
            RedissonClient redisson) {
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.rtspProvider = rtspProvider;
        this.streamProxies = streamProxies;
        this.zlm = zlm;
        this.nodeScheduler = nodeScheduler;
        this.offlineService = offlineService;
        this.ha = ha;
        this.media = media;
        this.sessionMapper = sessionMapper;
        this.redisson = redisson;
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        if (!ha.sipGatesOpen()) {
            return;
        }
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            doTick();
        } finally {
            lock.unlock();
        }
    }

    /** 包内可见便于单测 */
    /** 包内可见便于单测 */
    void doTick() {
        List<GbDeviceEntity> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.RTSP)
                        .eq(GbDeviceEntity::getAlwaysOn, 1)
                        .isNull(GbDeviceEntity::getDeletedTime));
        for (GbDeviceEntity device : devices) {
            if (!inFlight.add(device.getId())) {
                continue;
            }
            convergePool.submit(() -> {
                try {
                    converge(device);
                } catch (Exception e) {
                    log.warn("常驻收敛异常: device={} err={}", device.getDeviceId(), e.getMessage());
                } finally {
                    inFlight.remove(device.getId());
                }
            });
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        convergePool.shutdown();
    }

    /** 单台收敛: 活性校验 → keepalive/翻转 → 缺流时退避重建 */
    private void converge(GbDeviceEntity device) {
        GbDeviceChannelEntity ch = channelMapper.selectOne(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, device.getId())
                        .isNull(GbDeviceChannelEntity::getDeletedTime)
                        .last("LIMIT 1"));
        if (ch == null) {
            return;
        }
        String streamId = MediaSessionService.LIVE_STREAM_PREFIX + ch.getChannelId();
        // ① 活性校验: 常驻索引 key 对应的 ZLM 流已消失 → 清死 key
        boolean alive = false;
        for (Map.Entry<String, String> e : streamProxies.alwaysOnEntries(streamId).entrySet()) {
            if (streamProxies.streamActiveOn(e.getKey(), streamId)) {
                alive = true;
            } else {
                streamProxies.removeAlwaysOn(streamId, e.getKey());
            }
        }
        if (alive) {
            // ② 流活着: keepalive 恒新鲜（DeviceOfflineTask 兜底不触发）, 离线态则翻转上线
            offlineService.keepaliveAndOnline(device);
            nextAttemptAt.remove(device.getId());
            backoffSeconds.remove(device.getId());
            return;
        }
        // ③ 无活跃常驻流: 有观众在看的按需会话先不抢（其源代理随会话生命周期管理,
        // 走光后下一轮收敛补建）; 退避到期才重建
        if (device.getOnline() != null && device.getOnline() == 1 && hasActiveSession(ch.getId())) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long next = nextAttemptAt.get(device.getId());
        if (next != null && next > nowMs) {
            return;
        }
        try {
            rebuild(device, ch, streamId);
            nextAttemptAt.remove(device.getId());
            backoffSeconds.remove(device.getId());
        } catch (Exception e) {
            long sec = Math.min(
                    backoffSeconds.merge(device.getId(), BACKOFF_BASE_SECONDS, (old, v) -> old * 2),
                    BACKOFF_MAX_SECONDS);
            nextAttemptAt.put(device.getId(), nowMs + sec * 1000);
            log.warn("常驻拉流重建失败(退避 {}s): device={} stream={} err={}",
                    sec, device.getDeviceId(), streamId, e.getMessage());
        }
    }

    /** 重建常驻代理: 源可达性预探测（不可达省 ZLM API）→ 调度节点 → addStreamProxy → 登记常驻索引 */
    private void rebuild(GbDeviceEntity device, GbDeviceChannelEntity ch, String streamId) {
        String pullUrl = rtspProvider.resolveStreamUrl(device, ch, 1);
        RtspProbeClient client = new RtspProbeClient(media.getRtspProbeTimeoutMs(),
                media.getRtspProbeTimeoutMs());
        RtspProbeClient.ProbeResult r = client.probe(pullUrl);
        if (!r.ok()) {
            throw new BizException(com.yujian.vision.common.api.ErrorCode.BAD_REQUEST,
                    "源不可达: " + r.message());
        }
        GbMediaServerEntity node = nodeScheduler.select(null, java.util.Set.of());
        var proxyReq = ZlmAddStreamProxyRequest.forRtspPull("rtp", streamId, pullUrl,
                device.getRtspTransport());
        String key = zlm.addStreamProxy(node.getServerId(), proxyReq);
        streamProxies.registerAlwaysOn(streamId, node.getServerId(), key);
        log.info("常驻拉流已建立: device={} stream={} node={} transport={}",
                device.getDeviceId(), streamId, node.getServerId(), device.getRtspTransport());
    }

    /** 有观众在看的按需会话（活跃态）收敛暂不抢建, 防同名流 addStreamProxy 冲突 */
    private boolean hasActiveSession(Long channelDbId) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getChannelDbId, channelDbId)
                .eq(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_LIVE)
                .in(GbMediaSessionEntity::getStatus, "INIT", "INVITING", "STREAMING", "CLOSING")) > 0;
    }
}
