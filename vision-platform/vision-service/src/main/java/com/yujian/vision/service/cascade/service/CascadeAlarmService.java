package com.yujian.vision.service.cascade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.service.alarm.entity.GbAlarmEvent;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 上级订阅制报警上报（本平台作为下级, M3-B4 阶段2, docs/08 §2.5）：
 * ① 上级发入站 SUBSCRIBE(Event: Alarm)（RFC 3265 订阅方为上级）→ 校验来源为在册启用上级后
 * 建订阅对话（200 OK + To tag + 授予 Expires 回显）；非上级/其它事件包仍由路由层回 501 不建对话。
 * ② 设备/下级报警入库（AlarmService.ingest）后实时扇出 NOTIFY/Alarm 到全部在期订阅对话——
 * 共享过滤: 报警源通道须为该上级的启用共享通道（catalog_mode=2 全通道例外）; 虚拟编码替换与
 * 目录上报同规则（virtual_device_id 非空则 DeviceID 换虚拟编码）。设备级报警（无通道归属）不上报。
 * ③ 会话纯内存态（风格同 CascadeUpperManager/CatalogSubscriptionService, 重启后上级重订阅即恢复）;
 * 到期 tick 清理; 上级判死/配置停用（CascadeUpperManager toBackoff/dropSession）即清订阅。
 * NOTIFY 以新事务携带对话四元组发送（GB28181 无 Route 集, 与 AlarmSubscriptionService 续订同策略）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeAlarmService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final GbCascadeUpperMapper upperMapper;
    private final GbCascadeSharedChannelMapper sharedMapper;
    private final SipTransportProvider transport;
    private final GbPlatformProperties platform;
    private final ManScdpCodec codec;
    private final CascadeOutboundCodec outboundCodec;

    /** 订阅请求未带 Expires 时的授予秒数（vision.cascade.alarm-subscribe-expires, 默认 1h） */
    @Value("${vision.cascade.alarm-subscribe-expires:3600}")
    private int defaultExpires;

    /** 订阅会话: 上级国标编码 → 会话（内存态, 上级重订阅即恢复） */
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 入站订阅

    /**
     * 入站 SUBSCRIBE 处理（SipEventRouter 接线）：仅 Event: Alarm 且 From 为在册启用上级时受理
     * （200 OK + Expires 回显, 建对话返回 true）; 其余情况返回 false 由路由层回 501 不建对话。
     * Expires=0 为退订; 重订阅（同对话 To 带 tag）复用本端 tag 刷新会话, 新 Call-ID 置换旧会话。
     */
    public boolean onUpperSubscribe(SipEvent event) {
        if (event.getEvent() == null || !"Alarm".equalsIgnoreCase(event.getEvent().trim())) {
            return false;
        }
        GbCascadeUpperEntity u = findEnabledUpper(event.getFromUser());
        if (u == null) {
            return false;
        }
        // 对话内重订阅时请求 To 已带本端 tag: 200 必须回同 tag 才落在同一对话
        String localTag = StringUtils.hasText(event.getToTag())
                ? event.getToTag() : SipIds.newTag();
        int requested = event.getExpires() != null && event.getExpires() >= 0
                ? event.getExpires() : defaultExpires;
        String upperId = event.getFromUser();
        if (requested == 0) {
            Sub removed = subs.remove(upperId);
            if (removed != null) {
                log.info("上级退订报警: upper={} callId={}", upperId, removed.callId);
            }
            respond(event, localTag, 0);
            return true;
        }
        Sub s = new Sub();
        s.upperId = upperId;
        s.upperDbId = u.getId();
        s.callId = event.getCallId();
        s.upperFromTag = event.getFromTag();
        s.localToTag = localTag;
        s.stackId = event.getStackId();
        // NOTIFY 目标: 优先订阅请求 Contact（设备真实联系地址语义同注册）, 缺省回退上级配置地址
        s.targetHost = StringUtils.hasText(event.getContactHost())
                ? event.getContactHost() : u.getUpperIp();
        s.targetPort = event.getContactPort() != null ? event.getContactPort() : u.getUpperPort();
        s.expireAt = LocalDateTime.now().plusSeconds(requested);
        subs.put(upperId, s);
        respond(event, localTag, requested);
        log.info("上级订阅报警: upper={} callId={} 授予={}s 目标={}:{}",
                upperId, s.callId, requested, s.targetHost, s.targetPort);
        return true;
    }

    /** 200 OK 应答（To tag 显式指定建对话, Expires 回显授予值） */
    private void respond(SipEvent event, String localTag, int expires) {
        transport.sendResponse(event, 200,
                Map.of(SipTransportProvider.TO_TAG_HEADER_KEY, localTag,
                        "Expires", String.valueOf(expires)));
    }

    // ---------------------------------------------------------------- 报警扇出

    /**
     * 报警入库后扇出（AlarmService 旁路调用, 异常自吞不影响入库）：
     * 逐订阅上级做共享过滤（catalog_mode=1 须命中启用共享通道, mode=2 全通道）+ 虚拟编码替换,
     * NOTIFY 新事务携带对话四元组（Call-ID/双 tag）发送; 单上级失败隔离不影响其它上级。
     */
    public void onAlarm(GbAlarmEvent alarm) {
        if (alarm == null || alarm.getChannelDbId() == null) {
            // 设备级报警无通道归属, 上级侧目录无此编码可订阅, 不上报（docs/08 §2.5 口径）
            if (alarm != null) {
                log.debug("设备级报警不上报上级: device={} (无通道归属)", alarm.getDeviceId());
            }
            return;
        }
        if (subs.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Sub s : subs.values()) {
            try {
                notifyUpper(s, alarm, now);
            } catch (Exception e) {
                log.warn("报警上报上级失败(隔离): upper={}", s.upperId, e);
            }
        }
    }

    private void notifyUpper(Sub s, GbAlarmEvent alarm, LocalDateTime now) {
        if (s.expireAt.isBefore(now)) {
            return;
        }
        GbCascadeUpperEntity u = upperMapper.selectById(s.upperDbId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            return;
        }
        List<GbCascadeSharedChannelEntity> shared = sharedMapper.selectList(
                new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                        .eq(GbCascadeSharedChannelEntity::getUpperId, u.getId())
                        .eq(GbCascadeSharedChannelEntity::getEnabled, 1));
        boolean catalogAll = u.getCatalogMode() != null && u.getCatalogMode() == 2;
        String virtual = null;
        boolean hit = false;
        for (GbCascadeSharedChannelEntity row : shared) {
            if (alarm.getChannelDbId().equals(row.getChannelDbId())) {
                hit = true;
                virtual = row.getVirtualDeviceId();
                break;
            }
        }
        if (!hit && !catalogAll) {
            log.debug("报警通道未共享给订阅上级, 跳过: upper={} channel={}",
                    s.upperId, alarm.getChannelId());
            return;
        }
        // 虚拟编码替换与目录上报同规则（docs/08 §2.2）
        String sourceId = StringUtils.hasText(virtual) ? virtual : alarm.getChannelId();
        // CSeq 取全局单调序号（SipIds AtomicLong）, 对话内天然递增, 无需会话内单独计数
        long sn = SipIds.nextSn();
        long remaining = Math.max(1, ChronoUnit.SECONDS.between(now, s.expireAt));
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(s.stackId)
                .method("NOTIFY")
                .from(u.getLocalDeviceId() + "@" + platform.getDomain())
                .to(u.getUpperDeviceId() + "@" + platform.getDomain())
                .callId(s.callId)
                .fromTag(s.localToTag)
                .toTag(s.upperFromTag)
                .sn(sn)
                .event("Alarm")
                .subscriptionState("active;expires=" + remaining)
                .body(outboundCodec.body(codec.buildAlarmNotify(sourceId, sn,
                        alarm.getAlarmTime() != null ? alarm.getAlarmTime().format(ISO_FMT) : null,
                        alarm.getAlarmType(), alarm.getAlarmPriority(), alarm.getAlarmMethod(),
                        alarm.getDescription())))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .contentCharset(outboundCodec.tuned() ? outboundCodec.charset() : null)
                .targetHost(s.targetHost)
                .targetPort(s.targetPort)
                .build();
        transport.send(ctx);
        log.info("报警已上报上级: upper={} source={} type={} priority={} callId={} sn={}",
                s.upperId, sourceId, alarm.getAlarmType(), alarm.getAlarmPriority(), s.callId, sn);
    }

    // ---------------------------------------------------------------- 生命周期

    /** 上级判死/配置停用钩子（CascadeUpperManager toBackoff/dropSession 接线）: 清其订阅会话 */
    public void onUpperGone(String upperDeviceId) {
        if (upperDeviceId != null) {
            subs.remove(upperDeviceId);
        }
    }

    /** 到期清理 tick（10s）: 过期订阅不再扇出（notifyUpper 双保险）, 此处移除防 Map 常驻 */
    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        subs.entrySet().removeIf(e -> e.getValue().expireAt.isBefore(now));
    }

    /** 在期订阅会话数（运维/测试观测用） */
    public int subCount() {
        return subs.size();
    }

    // ---------------------------------------------------------------- 内部

    private GbCascadeUpperEntity findEnabledUpper(String upperDeviceId) {
        if (!StringUtils.hasText(upperDeviceId)) {
            return null;
        }
        List<GbCascadeUpperEntity> list = upperMapper.selectList(
                new LambdaQueryWrapper<GbCascadeUpperEntity>()
                        .eq(GbCascadeUpperEntity::getUpperDeviceId, upperDeviceId)
                        .eq(GbCascadeUpperEntity::getStatus, 1));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 上级报警订阅会话（内存态） */
    private static final class Sub {
        String upperId;
        Long upperDbId;
        String callId;
        String upperFromTag;
        String localToTag;
        String stackId;
        String targetHost;
        Integer targetPort;
        LocalDateTime expireAt;
    }
}
