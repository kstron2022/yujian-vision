package com.yujian.vision.service.device.service;

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
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.service.system.service.NoticeService;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 目录同步（docs/05 §3）：Catalog 查询 → 多包按 SN 聚合 → SumNum 满足或超时收敛入库。
 * v1 单实例：聚合会话在内存；集群化(M3)时迁移 Redis（docs/02 §3）。
 */
@Slf4j
@Component
public class CatalogSyncService {

    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(15);

    private final ManScdpCodec codec;
    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;
    private final StreamEventPublisher eventPublisher;
    private final NoticeService noticeService;
    private final Map<String, CatalogSyncSession> sessions = new ConcurrentHashMap<>();

    public CatalogSyncService(ManScdpCodec codec, SipTransportProvider transport,
            GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            GbPlatformProperties platform, StreamEventPublisher eventPublisher,
            NoticeService noticeService) {
        this.codec = codec;
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.platform = platform;
        this.eventPublisher = eventPublisher;
        this.noticeService = noticeService;
    }

    /** 目录同步进度（docs/03 §3 catalog/progress; 无会话=未在同步） */
    public record SyncProgress(boolean running, int total, int synced) {
    }

    public SyncProgress progress(String deviceId) {
        CatalogSyncSession s = sessions.get(deviceId);
        if (s == null) {
            return new SyncProgress(false, 0, 0);
        }
        return new SyncProgress(true, Math.max(s.total, 0), s.items.size());
    }

    /** 向设备发起目录同步（接口层触发, 进行中重复触发抛 CATALOG_SYNC_RUNNING） */
    public void sync(String deviceId) {
        GbDeviceEntity device = selectDevice(deviceId);
        if (device == null || device.getOnline() == null || device.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线或未注册: " + deviceId);
        }
        if (sessions.containsKey(deviceId)) {
            throw new BizException(ErrorCode.CATALOG_SYNC_RUNNING);
        }
        long sn = SipIds.nextSn();
        sessions.put(deviceId, new CatalogSyncSession(device.getId()));
        // 外呼栈: 按设备注册传输协议(UDP/TCP)选择, 未启用该协议栈时回退平台默认栈
        String stackId = transport.stackIdForTransport(device.getTransport());
        if (stackId == null) {
            stackId = platform.getStackId();
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(stackId)
                .method("MESSAGE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(deviceId + "@" + platform.getDomain())
                .sn(sn)
                .targetHost(device.getIp())
                .targetPort(device.getPort() != null ? device.getPort() : 5060)
                .body(codec.buildCatalogQuery(deviceId, sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .build();
        transport.send(ctx);
        log.info("目录同步已发起: device={} sn={}", deviceId, sn);
    }

    /** 处理设备上报的目录数据（SIP MESSAGE 请求, 报文根为 RESPONSE） */
    public void onResponse(SipEvent event, ManScdpMessage message) {
        String deviceId = message.getDeviceId() != null ? message.getDeviceId() : event.getFromUser();
        CatalogSyncSession session = sessions.get(deviceId);
        if (session == null || session.done) {
            log.debug("无进行中的目录同步, 丢弃: device={}", deviceId);
            return;
        }
        if (message.getSumNum() != null) {
            session.total = message.getSumNum();
        }
        if (message.getItems() != null) {
            session.items.addAll(message.getItems());
        }
        log.debug("目录数据: device={} 累计 {}/{}", deviceId, session.items.size(), session.total);
        eventPublisher.catalogSyncProgress(deviceId, Math.max(session.total, 0), session.items.size());
        if (session.total >= 0 && session.items.size() >= session.total) {
            converge(deviceId);
        }
    }

    /** 超时收敛兜底（SumNum 未到齐的设备, 15s 后按已收数据收敛） */
    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        LocalDateTime deadline = LocalDateTime.now().minus(SYNC_TIMEOUT);
        for (Map.Entry<String, CatalogSyncSession> e : sessions.entrySet()) {
            CatalogSyncSession s = e.getValue();
            if (s.done || !s.startedAt.isBefore(deadline)) {
                continue;
            }
            if (s.total < 0 && s.items.isEmpty()) {
                // 一包未收(设备不应答/丢包): 不应答≠空目录, 中止同步并保留存量目录, 防止误删全部通道
                sessions.remove(e.getKey());
                s.done = true;
                log.warn("目录同步超时且未收到任何数据, 已中止并保留存量目录: device={}", e.getKey());
                eventPublisher.catalogSyncProgress(e.getKey(), 0, 0);
                // 消息中心 B1-c: 无应答≠空目录, 属需人工关注的失败, 站内通知（publish 内部吞异常）
                noticeService.publish("CATALOG_SYNC_FAIL", "目录同步失败",
                        "设备 " + e.getKey() + " 目录查询无应答，已中止本次同步并保留存量目录", 2, e.getKey());
                continue;
            }
            log.warn("目录同步超时收敛: device={} 已收{}条", e.getKey(), s.items.size());
            converge(e.getKey());
        }
    }

    /** 收敛入库：与现有目录全量比对 → 新增/更新/软删缺失通道 */
    private void converge(String deviceId) {
        CatalogSyncSession session = sessions.remove(deviceId);
        if (session == null || session.done) {
            return;
        }
        session.done = true;
        try {
            List<GbDeviceChannelEntity> existing = channelMapper.selectList(
                    new LambdaQueryWrapper<GbDeviceChannelEntity>()
                            .eq(GbDeviceChannelEntity::getDeviceDbId, session.deviceDbId)
                            .isNull(GbDeviceChannelEntity::getDeletedTime));
            Map<String, GbDeviceChannelEntity> byCode = new HashMap<>();
            for (GbDeviceChannelEntity ch : existing) {
                byCode.put(ch.getChannelId(), ch);
            }
            LocalDateTime now = LocalDateTime.now();
            int inserted = 0;
            int updated = 0;
            Set<String> seen = new HashSet<>();
            for (Map<String, String> item : session.items) {
                String channelId = item.get("DeviceID");
                if (channelId == null || channelId.isBlank() || !seen.add(channelId)) {
                    continue;
                }
                GbDeviceChannelEntity ch = byCode.get(channelId);
                boolean isNew = ch == null;
                if (ch == null) {
                    ch = new GbDeviceChannelEntity();
                    ch.setDeviceDbId(session.deviceDbId);
                    ch.setChannelId(channelId);
                    ch.setCreateTime(now);
                }
                applyItemFields(ch, item);
                if (isNew) {
                    channelMapper.insert(ch);
                    inserted++;
                } else {
                    channelMapper.updateById(ch);
                    updated++;
                }
            }
            int removed = 0;
            for (GbDeviceChannelEntity ch : existing) {
                if (!seen.contains(ch.getChannelId()) && ch.getDeletedTime() == null) {
                    GbDeviceChannelEntity del = new GbDeviceChannelEntity();
                    del.setId(ch.getId());
                    del.setDeletedTime(now);
                    channelMapper.updateById(del);
                    removed++;
                }
            }
            log.info("目录同步收敛完成: device={} 总数{} 入库{} 更新{} 下线{}",
                    deviceId, session.total, inserted, updated, removed);
            eventPublisher.channelChange(deviceId);
        } catch (Exception e) {
            log.error("目录收敛异常: device={}", deviceId, e);
        }
    }

    /**
     * 目录条目 → 通道实体字段映射（目录同步收敛与 NOTIFY 增量入库共用, M3-B3 阶段4 抽取）。
     * 语义: 报文缺省字段保留实体现值(getOrDefault 容错), Status 缺失按 OFF。
     * 只做内存赋值, insert/updateById 由调用方决定; catalogTime 由本方法统一刷新。
     */
    static void applyItemFields(GbDeviceChannelEntity ch, Map<String, String> item) {
        ch.setName(item.getOrDefault("Name", ch.getName() == null ? ch.getChannelId() : ch.getName()));
        ch.setCustomName(ch.getCustomName() == null ? "" : ch.getCustomName());
        ch.setManufacturer(item.getOrDefault("Manufacturer", ch.getManufacturer()));
        ch.setModel(item.getOrDefault("Model", ch.getModel()));
        ch.setOwner(item.getOrDefault("Owner", ch.getOwner()));
        ch.setCivilCode(item.getOrDefault("CivilCode", ch.getCivilCode()));
        ch.setAddress(item.getOrDefault("Address", ch.getAddress()));
        ch.setParental("1".equals(item.get("Parental")) ? 1 : 0);
        ch.setParentId(item.getOrDefault("ParentID", ch.getParentId()));
        ch.setBusinessGroupId(item.getOrDefault("BusinessGroupID", ch.getBusinessGroupId()));
        ch.setStatus(ManScdpConstants.STATUS_ON.equalsIgnoreCase(item.get("Status")) ? 1 : 0);
        ch.setRegisterWay(parseInt(item.get("RegisterWay"), ch.getRegisterWay()));
        ch.setSecrecy(parseInt(item.get("Secrecy"), ch.getSecrecy()));
        ch.setIp(item.getOrDefault("IPAddress", ch.getIp()));
        ch.setPort(parseInt(item.get("Port"), ch.getPort()));
        ch.setLongitude(parseDecimal(item.get("Longitude"), ch.getLongitude()));
        ch.setLatitude(parseDecimal(item.get("Latitude"), ch.getLatitude()));
        // 云台类型防覆盖: 部分平台(如 LiveGBS)目录不报 PTZType 或恒报 0, 而该字段支持人工修正
        // (通道信息 patch, M5-B2)。仅真实上报 >0 才采用, 缺省/0 保留现值——
        // 防止人工补录的类型被下一次目录收敛/增量 NOTIFY 冲掉。
        int reportedPtz = parseInt(item.get("PTZType"), 0);
        if (reportedPtz > 0) {
            ch.setPtzType(reportedPtz);
        }
        ch.setMobile(parseTruth(item.get("Mobile"), ch.getMobile()));
        ch.setSubCount(parseInt(item.get("SubCount"), ch.getSubCount()));
        ch.setCatalogTime(LocalDateTime.now());
    }

    private GbDeviceEntity selectDevice(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    private static Integer parseInt(String v, Integer dft) {
        if (v == null) {
            return dft;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static BigDecimal parseDecimal(String v, BigDecimal dft) {
        if (v == null) {
            return dft;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static Integer parseTruth(String v, Integer dft) {
        if (v == null) {
            return dft;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) ? 1 : 0;
    }

    /** 目录同步聚合会话（内存态, 按设备键控） */
    private static final class CatalogSyncSession {
        final Long deviceDbId;
        final LocalDateTime startedAt = LocalDateTime.now();
        final List<Map<String, String>> items = new ArrayList<>();
        int total = -1;
        volatile boolean done;

        CatalogSyncSession(Long deviceDbId) {
            this.deviceDbId = deviceDbId;
        }
    }
}
