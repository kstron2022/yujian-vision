package com.yujian.vision.service.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 录像检索（docs/05 §3 / docs/03 §6）：RecordInfo 查询 → 按 SN 聚合多包应答
 * → SumNum 满足或超时收敛 → 间隙合并去重。
 * v1 内存会话/缓存，单实例；集群化(M3) 迁 Redis（docs/02 §3）。
 */
@Slf4j
@Component
public class RecordQueryService {

    /** 单次录像查询等待上限（多包应答按 SN 聚合的窗口） */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);

    /** 单次查询跨度上限（防压垮 NVR 录像检索） */
    private static final int MAX_RANGE_DAYS = 31;

    /** 结果缓存 TTL（同窗口 60s 内重复查询直接命中，不重复外呼） */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 结果缓存容量上限（超限整体清空, v1 简单策略） */
    private static final int CACHE_MAX = 256;

    /** 录像查询报文时间格式（GB/T 28181-2016） */
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** API 输出/日志时间格式 */
    private static final DateTimeFormatter API_FMT = RecordSegments.API_FMT;

    private final ManScdpCodec codec;
    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;
    /** ONVIF 检索分流（M4-B3）: Search Service + 整窗单段降级 */
    private final OnvifRecordSearchService onvifRecordSearch;
    /** sn → 会话（按 SN 聚合多包应答） */
    private final Map<Long, RecordSession> sessions = new ConcurrentHashMap<>();
    /** 查询结果缓存: channelDbId|start|end → 条目 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 查询失败快速通道: deviceDbId → 解禁时间戳(epoch ms)。设备超时后 60s 内重试直接秒回提示, 不再干等 15s */
    private static final long FAIL_RETRY_COOLDOWN_MS = 60_000L;
    private final Map<Long, Long> deviceFailUntil = new ConcurrentHashMap<>();

    public RecordQueryService(ManScdpCodec codec, SipTransportProvider transport,
            GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            GbPlatformProperties platform, OnvifRecordSearchService onvifRecordSearch) {
        this.codec = codec;
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.platform = platform;
        this.onvifRecordSearch = onvifRecordSearch;
    }

    /**
     * 通道录像检索（docs/03 §6）：校验 → 缓存 → 设备在线校验 → 发 RecordInfo 查询
     * → 按 SN 等待收敛（15s 超时）→ normalize + 缓存。空结果正常返回（不抛 1008）。
     */
    public List<RecordSegment> query(Long channelDbId, LocalDateTime start, LocalDateTime end) {
        // 1. 参数/范围校验（防压垮 NVR）
        if (channelDbId == null || start == null || end == null || !start.isBefore(end)
                || Duration.between(start, end).toDays() > MAX_RANGE_DAYS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间范围非法（起止倒置或超过31天）");
        }
        // 2. 缓存命中直接返回；过期随手清除
        String cacheKey = channelDbId + "|" + start + "|" + end;
        CacheEntry hit = cache.get(cacheKey);
        if (hit != null) {
            if (System.currentTimeMillis() - hit.storedAt() < CACHE_TTL_MS) {
                return hit.segments();
            }
            cache.remove(cacheKey);
        }
        // 3. 通道校验
        GbDeviceChannelEntity channel = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getId, channelDbId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
        if (channel == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道不存在: " + channelDbId);
        }
        // 4. 设备校验: channel.deviceDbId 是 gb_device 主键 id（Long, 见 CatalogSyncService.converge）→ selectById
        GbDeviceEntity device = deviceMapper.selectById(channel.getDeviceDbId());
        if (device == null || device.getDeletedTime() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备不存在: " + channel.getDeviceDbId());
        }
        if (device.getOnline() == null || device.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线或未注册: " + device.getDeviceId());
        }
        // M4-B3 分流(docs/13 §5.3): ONVIF 通道走 Search Service;
        // 检索失败整窗单段降级(按时段起流观看); 降级产物不写缓存防 60s 内重试被合成段挡住
        if (DeviceProtocol.of(device) == DeviceProtocol.ONVIF) {
            OnvifRecordSearchService.SearchOutcome outcome =
                    onvifRecordSearch.queryOutcome(device, channel, start, end);
            if (!outcome.degraded()) {
                if (cache.size() > CACHE_MAX) {
                    cache.clear();
                }
                cache.put(cacheKey, new CacheEntry(outcome.segments(), System.currentTimeMillis()));
            }
            return outcome.segments();
        }
        // 失败快速通道: 设备刚超时过(不支持录像检索/无存储的典型表现是沉默), 冷却期内秒回提示不再干等
        Long failUntil = deviceFailUntil.get(device.getId());
        if (failUntil != null && System.currentTimeMillis() < failUntil) {
            throw new BizException(ErrorCode.RECORD_QUERY_TIMEOUT,
                    "设备刚刚未响应录像查询（可能不支持录像检索或无存储介质），约 "
                            + Math.max(1, (failUntil - System.currentTimeMillis()) / 1000) + " 秒后可重试");
        }
        // 5. 建会话（先 put 再发, 防快速应答漏收）并发起 RecordInfo 查询（发往通道编码）
        long sn = SipIds.nextSn();
        RecordSession session = new RecordSession(channel.getChannelId());
        sessions.put(sn, session);
        // 外呼栈: 按设备注册传输协议(UDP/TCP)选择, 未启用该协议栈时回退平台默认栈
        String stackId = transport.stackIdForTransport(device.getTransport());
        if (stackId == null) {
            stackId = platform.getStackId();
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(stackId)
                .method("MESSAGE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(channel.getChannelId() + "@" + platform.getDomain())
                .sn(sn)
                .targetHost(device.getIp())
                .targetPort(device.getPort() != null ? device.getPort() : 5060)
                .body(codec.buildRecordInfoQuery(channel.getChannelId(), sn,
                        start.format(ISO_FMT), end.format(ISO_FMT)))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .build();
        transport.send(ctx);
        log.info("录像查询已发起: channel={} 范围[{} ~ {}] sn={}", channel.getChannelId(),
                start.format(API_FMT), end.format(API_FMT), sn);
        // 6. 阻塞等待多包聚合完成（onResponse complete / sweep 兜底）
        List<RecordSegment> segments;
        try {
            segments = session.future.get(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            sessions.remove(sn);
            deviceFailUntil.put(device.getId(), System.currentTimeMillis() + FAIL_RETRY_COOLDOWN_MS);
            throw new BizException(ErrorCode.RECORD_QUERY_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sessions.remove(sn);
            deviceFailUntil.put(device.getId(), System.currentTimeMillis() + FAIL_RETRY_COOLDOWN_MS);
            throw new BizException(ErrorCode.RECORD_QUERY_TIMEOUT);
        } finally {
            sessions.remove(sn);
        }
        deviceFailUntil.remove(device.getId());
        // 7. normalize + 写缓存（空结果是正常返回, 不抛 1008）
        List<RecordSegment> result = RecordSegments.normalize(segments, start, end);
        if (cache.size() > CACHE_MAX) {
            cache.clear();
        }
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * RecordInfo 应答处理（SipEventRouter 分发, 按 SN 聚合多包）：
     * items 解析进会话，SumNum 到齐即 complete；SN 无会话则丢弃（超时后迟到的应答）。
     */
    public void onResponse(SipEvent event, ManScdpMessage message) {
        if (message.getSn() == null) {
            return;
        }
        RecordSession session = sessions.get(message.getSn());
        if (session == null) {
            log.debug("无进行中的录像查询, 丢弃: sn={} device={}", message.getSn(), message.getDeviceId());
            return;
        }
        synchronized (session) {
            // items 可能为 null（无录像或部分厂商单包即 SumNum=0）
            List<Map<String, String>> items = message.getItems() == null ? List.of() : message.getItems();
            session.items.addAll(RecordSegments.parse(items, session.channelId));
            if (message.getSumNum() != null) {
                session.total = message.getSumNum();
            }
            if (session.total >= 0 && session.items.size() >= session.total && !session.future.isDone()) {
                session.future.complete(new ArrayList<>(session.items));
            }
        }
    }

    /**
     * 超时兜底（SumNum 未到齐且超窗 → completeExceptionally(1010) + 会话出清）
     */
    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        LocalDateTime deadline = LocalDateTime.now().minus(QUERY_TIMEOUT);
        for (Map.Entry<Long, RecordSession> e : sessions.entrySet()) {
            RecordSession s = e.getValue();
            if (s.future.isDone() || s.startedAt.isAfter(deadline)) {
                continue;
            }
            sessions.remove(e.getKey());
            s.future.completeExceptionally(new BizException(ErrorCode.RECORD_QUERY_TIMEOUT));
            log.warn("录像查询超时: channel={} sn={} 已收{}条", s.channelId, e.getKey(), s.items.size());
        }
    }

    /** 录像查询聚合会话（内存态） */
    private static final class RecordSession {
        final String channelId;
        final LocalDateTime startedAt = LocalDateTime.now();
        final List<RecordSegment> items = new ArrayList<>();
        int total = -1;
        final CompletableFuture<List<RecordSegment>> future = new CompletableFuture<>();

        RecordSession(String channelId) {
            this.channelId = channelId;
        }
    }

    /** 查询结果缓存条目（TTL 判定用存储时间戳） */
    private record CacheEntry(List<RecordSegment> segments, long storedAt) {
    }
}
