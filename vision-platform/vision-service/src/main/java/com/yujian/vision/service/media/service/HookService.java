package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.cascade.service.CascadeInviteService;
import com.yujian.vision.service.ha.HaPeerClient;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.monitor.VisionMetrics;
import com.yujian.vision.service.record.CloudRecordService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ZLM Hook 处理（docs/04 §1/§2）：on_publish/on_play 同步裁决, 其余异步投递。
 * on_server_keepalive/on_server_started 维护节点在线状态与 Redis 保活键（docs/06 §7）。
 * 幂等：由会话状态机按当前态判断, 重复事件忽略。
 */
@Slf4j
@Service
public class HookService {

    /** 节点保活 Redis 键前缀（on_server_keepalive 写入, NodeKeepaliveTask 检查, docs/06 §7） */
    public static final String NODE_KEEPALIVE_KEY = "gb:server:keepalive:";
    /** 保活 TTL: 3 × ZLM keepAliveSecond(15s) */
    public static final long NODE_KEEPALIVE_TTL_SECONDS = 45;
    /** hook secret 缓存 TTL */
    private static final long SECRET_CACHE_TTL_MS = 30_000;
    /** 按需建流去重键前缀（M3-B7）: 同一 streamId 在 TTL 内只触发一次建流 */
    static final String ON_DEMAND_KEY = "gb:ondemand:";
    /** 按需建流去重窗口秒数: 覆盖"播放器重试/多播放器同时拉同一不存在的流"的连发 hook,
     *  需 ≥ 一次建流的典型耗时（INVITE→流注册, 秒级）, 过长会拖慢真失败的恢复 */
    static final long ON_DEMAND_DEDUP_SECONDS = 10;

    private final MediaSessionService sessions;
    private final PlayUrlService urlService;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final RedissonClient redisson;
    private final AesCipher aes;
    private final CloudRecordService cloudRecords;
    private final VisionMetrics metrics;
    private final CascadeInviteService cascadeInvites;
    /** 按需点播行为配置（M3-B7: 开关与码流档） */
    private final MediaProperties media;
    /** 备台 → 活跃实例内部调用（M3-B7: 备台无 SIP 栈, 建流须委托活跃实例） */
    private final HaPeerClient haPeer;
    /** 点播流就绪自动抓图（M3-B7 阶段2: 独立池延迟执行, 与云端录像触发同形制） */
    private final SnapshotService snapshots;
    /** hook 事件丢弃计数（B6 有界化观测: 大于 0 说明出现过 hook 风暴积压） */
    final AtomicInteger hookDropped = new AtomicInteger();
    /** 按需建流任务丢弃计数（M3-B7, 与 hookDropped 同口径观测） */
    final AtomicInteger onDemandDropped = new AtomicInteger();

    /**
     * 按需建流执行器（M3-B7）: <b>独立于</b>下方单线程 hook 池——建流要等 INVITE 应答与流注册
     * （秒级）, 落在 hook 串行池里会阻塞 on_stream_changed/none_reader 等状态机事件。
     * 4 线程 + 有界队列 256, 满则丢新任务（丢弃无害: 播放器重试会再触发一次 hook）。
     */
    private final ExecutorService onDemandPool = new java.util.concurrent.ThreadPoolExecutor(4, 4,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(256),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "zlm-on-demand-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            (r, executor) -> {
                long n = onDemandDropped.incrementAndGet();
                countOnDemand("drop");
                if (n == 1 || n % 64 == 0) {
                    log.warn("按需建流池队列满(256), 丢弃任务, 累计: {}", n);
                }
            });
    /**
     * hook 异步事件池（B6 有界化）: 单线程保序（regist true/false 顺序敏感）,
     * 队列 8192 覆盖 5000 通道突发 regist + 心跳杂项; 满则丢新事件并计数
     * （事件流允许有损: on_stream_changed 迟到由 retryCodecAndRepublish/4096 兜底自愈,
     * 云端录像/none_reader 丢一条不影响状态机收敛）, 防风暴拖垮平台。
     */
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(8192),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "zlm-hook-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            (r, executor) -> {
                long n = hookDropped.incrementAndGet();
                if (n == 1 || n % 1024 == 0) {
                    log.warn("hook 池队列满(8192), 丢弃事件, 累计: {}", n);
                }
            });

    public HookService(MediaSessionService sessions, PlayUrlService urlService,
            GbMediaServerMapper serverMapper, ZlmApiClient zlm, RedissonClient redisson, AesCipher aes,
            CloudRecordService cloudRecords, VisionMetrics metrics, CascadeInviteService cascadeInvites,
            MediaProperties media, HaPeerClient haPeer, SnapshotService snapshots) {
        this.sessions = sessions;
        this.urlService = urlService;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.redisson = redisson;
        this.aes = aes;
        this.cloudRecords = cloudRecords;
        this.metrics = metrics;
        this.cascadeInvites = cascadeInvites;
        this.media = media;
        this.haPeer = haPeer;
        this.snapshots = snapshots;
    }

    /** hook secret 缓存（30s TTL）: 避免每个 hook 请求都查库 */
    private volatile List<String> secretCache = List.of();
    private volatile long secretCacheAt = 0L;

    /**
     * Hook secret 校验（docs/04 §2 + docs/02 §0）：与解密后的全部节点 sign_secret 做
     * MessageDigest.isEqual 恒定时间比对（命中后继续遍历, 不提前返回, 防时序侧信道）。
     */
    public boolean secretValid(String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        List<String> expected = secretCache;
        long now = System.currentTimeMillis();
        if (now - secretCacheAt > SECRET_CACHE_TTL_MS) {
            expected = loadSecrets();
            secretCache = expected;
            secretCacheAt = now;
        }
        byte[] provided = secret.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (String s : expected) {
            if (s == null) {
                continue;
            }
            if (MessageDigest.isEqual(provided, s.getBytes(StandardCharsets.UTF_8))) {
                ok = true;
            }
        }
        return ok;
    }

    private List<String> loadSecrets() {
        try {
            return serverMapper.selectList(new LambdaQueryWrapper<GbMediaServerEntity>()).stream()
                    .map(GbMediaServerEntity::getSignSecret)
                    .filter(s -> s != null && !s.isBlank())
                    .map(aes::decrypt)
                    .toList();
        } catch (Exception e) {
            // 缓存加载失败时拒绝一切 hook（宁拒不放行）
            log.error("加载 hook secret 失败, 本次校验拒绝", e);
            return List.of();
        }
    }

    /** on_server_keepalive（docs/04 §1）: 刷新节点在线 + 保活键。跑在 hook 线程, 必须快(仅一次 DB 写 + 一次 Redis 写) */
    public void serverKeepalive(String mediaServerId) {
        countHook("server_keepalive");
        GbMediaServerEntity node = selectByServerId(mediaServerId);
        if (node == null) {
            log.warn("on_server_keepalive: 未知节点: {}", mediaServerId);
            return;
        }
        if (node.getStatus() == null || node.getStatus() != 1) {
            GbMediaServerEntity up = new GbMediaServerEntity();
            up.setId(node.getId());
            up.setStatus(1);
            serverMapper.updateById(up);
            log.info("节点上线: {}", mediaServerId);
        }
        redisson.<String>getBucket(NODE_KEEPALIVE_KEY + mediaServerId)
                .set("1", Duration.ofSeconds(NODE_KEEPALIVE_TTL_SECONDS));
        // on_server_started 在 ZLM 启动时仅发一次, 可能早于其 hook 子系统就绪而静默丢失:
        // keepalive 发现版本为空则异步补录, 一个心跳周期内自愈
        if (node.getZlmVersion() == null || node.getZlmVersion().isBlank()) {
            pool.submit(() -> {
                try {
                    String v = safeVersion(mediaServerId);
                    if (!v.isBlank()) {
                        GbMediaServerEntity up = new GbMediaServerEntity();
                        up.setId(node.getId());
                        // 防御截断: 列宽 64(V7), 异常版本串不因超长阻塞在线状态维护
                        up.setZlmVersion(v.length() > 64 ? v.substring(0, 64) : v);
                        serverMapper.updateById(up);
                        log.info("节点版本补录: {} {}", mediaServerId, v);
                    }
                } catch (Exception e) {
                    // async 任务异常会被 Future 吞掉, 显式记录
                    log.error("节点版本补录失败: {}", mediaServerId, e);
                }
            });
        }
    }

    /** on_server_started（docs/04 §1）: 节点(重)启 → 上线 + 版本落库。异步执行, 不占 hook 串行池 */
    public void serverStarted(String mediaServerId) {
        countHook("server_started");
        pool.submit(() -> {
            try {
                GbMediaServerEntity node = selectByServerId(mediaServerId);
                if (node == null) {
                    log.warn("on_server_started: 未知节点: {}", mediaServerId);
                    return;
                }
                String version = safeVersion(mediaServerId);
                GbMediaServerEntity up = new GbMediaServerEntity();
                up.setId(node.getId());
                up.setStatus(1);
                up.setZlmVersion(version);
                serverMapper.updateById(up);
                redisson.<String>getBucket(NODE_KEEPALIVE_KEY + mediaServerId)
                        .set("1", Duration.ofSeconds(NODE_KEEPALIVE_TTL_SECONDS));
                log.info("ZLM 节点已启动: {} version={}", mediaServerId, version);
            } catch (Exception e) {
                log.error("on_server_started 处理异常: {}", mediaServerId, e);
            }
        });
    }

    /** 取 ZLM 版本, 失败置空(版本仅展示用, 不阻断上线流程) */
    private String safeVersion(String serverId) {
        try {
            return zlm.getVersion(serverId);
        } catch (Exception e) {
            log.warn("获取 ZLM 版本失败(置空): {} ({})", serverId, e.getMessage());
            return "";
        }
    }

    private GbMediaServerEntity selectByServerId(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        return serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, serverId)
                .last("LIMIT 1"));
    }

    /**
     * hook 原始事件计数（docs/09 M2 #8 指标 vision.hook{event}）：各 hook 方法入口处打点,
     * 在空值守卫之前 —— 未知节点/未知流的非法事件同样计入事件率, 便于发现异常来源。
     */
    private void countHook(String event) {
        metrics.increment("vision.hook", "event", event);
    }

    /** 按需建流结果计数（docs/06 §3.1 观测行）。独立方法而非在池 lambda 里直引 metrics:
     *  metrics 是构造器赋值的 blank final, 字段初始化器 lambda 直引触发定值分析报错。 */
    private void countOnDemand(String result) {
        metrics.increment("vision.ondemand", "result", result);
    }

    /** on_publish 同步裁决: 流须有对应活跃会话（串流/伪造第二道闸, docs/04 §1） */
    public boolean onPublish(String app, String stream) {
        countHook("on_publish");
        if (!"rtp".equals(app)) {
            return false;
        }
        return sessions.hasActiveStream(stream);
    }

    /** on_play 同步裁决: 播放签名校验（防盗链）。
     *  F2 修复: 真实 ZLM 的 params 是 URL 查询串字符串("sign=..&expire=.."), 也兼容 Map 形状。
     *  B6 F1: 按 hook 报文 mediaServerId 定位节点取密钥——每节点签名密钥独立（docs/02 §2.3）,
     *  流在哪个节点就须用哪个节点的密钥; hook 未带 serverId（理论不可达）回落首个在线节点。 */
    public boolean onPlay(String app, String stream, Object params, String mediaServerId) {
        countHook("on_play");
        GbMediaServerEntity node = null;
        if (StringUtils.hasText(mediaServerId)) {
            node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                    .eq(GbMediaServerEntity::getServerId, mediaServerId).last("LIMIT 1"));
        }
        if (node == null) {
            // 兜底: hook 未带 serverId 时按历史口径任一在线节点（多节点下不应发生, 日志观测）
            node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                    .eq(GbMediaServerEntity::getStatus, 1).last("LIMIT 1"));
        }
        if (node == null) {
            return false;
        }
        String[] se = parseParams(params);
        boolean ok = urlService.verify(stream, se[0], se[1], aes.decrypt(node.getSignSecret()));
        if (!ok) {
            log.warn("on_play 鉴权失败: stream={} sign={}", stream, se[0]);
        }
        return ok;
    }

    /** 解析 on_play params → {sign, expire}; 兼容查询串字符串与 Map 两种形状（包私有便于单测） */
    static String[] parseParams(Object params) {
        if (params instanceof Map<?, ?> map) {
            return new String[]{str(map.get("sign")), str(map.get("expire"))};
        }
        String sign = null;
        String expire = null;
        if (params != null) {
            for (String kv : params.toString().split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String k = kv.substring(0, eq).trim();
                    String v = urlDecode(kv.substring(eq + 1).trim());
                    if ("sign".equals(k)) { sign = v; }
                    else if ("expire".equals(k)) { expire = v; }
                }
            }
        }
        return new String[]{sign, expire};
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    /** hook 报文取字符串字段（与 MediaHookController 同口径） */
    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * on_stream_not_found（M3-B7, docs/06 §3.1）: 播放器拉的流不存在 → 平台按需把流建起来。
     *
     * <p>ZLM 语义（已核实）: 响应的 code <b>不参与裁决</b>——ZLM 把播放器请求挂起至
     * maxStreamWaitMS（默认 15s）, 期内该流注册成功即起播, 超时才回"流不存在"。
     * 故本方法只做便宜校验后立即返回, 建流交独立执行器异步完成（见 onDemandPool 注释）。</p>
     *
     * <p>准入两道: ①只处理 app=rtp 且 streamId 以 gb_play_ 开头的实况流（回放 gb_playback_*
     * 前缀不同天然不匹配——其 streamId 每次唯一, 凭 streamId 无法复原回放窗口）;
     * ②params 携带的播放签名按 hook 报文节点的密钥验签。建流是消耗资源的动作, 未带参数或验签
     * 不过一律不建（不放松: 播放器最终仍会在流起播时的 on_play 被同一签名拦下）。</p>
     *
     * <p>去重: 播放器重试/多播放器同时拉同一不存在的流会连发 hook, 10s 窗口内只建一次;
     * 重复的 hook 直接返回不影响 ZLM（它仍在等待, 首轮建流成功即全量起播）。</p>
     */
    public void streamNotFound(String app, String stream, Object params, String mediaServerId) {
        countHook("on_stream_not_found");
        if (!media.isOnDemandEnabled()) {
            log.debug("按需点播已关闭(vision.media.on-demand-enabled=false), 忽略: stream={}", stream);
            return;
        }
        if (!"rtp".equals(app) || stream == null
                || !stream.startsWith(MediaSessionService.LIVE_STREAM_PREFIX)
                || stream.length() == MediaSessionService.LIVE_STREAM_PREFIX.length()) {
            return;
        }
        String[] se = parseParams(params);
        if (!onDemandSignValid(stream, se, mediaServerId)) {
            log.warn("on_stream_not_found 验签失败, 不建流: stream={} node={}", stream, mediaServerId);
            return;
        }
        // setIfAbsent(value, ttl): 原子「不存在才写」+TTL, 替代已 deprecated 的 trySet(ttl, unit)
        // （3.45.1 的 RBucket 无 trySet(V, Duration) 重载, Duration 版原子语义即 setIfAbsent）
        if (!redisson.<String>getBucket(ON_DEMAND_KEY + stream)
                .setIfAbsent("1", Duration.ofSeconds(ON_DEMAND_DEDUP_SECONDS))) {
            countOnDemand("dedup");
            log.debug("on_stream_not_found 去重命中(建流在途), 忽略: stream={}", stream);
            return;
        }
        int profile = media.getOnDemandProfile();
        onDemandPool.submit(() -> {
            try {
                if (haPeer.isLocal()) {
                    sessions.playOnDemand(stream, mediaServerId, profile);
                    countOnDemand("ok");
                } else {
                    // 备台无 SIP 栈: 请活跃实例代建（建流落在请求节点上, 与谁执行无关）
                    boolean ok = haPeer.requestOnDemand(stream, mediaServerId, profile);
                    countOnDemand(ok ? "ok" : "fail");
                }
            } catch (Exception e) {
                countOnDemand("fail");
                log.warn("按需建流失败: stream={} node={} err={}", stream, mediaServerId, e.getMessage());
            }
        });
    }

    /**
     * 按需建流准入验签: sign/expire 与节点密钥比对（B6 F1 同口径——流在哪个节点就用哪个节点的密钥）。
     * 节点未知（hook 未带 mediaServerId）时遍历全部节点密钥兜底；缺 sign/expire 直接拒绝。
     */
    private boolean onDemandSignValid(String stream, String[] se, String mediaServerId) {
        if (se[0] == null || se[1] == null) {
            return false;
        }
        GbMediaServerEntity node = selectByServerId(mediaServerId);
        if (node != null) {
            return urlService.verify(stream, se[0], se[1], aes.decrypt(node.getSignSecret()));
        }
        for (GbMediaServerEntity n : serverMapper.selectList(new LambdaQueryWrapper<GbMediaServerEntity>())) {
            if (n.getSignSecret() != null && !n.getSignSecret().isBlank()
                    && urlService.verify(stream, se[0], se[1], aes.decrypt(n.getSignSecret()))) {
                return true;
            }
        }
        return false;
    }

    /** on_stream_changed（异步）。videoCodec 从 hook body 的 tracks 提取（B5: 前端按编码选播放路径） */
    public void streamChanged(String streamId, boolean regist, String videoCodec) {
        countHook("on_stream_changed");
        log.debug("on_stream_changed: stream={} regist={} hookTracksCodec={}", streamId, regist, videoCodec);
        pool.submit(() -> {
            try {
                sessions.onStreamChanged(streamId, regist, videoCodec);
            } catch (Exception e) {
                log.error("on_stream_changed 处理异常: stream={}", streamId, e);
            }
            // 流就绪(注册)后按录像计划触发云端录制。hook 串行池保证先完成会话状态机流转
            // (INVITING→STREAMING), 再判定计划并调 ZLM startRecord; 录制失败不影响会话。
            if (regist) {
                try {
                    cloudRecords.onStreamReady(streamId);
                } catch (Exception e) {
                    log.warn("按计划触发云端录像异常(忽略): stream={} err={}", streamId, e.getMessage());
                }
                // M3-B7 阶段2: 点播自动抓图。与云端录像触发同形制——平级 try/catch, 失败不影响会话。
                // SnapshotService.onStreamReady 守卫（开关/autoOnPlay/前缀）后向独立池投递延迟任务,
                // 不占 hook 串行池
                try {
                    snapshots.onStreamReady(streamId);
                } catch (Exception e) {
                    log.warn("点播自动抓图触发失败(忽略): stream={} err={}", streamId, e.getMessage());
                }
            } else {
                // B3 P1: 流注销时清掉该流的 startRecord 去重条目（recordAskAt）,
                // 防"关流→同 streamId 再点播"时旧时间戳压制新会话的开录判定
                try {
                    cloudRecords.onStreamClosed(streamId);
                } catch (Exception e) {
                    log.warn("清理云端录像去重条目异常(忽略): stream={} err={}", streamId, e.getMessage());
                }
            }
        });
    }

    /**
     * on_record_mp4（docs/04 §1, docs/09 M2 #3）: MP4 分片落盘完成 → 归因入库 gb_cloud_record。
     * body 字段名以 ZLM 官方文档为准（mediaServerId/app/stream/file_name/file_path/file_size/
     * start_time(秒级时间戳)/time_len(秒)）; 异步执行, 对回复不敏感。
     */
    public void recordMp4(Map<String, Object> body) {
        countHook("on_record_mp4");
        pool.submit(() -> {
            try {
                CloudRecordService.HookMp4Chunk parsed = parseMp4Body(body);
                if (parsed == null) {
                    return;
                }
                // server_id 从 body.mediaServerId 映射节点表（未命中时保留原值, 归因层回落会话承载节点）
                GbMediaServerEntity node = selectByServerId(parsed.mediaServerId());
                if (node != null) {
                    parsed = parsed.withServerId(node.getServerId());
                }
                cloudRecords.saveFromHook(parsed);
            } catch (Exception e) {
                log.error("on_record_mp4 入库异常: stream={}", str(body, "stream"), e);
            }
        });
    }

    /**
     * on_record_mp4 报文归一化（包私有便于单测）: file_path 兼容 ZLM 部分版本的 filepath 拼写;
     * 时长兼容 time_len(文档值)/duration。缺失字段安全兜底, 不抛异常（分片宁缺勿炸 hook 线程）。
     */
    static CloudRecordService.HookMp4Chunk parseMp4Body(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        String filePath = firstNonBlank(str(body, "file_path"), str(body, "filepath"));
        Double timeLen = asDouble(body.get("time_len"));
        if (timeLen == null) {
            timeLen = asDouble(body.get("duration"));
        }
        return new CloudRecordService.HookMp4Chunk(
                str(body, "stream"),
                str(body, "app"),
                str(body, "mediaServerId"),
                filePath,
                firstNonBlank(str(body, "file_name"), str(body, "fileName")),
                asLong(body.get("file_size")),
                timeLen,
                asLong(body.get("start_time")));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** on_stream_none_reader（异步自动关流） */
    public void noneReader(String streamId) {
        countHook("on_stream_none_reader");
        pool.submit(() -> {
            try {
                sessions.onNoneReader(streamId);
            } catch (Exception e) {
                log.error("on_stream_none_reader 处理异常: stream={}", streamId, e);
            }
        });
    }

    /** on_rtp_server_timeout（异步清理孤儿会话） */
    public void rtpServerTimeout(String streamId) {
        countHook("on_rtp_server_timeout");
        pool.submit(() -> {
            try {
                sessions.onRtpServerTimeout(streamId, null);
            } catch (Exception e) {
                log.error("on_rtp_server_timeout 处理异常: stream={}", streamId, e);
            }
        });
    }

    /**
     * on_send_rtp_stopped（M3-B2 转推兜底, 异步）: ZLM 停止发送 RTP（TCP 断链/发送异常时 ZLM 自停并回调;
     * 正常 stopSendRtp 亦触发）→ 级联侧按 stream+ssrc 回收在途转推。对讲源流停止由 none_reader 路由兜底。
     * 正常 BYE 路径上下文已被 onUpperBye 移除, 匹配不了即忽略（幂等）。
     */
    public void sendRtpStopped(String app, String streamId, String ssrc) {
        countHook("on_send_rtp_stopped");
        if (!"rtp".equals(app)) {
            return;
        }
        pool.submit(() -> {
            try {
                cascadeInvites.onSendRtpStopped(streamId, ssrc);
            } catch (Exception e) {
                log.error("on_send_rtp_stopped 处理异常: stream={} ssrc={}", streamId, ssrc, e);
            }
        });
    }
}
