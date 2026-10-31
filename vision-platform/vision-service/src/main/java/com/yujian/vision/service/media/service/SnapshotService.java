package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.auth.DigestAuthenticator;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmSnap;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.ha.HaPeerClient;
import com.yujian.vision.service.media.config.SnapshotProperties;
import com.yujian.vision.service.media.entity.GbChannelSnapshotEntity;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.entity.SnapshotVo;
import com.yujian.vision.service.media.mapper.GbChannelSnapshotMapper;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.record.CloudRecordService;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 服务端抓图（M3-B7 阶段2, docs/09）：ZLM getSnap 抽帧 → 落盘 → gb_channel_snapshot 每通道最新一张。
 *
 * <p>三条触发路径: ①手动 captureManual（HTTP 线程同步执行, 最坏 ~20s = 空闲建流 8s + 抓帧预算 8s + 越界余量）;
 * ②点播流就绪自动抓 onStreamReady（HookService 投递, 独立池延迟执行, 不占 hook 串行池）;
 * ③空闲通道抓图（无流通道临时建流, 抓完即停——停流走 stop() 的 viewer 语义, 期间有真人点播不误停）。</p>
 *
 * <p>真图判定: ZLM 流未就绪时 getSnap 回 defaultSnap 占位图（PNG）, 按 content-type 含 jpeg/jpg
 * <b>且</b>前 3 字节 FF D8 FF 双条件判定, 非真图按 poll-interval-ms 轮询至 timeout-ms 预算耗尽。</p>
 *
 * <p>签名图片 URL: 免登录读图端点（形制同 /api/cloud-records/file）用
 * md5(channelDbId:size:expire:aesKey) 防盗链, TTL = url-ttl-seconds, 泄露仅暴露单张通道截图。</p>
 */
@Slf4j
@Service
public class SnapshotService {

    /** 自动抓图去重键前缀: 同通道 10s 内只触发一次（多播放器/重试连发 on_stream_changed 场景） */
    static final String AUTO_KEY = "gb:snapshot:auto:";
    /** 自动抓图去重窗口秒数（与按需建流去重同阶: 覆盖一次抓图的典型耗时） */
    static final long AUTO_DEDUP_SECONDS = 10;
    /** 自拉流地址 TTL 秒: 只需覆盖一次抓图会话（含占位图轮询预算）, 短 TTL 减少签名暴露窗口 */
    private static final long SELF_PULL_TTL_SECONDS = 120;
    /** 空闲建流后等会话到 STREAMING 的步长毫秒 */
    private static final long IDLE_POLL_STEP_MS = 200;
    /** getSnap 单次超时上限秒（= ZlmApiClient 契约上限; 冷抓真帧 4~5.4s, 不得低于该量级） */
    private static final int SNAP_CALL_MAX_SEC = ZlmApiClient.SNAP_MAX_TIMEOUT_SECONDS;
    /** getSnap expire_sec: ZLM 侧同 url 结果缓存秒数（本层每次抓图 url 都不同, 缓存只影响单次会话） */
    private static final int SNAP_EXPIRE_SEC = 3;
    /** 缩略图文件名后缀 */
    private static final String THUMB_SUFFIX = "_t";

    private final SnapshotProperties props;
    private final GbDeviceMapper deviceMapper;
    private final DeviceAccessRegistry accessRegistry;
    private final GbChannelSnapshotMapper snapshotMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final MediaSessionService sessions;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final PlayUrlService urls;
    private final RedissonClient redisson;
    private final HaPeerClient haPeer;
    private final StreamProxyService streamProxies;
    /** 签名密钥材料: 复用 vision.security.aes.key（docs/10 §1, prod 环境变量注入强随机值） */
    private final String aesKey;
    /**
     * 抓图执行池（独立于 hook 串行池与 media-async 池）: 自动抓图含秒级轮询等待,
     * 落 hook 池会阻塞状态机事件, 落 media-async 会挤占对讲/降级路径。
     * <b>构造器体内构建</b>——props 是构造器赋值的 blank final, 线程数取 props.concurrency,
     * 字段初始化器直引会触发 Java 定值分析编译错（同 HookService.countOnDemand javadoc）。
     */
    private final ScheduledThreadPoolExecutor snapPool;

    /** 空闲建流等待上限毫秒（生产 8s; 包私有非 final 供单测调小加速） */
    long idleBuildWaitMs = 8_000L;

    public SnapshotService(SnapshotProperties props,
            GbChannelSnapshotMapper mapper,
            GbDeviceChannelMapper channelMapper,
            MediaSessionService sessions,
            GbMediaServerMapper serverMapper,
            ZlmApiClient zlm,
            PlayUrlService urls,
            RedissonClient redisson,
            HaPeerClient haPeer,
            GbDeviceMapper deviceMapper,
            DeviceAccessRegistry accessRegistry,
            StreamProxyService streamProxies,
            @Value("${vision.security.aes.key:}") String aesKey) {
        this.props = props;
        this.deviceMapper = deviceMapper;
        this.accessRegistry = accessRegistry;
        this.snapshotMapper = mapper;
        this.channelMapper = channelMapper;
        this.sessions = sessions;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.urls = urls;
        this.redisson = redisson;
        this.haPeer = haPeer;
        this.streamProxies = streamProxies;
        this.aesKey = aesKey == null ? "" : aesKey;
        AtomicInteger seq = new AtomicInteger(0);
        this.snapPool = new ScheduledThreadPoolExecutor(Math.max(1, props.getConcurrency()), r -> {
            Thread t = new Thread(r, "snapshot-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        log.info("服务端抓图已就绪: enabled={} idleEnabled={} autoOnPlay={} dir={} concurrency={}",
                props.isEnabled(), props.isIdleEnabled(), props.isAutoOnPlay(),
                props.getDir(), props.getConcurrency());
    }

    @PreDestroy
    void shutdownPool() {
        snapPool.shutdownNow();
    }

    // ---------------------------------------------------------------- 手动抓图

    /**
     * 手动抓图（docs/03 通道截图契约）: 优先复用通道当前活跃流; 无流且开启空闲抓图时
     * 临时建流抓完即停。同步执行——调用方（HTTP 线程）最坏 ~23s（空闲建流 8s + 抓帧预算 15s;
     * HA 备台转发读超时须大于此值, 见 application.yaml）。
     */
    public SnapshotVo captureManual(long channelDbId) {
        GbDeviceChannelEntity ch = requireChannel(channelDbId);
        GbChannelSnapshotEntity row = capture(ch, GbChannelSnapshotEntity.SOURCE_MANUAL, true);
        log.info("手动抓图成功: channel={} source={} file={} {}x{} {}B",
                ch.getChannelId(), row.getSource(), row.getFileName(),
                row.getWidth(), row.getHeight(), row.getSizeBytes());
        return toVo(row);
    }

    // ---------------------------------------------------------------- 自动抓图（点播流就绪）

    /**
     * 点播流就绪自动抓图入口（HookService.on_stream_changed regist=true 投递, 必须快速返回）:
     * 守卫通过后向独立池投递延迟任务（auto-delay-seconds 等首帧稳定防黑帧）。
     * 守卫（全部在此同步判定, 不占任何池）: 总开关/autoOnPlay 开关/实况流前缀
     * （回放 gb_playback_*、对讲/级联内部流前缀不同天然跳过）。
     */
    public void onStreamReady(String streamId) {
        if (!props.isEnabled() || !props.isAutoOnPlay()) {
            return;
        }
        if (streamId == null || streamId.isBlank()
                || !streamId.startsWith(MediaSessionService.LIVE_STREAM_PREFIX)) {
            return;
        }
        try {
            snapPool.schedule(() -> autoCapture(streamId), props.getAutoDelaySeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // 池已关闭/拒绝等调度失败只告警: 自动抓图是附赠能力, 不影响点播主链路
            log.warn("自动抓图调度失败(忽略): stream={} err={}", streamId, e.getMessage());
        }
    }

    /** 自动抓图执行体（snapPool 线程）: 全量吞异常——失败只降级为"这张图没有", 不惊动用户 */
    private void autoCapture(String streamId) {
        try {
            String channelId = streamId.substring(MediaSessionService.LIVE_STREAM_PREFIX.length());
            GbDeviceChannelEntity ch = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .eq(GbDeviceChannelEntity::getChannelId, channelId)
                    .isNull(GbDeviceChannelEntity::getDeletedTime)
                    .last("LIMIT 1"));
            if (ch == null) {
                return;   // 通道已删/预建档不存在: 静默跳过
            }
            // Redis 去重: setIfAbsent 原子「不存在才写」, 连发 hook 只抓一次
            if (!redisson.<String>getBucket(AUTO_KEY + ch.getId())
                    .setIfAbsent("1", Duration.ofSeconds(AUTO_DEDUP_SECONDS))) {
                log.debug("自动抓图去重命中(在途), 跳过: stream={}", streamId);
                return;
            }
            // 节流: 已有图且未超 auto-refresh-seconds → 跳过（0=仅无图时抓）。
            // 行 selectById 无并发问题: 最坏多抓一张, 写新删旧收敛为每通道一行
            GbChannelSnapshotEntity existing = snapshotMapper.selectById(ch.getId());
            if (existing != null && existing.getCaptureTime() != null && props.getAutoRefreshSeconds() > 0) {
                long ageSec = Duration.between(existing.getCaptureTime(), LocalDateTime.now()).getSeconds();
                if (ageSec < props.getAutoRefreshSeconds()) {
                    log.debug("自动抓图节流命中: channel={} 图龄 {}s < {}s",
                            ch.getChannelId(), ageSec, props.getAutoRefreshSeconds());
                    return;
                }
            }
            GbChannelSnapshotEntity row = capture(ch, GbChannelSnapshotEntity.SOURCE_AUTO, false);
            log.debug("点播自动抓图完成: channel={} file={} {}x{} {}B",
                    ch.getChannelId(), row.getFileName(), row.getWidth(), row.getHeight(), row.getSizeBytes());
        } catch (BizException e) {
            // 自动抓图失败静默降级（allowIdle=false 无流/抓帧超时等）: 点播主链路不受影响
            log.warn("点播自动抓图跳过: stream={} code={} msg={}",
                    streamId, e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("点播自动抓图失败(忽略): stream={} err={}", streamId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 抓图主干

    /**
     * 抓图主干: 定位流 → 轮询真图 → 落盘 upsert。builtHere=true（本次临时建的空闲流）时
     * finally 停流——抓图者作为唯一观看者退出触发清理三件套; 期间有真人点播则 viewer≥1 不误停。
     * 建流后的等待超时/抓帧异常同样走停流（评审 P2-1: playOnDemand/复用都会 viewer+1,
     * 不回收则自建流白耗带宽、复用他人会话则计数永久多 1）。
     */
    private GbChannelSnapshotEntity capture(GbDeviceChannelEntity ch, String source, boolean allowIdle) {
        requireEnabled();
        String streamId = MediaSessionService.LIVE_STREAM_PREFIX + ch.getChannelId();
        // M4-B2 (docs/13 §5.2): ONVIF 通道优先 GetSnapshotUri 平台代取(免建流零冷抓等待),
        // 失败/不支持回退 ZLM 冷抓路径(ONVIF 点播 B2 已通, 空闲建流也可用)
        GbDeviceEntity snapDev = deviceMapper.selectById(ch.getDeviceDbId());
        if (snapDev != null && DeviceProtocol.of(snapDev) == DeviceProtocol.ONVIF) {
            try {
                byte[] jpeg = accessRegistry.of(DeviceProtocol.ONVIF).fetchSnapshot(snapDev, ch);
                return persist(ch, source, "", jpeg);
                // serverId 传空: 直取不经媒体节点, 表列有 DEFAULT '' 不拒绝
            } catch (Exception e) {
                log.warn("ONVIF 快照直取失败, 回退 ZLM 冷抓: channel={} err={}",
                        ch.getChannelId(), e.getMessage());
            }
        }

        GbMediaSessionEntity active = sessions.findActiveLive(ch.getId());
        String serverId;
        boolean builtHere = false;
        if (active != null && "STREAMING".equals(active.getStatus())) {
            serverId = active.getServerId();
        } else {
            // M5-B1: RTSP 常驻流无观众时平台侧无会话行, 但 ZLM 侧流恒活跃——常驻索引兜底
            // 直接热抓（不建临时会话, 抓完无需停流; 生命周期归收敛循环管, 与抓图无关）
            Map<String, String> alwaysOn = streamProxies.alwaysOnEntries(streamId);
            if (!alwaysOn.isEmpty()) {
                serverId = alwaysOn.keySet().iterator().next();
                log.info("常驻流快照兜底: 通道无会话但常驻流活跃, 直接热抓 channel={} node={}",
                        ch.getChannelId(), serverId);
            } else if (!allowIdle || !props.isIdleEnabled()) {
                // 手动+未开空闲抓图 / 自动路径(流刚注册理应有会话, 走到这里说明会话已不在) → 拒绝
                throw new BizException(ErrorCode.SNAPSHOT_NO_STREAM, props.isIdleEnabled()
                        ? ErrorCode.SNAPSHOT_NO_STREAM.getMsg() + "（当前无进行中的实况流）"
                        : ErrorCode.SNAPSHOT_NO_STREAM.getMsg());
            } else {
                // 空闲通道抓图: 临时建流（主/子码流档可配）。备台无 SIP 栈 → 委托活跃实例（M3-B7 同法）。
                // 建流请求本身失败(ok=false)时流未起, 无需回收; 起流后的任何失败一律 stopQuietly 回收
                builtHere = true;
                log.info("空闲抓图: 通道无流, 临时建流 channel={} stream={} profile={}",
                        ch.getChannelId(), streamId, props.getIdleProfile());
                boolean ok;
                if (haPeer.isLocal()) {
                    sessions.playOnDemand(streamId, null, props.getIdleProfile());
                    ok = true;
                } else {
                    ok = haPeer.requestOnDemand(streamId, null, props.getIdleProfile());
                }
                if (!ok) {
                    builtHere = false;
                    throw new BizException(ErrorCode.SNAPSHOT_FAILED, "空闲抓图建流失败");
                }
                try {
                    GbMediaSessionEntity built = awaitStreaming(ch.getId());
                    if (built == null) {
                        throw new BizException(ErrorCode.SNAPSHOT_FAILED, "空闲抓图建流超时");
                    }
                    serverId = built.getServerId();
                } catch (Exception e) {
                    stopQuietly(streamId);   // 停流对 INVITING 会话同样安全（条件减量+cleanup 单次守卫）
                    throw e;
                }
            }
        }
        try {
            byte[] jpeg = snapUntilReal(serverId, streamId);
            // source 反映出图方式: 本次临时建流的(含手动入口触发的空闲抓图)一律记 IDLE,
            // 复用已有流的按调用入口记 MANUAL/AUTO
            return persist(ch, builtHere ? GbChannelSnapshotEntity.SOURCE_IDLE : source, serverId, jpeg);
        } finally {
            if (builtHere) {
                stopQuietly(streamId);
            }
        }
    }

    /** 抓图总开关（手动入口的拒绝点; 自动路径由 onStreamReady 守卫前置拦截） */
    private void requireEnabled() {
        if (!props.isEnabled()) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "抓图总开关未开启");
        }
    }

    /** 轮询会话至 STREAMING（空闲建流后设备起播需 1~3s; 有界等待, 超时返回 null 由调用方报 1013） */
    private GbMediaSessionEntity awaitStreaming(long channelDbId) {
        long deadline = System.currentTimeMillis() + idleBuildWaitMs;
        while (true) {
            GbMediaSessionEntity s = sessions.findActiveLive(channelDbId);
            if (s != null && "STREAMING".equals(s.getStatus())) {
                return s;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            sleep(IDLE_POLL_STEP_MS);
        }
    }

    /**
     * 轮询真图: ZLM getSnap 对未就绪流回占位图（PNG）, 按 poll-interval-ms 步长重试至
     * timeout-ms 预算耗尽 → 1013"抓帧超时"。
     *
     * <p>单次超时跟随剩余预算（而非固定小值）: ZLM 冷抓真帧需 4~5.4s（242 cam1 H265 实测）,
     * 提前掐断会让 ZLM 把 snap 播放器留在占位图卡死态, 之后同一 url 全部秒回 PNG →
     * 轮询变成空转, 必然报 1013。ZLM 不可达等接口异常直接上抛（语义比笼统超时准）。
     */
    private byte[] snapUntilReal(String serverId, String streamId) {
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, serverId).last("LIMIT 1"));
        if (node == null) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "媒体节点不存在: " + serverId);
        }
        String pullUrl = urls.buildSelfPullUrl(node, streamId, SELF_PULL_TTL_SECONDS);
        long deadline = System.currentTimeMillis() + props.getTimeoutMs();
        while (true) {
            long remainMs = deadline - System.currentTimeMillis();
            int perCallSec = (int) Math.min(SNAP_CALL_MAX_SEC, Math.max(1, remainMs / 1000));
            ZlmSnap snap = zlm.getSnap(serverId, pullUrl, perCallSec, SNAP_EXPIRE_SEC);
            if (isRealJpeg(snap)) {
                return snap.body();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new BizException(ErrorCode.SNAPSHOT_FAILED, "抓帧超时（流未出图或设备未推流）");
            }
            sleep(props.getPollIntervalMs());
        }
    }

    /** 真图判定: content-type 含 jpeg/jpg 与 JPEG 魔数 FF D8 FF 两个条件 AND（占位图 PNG 双不过） */
    static boolean isRealJpeg(ZlmSnap snap) {
        if (snap == null || snap.body() == null || snap.body().length < 3) {
            return false;
        }
        String ct = snap.contentType() == null ? "" : snap.contentType().toLowerCase();
        boolean typeJpeg = ct.contains("jpeg") || ct.contains("jpg");
        boolean magic = (snap.body()[0] & 0xFF) == 0xFF
                && (snap.body()[1] & 0xFF) == 0xD8
                && (snap.body()[2] & 0xFF) == 0xFF;
        return typeJpeg && magic;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "抓图被中断");
        }
    }

    /** 空闲流停流（吞异常）: 已关闭(SESSION_NOT_FOUND)即视为成功——finally 语义; 其余失败同样吞,
     *  残留会话由 none_reader/对账兜底回收 */
    private void stopQuietly(String streamId) {
        try {
            sessions.stop(streamId, null);
            log.info("空闲抓图停流完成: stream={}", streamId);
        } catch (Exception e) {
            log.info("空闲抓图停流(容忍失败, 已关闭即成功): stream={} err={}", streamId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 落盘与元数据

    /**
     * 落盘 + upsert 行（写新删旧顺序: 先写新文件 → upsert 行 → 删旧文件;
     * 删旧失败吞掉——孤儿文件由 SnapshotCleanupTask 每日收扫）。缩略生成失败回退原图（thumbName 写空串）。
     */
    private GbChannelSnapshotEntity persist(GbDeviceChannelEntity ch, String source, String serverId,
            byte[] jpeg) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "抓帧解码失败: " + e.getMessage());
        }
        int w = img == null ? 0 : img.getWidth();
        int h = img == null ? 0 : img.getHeight();
        Path dir = Paths.get(props.getDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "截图目录创建失败: " + props.getDir());
        }
        String baseName = ch.getId() + "_" + System.currentTimeMillis();
        String fileName = baseName + ".jpg";
        try {
            Files.write(dir.resolve(fileName), jpeg);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "截图文件写入失败: " + e.getMessage());
        }
        String thumbName = writeThumb(dir, baseName, img);
        GbChannelSnapshotEntity existing = snapshotMapper.selectById(ch.getId());
        GbChannelSnapshotEntity row = existing == null ? new GbChannelSnapshotEntity() : existing;
        String oldOriginal = existing == null ? null : existing.getFileName();
        String oldThumb = existing == null ? null : existing.getThumbName();
        if (existing == null) {
            row.setChannelDbId(ch.getId());
            row.setTenantId(0L);   // v1 单域固定 0（D12）
        }
        row.setFileName(fileName);
        // 缩略失败写空串而非 null: MP updateById 默认 NOT_NULL 策略会跳过 null 字段,
        // DB 将残留上一次的 thumb_name 而旧缩略文件又被删 → readForServe 恒 1013（评审 P1-1）。
        // 全链路（readForServe/清理任务 missing()）都把 blank 视为无缩略, 空串即"无"语义
        row.setThumbName(thumbName == null ? "" : thumbName);
        row.setCaptureTime(LocalDateTime.now());
        row.setSource(source);
        row.setServerId(serverId == null ? "" : serverId);
        // 0=未知（同 updateById NOT_NULL 规避; 读侧不消费, 仅展示）
        row.setWidth(w > 0 ? w : 0);
        row.setHeight(h > 0 ? h : 0);
        row.setSizeBytes((long) jpeg.length);
        if (existing == null) {
            try {
                snapshotMapper.insert(row);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 同通道并发首次抓图（手动+自动抓竞态）双双走到 insert: PK 冲突转 update（评审 P2-2）
                snapshotMapper.updateById(row);
            }
        } else {
            snapshotMapper.updateById(row);
        }
        deleteOldQuietly(dir, oldOriginal, fileName);
        deleteOldQuietly(dir, oldThumb, thumbName == null ? "" : thumbName);
        return row;
    }

    /** 缩略图: 等比缩到 thumb-width（不放大）, TYPE_INT_RGB 落 JPEG; 任何失败回退 null（列表显示原图） */
    private String writeThumb(Path dir, String baseName, BufferedImage img) {
        if (img == null || props.getThumbWidth() <= 0) {
            return null;
        }
        try {
            int targetW = Math.min(props.getThumbWidth(), img.getWidth());
            if (targetW <= 0) {
                return null;
            }
            int targetH = Math.max(1, Math.round(img.getHeight() * (targetW * 1.0f / img.getWidth())));
            BufferedImage thumb = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = thumb.createGraphics();
            try {
                g.drawImage(img, 0, 0, targetW, targetH, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(thumb, "jpg", bos)) {
                return null;   // 无 jpg writer（理论不可达）: 回退原图
            }
            String thumbName = baseName + THUMB_SUFFIX + ".jpg";
            Files.write(dir.resolve(thumbName), bos.toByteArray());
            return thumbName;
        } catch (Exception e) {
            log.debug("缩略图生成失败(回退原图): base={} err={}", baseName, e.getMessage());
            return null;
        }
    }

    /** 写新删旧: 仅当旧文件存在且与新文件名不同才删（upsert 成功后调用; 失败吞——孤儿由清理任务收扫） */
    private static void deleteOldQuietly(Path dir, String oldName, String newName) {
        if (oldName == null || oldName.isBlank() || oldName.equals(newName)) {
            return;
        }
        CloudRecordService.deleteFileIfExists(dir.resolve(oldName).toString());
    }

    // ---------------------------------------------------------------- 读取与视图

    /** 读图结果（免登录图片端点用; body 为 JPEG 原始字节） */
    public record ServedImage(byte[] body, LocalDateTime captureTime) {
    }

    /**
     * 读图（免登录端点用, 调用方须先 verifyUrl）: size=thumb 且缩略存在读缩略图, 否则回退原图;
     * 行/文件缺失分别报 404/1013。文件名由平台生成（{channelDbId}_{epochMs}.jpg）无用户输入,
     * 无路径穿越风险。
     */
    public ServedImage readForServe(long channelDbId, boolean thumb) {
        GbChannelSnapshotEntity row = snapshotMapper.selectById(channelDbId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "截图不存在");
        }
        String name = thumb && row.getThumbName() != null && !row.getThumbName().isBlank()
                ? row.getThumbName()
                : row.getFileName();
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "截图不存在");
        }
        try {
            return new ServedImage(Files.readAllBytes(Paths.get(props.getDir()).resolve(name)),
                    row.getCaptureTime());
        } catch (IOException e) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED, "截图文件读取失败");
        }
    }

    /** 行 → 视图对象（url/thumbUrl 为带签名的同源相对地址, TTL=url-ttl-seconds） */
    public SnapshotVo toVo(GbChannelSnapshotEntity row) {
        return new SnapshotVo(buildUrl(row.getChannelDbId(), "full"), buildUrl(row.getChannelDbId(), "thumb"),
                row.getCaptureTime(), row.getSource());
    }

    /**
     * 列表页批量填充最新截图（ChannelQueryService.page 调用）: 一次 IN 查询按 channel_db_id 映射,
     * 无图通道 snapshot 保持 null（前端占位图标）。
     */
    public void fillSnapshots(List<GbDeviceChannelEntity> channels) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        List<Long> ids = channels.stream().map(GbDeviceChannelEntity::getId)
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, GbChannelSnapshotEntity> byChannel = snapshotMapper.selectList(
                        new LambdaQueryWrapper<GbChannelSnapshotEntity>()
                                .in(GbChannelSnapshotEntity::getChannelDbId, ids))
                .stream()
                .collect(Collectors.toMap(GbChannelSnapshotEntity::getChannelDbId, r -> r));
        for (GbDeviceChannelEntity c : channels) {
            GbChannelSnapshotEntity row = byChannel.get(c.getId());
            if (row != null) {
                c.setSnapshot(toVo(row));
            }
        }
    }

    // ---------------------------------------------------------------- 签名 URL（生成与校验同格式）

    /** 签名 URL（同源相对路径, 前端同域直接 GET）: /api/channels/{id}/snapshot?size=..&sign=..&expire=.. */
    private String buildUrl(long channelDbId, String size) {
        long expire = System.currentTimeMillis() / 1000 + props.getUrlTtlSeconds();
        return "/api/channels/" + channelDbId + "/snapshot?size=" + size
                + "&sign=" + signOf(channelDbId, size, expire) + "&expire=" + expire;
    }

    /** sign = md5(channelDbId:size:expire:aesKey)——生成（buildUrl）与校验（verifyUrl）必须同格式同密钥 */
    private String signOf(long channelDbId, String size, long expire) {
        return DigestAuthenticator.md5Hex(channelDbId + ":" + size + ":" + expire + ":" + aesKey);
    }

    /**
     * 免登录读图端点的签名复验: expire 非数字/已过期/签名不符/密钥材料缺失一律 false。
     * @param size 请求的尺寸档（full/thumb, 参与签名——换 size 即换 URL）
     */
    public boolean verifyUrl(long channelDbId, String size, String expire, String sign) {
        if (size == null || size.isBlank() || sign == null || sign.isBlank()
                || expire == null || expire.isBlank()) {
            return false;
        }
        if (aesKey.isBlank()) {
            // 密钥材料缺失时 sign 可离线伪造（md5(id:size:expire:) 无秘密量）, 一律拒绝
            return false;
        }
        long expireAt;
        try {
            expireAt = Long.parseLong(expire.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (expireAt < System.currentTimeMillis() / 1000) {
            return false;
        }
        // 恒定时间比对（形制同 HookService.secretValid; md5hex 场景实操风险低, 顺手对齐）
        return MessageDigest.isEqual(signOf(channelDbId, size, expireAt).getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 共用

    /** 通道必须存在且未删（手动入口; 自动路径查不到直接跳过不报错） */
    private GbDeviceChannelEntity requireChannel(long channelDbId) {
        GbDeviceChannelEntity ch = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getId, channelDbId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
        if (ch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通道不存在");
        }
        return ch;
    }
}
