package com.yujian.vision.service.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报警订阅（M3-B4）：本平台作为订阅方，向全部在线设备(type=1/2)主动
 * SUBSCRIBE(Event: Alarm) 建立报警订阅对话，对端随后经 NOTIFY/Alarm 推送报警
 * （入库链路见 AlarmService.ingest, 路由见 SipEventRouter）。
 * 状态机结构与 CatalogSubscriptionService 同构：内存态（重启后由 tick 扫描 DB 重建）；
 * 在途无应答 15s 丢弃重建；到期 0.8× 同对话续订（以对端授予 Expires 为准）。
 * 仅作为订阅方发起 SUBSCRIBE, 不接收入站订阅（入站 501 桩保留）。
 */
@Slf4j
@Component
public class AlarmSubscriptionService {

    /** 事件包名（GB/T 28181 报警订阅） */
    static final String EVENT_ALARM = "Alarm";
    /** 订阅请求应答超时: 超 15s 未获 200 视为失败, 由 tick 丢弃重建 */
    private static final long RESPONSE_TIMEOUT_SECONDS = 15;
    /** 到期续订提前量: 到期前 20%（0.8×）发 re-SUBSCRIBE 刷新 */
    private static final double REFRESH_ADVANCE = 0.8;

    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbPlatformProperties platform;
    private final ManScdpCodec codec;
    /** SIP 主备（M3-B6 阶段2）: 备台身份停跑订阅扫描（SUBSCRIBE 需本进程 SIP 栈） */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;

    /** SUBSCRIBE Expires 秒数(默认 1h, 到期前 0.8× 自动续订, vision.alarm.subscribe-expires) */
    @Value("${vision.alarm.subscribe-expires:3600}")
    private int subscribeExpires;

    /** 订阅会话: 国标编码 → 会话（内存态, 重启失, tick 扫描在线设备重建） */
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    /** Call-ID → 国标编码（200 OK 应答按 callId 关联会话） */
    private final Map<String, String> callIdIndex = new ConcurrentHashMap<>();

    public AlarmSubscriptionService(SipTransportProvider transport, GbDeviceMapper deviceMapper,
            GbPlatformProperties platform, ManScdpCodec codec,
            com.yujian.vision.service.ha.HaRuntimeService ha) {
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.platform = platform;
        this.codec = codec;
        this.ha = ha;
    }

    // ---------------------------------------------------------------- 生命周期钩子

    /**
     * 设备上线钩子(注册成功/重新注册): 自动发起报警订阅（全部设备, 不限 type）。
     * 失败只记日志不阻塞注册(与 asyncCatalogSync 同策略)。
     */
    public void onDeviceOnline(GbDeviceEntity device) {
        if (device == null) {
            return;
        }
        try {
            trySubscribe(device);
        } catch (Exception e) {
            log.warn("报警订阅发起失败: device={}", device.getDeviceId(), e);
        }
    }

    /**
     * 设备离线/删除钩子: 清订阅会话; 重新上线由注册钩子或 tick 重建。
     */
    public void onDeviceOffline(String deviceId) {
        Sub s = subs.remove(deviceId);
        if (s != null) {
            callIdIndex.remove(s.callId);
        }
    }

    /** 设备删除钩子(语义同离线清理) */
    public void onDeviceDeleted(String deviceId) {
        onDeviceOffline(deviceId);
    }

    // ---------------------------------------------------------------- 订阅发送与刷新

    /**
     * 扫描式调度（单 tick 每 10s, 风格同 CatalogSubscriptionService）：
     * ① 扫描全部在线设备(type=1/2)补建缺失订阅（重启恢复 + 注册钩子漏网兜底）;
     * ② 在途无应答超 15s 的会话丢弃重建（防对话悬挂）;
     * ③ 已确认会话到期 0.8× 自动续订（同对话 re-SUBSCRIBE）。
     */
    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        if (!ha.sipGatesOpen()) {
            return;
        }
        List<GbDeviceEntity> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getOnline, 1)
                        .isNull(GbDeviceEntity::getDeletedTime));
        LocalDateTime now = LocalDateTime.now();
        for (GbDeviceEntity d : devices) {
            // M4-B2: ONVIF 设备无 GB 报警订阅语义(SUBSCRIBE 对相机侧无应答), 跳过防
            // 每 10s tick 循环重建噪音(242 实测每 30s 一轮「订阅应答超时, 丢弃重建」);
            // 残留在途会话由超时丢弃路径自然消化。
            // M5-B1: 改白名单口径——RTSP 拉流代理同样无 SIP 语义, 非 GB 一律跳过
            if (DeviceProtocol.of(d) != DeviceProtocol.GB) {
                continue;
            }
            if (!subs.containsKey(d.getDeviceId())) {
                // 逐设备隔离: 单台发送失败(TCP 连接拒绝等)不中断整轮巡检
                try {
                    trySubscribe(d);
                } catch (Exception e) {
                    log.warn("报警订阅发起失败: device={}", d.getDeviceId(), e);
                }
            }
        }
        for (Map.Entry<String, Sub> e : subs.entrySet()) {
            Sub s = e.getValue();
            if (!s.confirmed && s.sentAt.plusSeconds(RESPONSE_TIMEOUT_SECONDS).isBefore(now)) {
                log.info("订阅应答超时, 丢弃重建: device={} callId={}", e.getKey(), s.callId);
                callIdIndex.remove(s.callId);
                subs.remove(e.getKey());
                continue;
            }
            if (s.confirmed && now.isAfter(s.nextRefreshAt)
                    && s.sentAt.plusSeconds(RESPONSE_TIMEOUT_SECONDS).isBefore(now)) {
                sendRefresh(s);
            }
        }
    }

    /** 发起订阅(synchronized 防 tick 与注册钩子并发双发) */
    private synchronized void trySubscribe(GbDeviceEntity d) {
        Sub s = subs.get(d.getDeviceId());
        if (s != null) {
            boolean inFlight = !s.confirmed
                    && s.sentAt.plusSeconds(RESPONSE_TIMEOUT_SECONDS).isAfter(now());
            boolean healthy = s.confirmed && s.nextRefreshAt.isAfter(now());
            if (inFlight || healthy) {
                return;
            }
            callIdIndex.remove(s.callId);
        }
        long sn = SipIds.nextSn();
        Sub sub = new Sub(d.getDeviceId());
        sub.callId = SipIds.newCallId();
        sub.fromTag = SipIds.newTag();
        sub.sentAt = now();
        String stackId = transport.stackIdForTransport(d.getTransport());
        if (stackId == null) {
            stackId = platform.getStackId();
        }
        sub.stackId = stackId;
        sub.targetHost = d.getIp();
        sub.targetPort = d.getPort() != null ? d.getPort() : 5060;
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(stackId)
                .method("SUBSCRIBE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(d.getDeviceId() + "@" + platform.getDomain())
                .callId(sub.callId)
                .fromTag(sub.fromTag)
                .sn(sn)
                .expires(subscribeExpires)
                .event(EVENT_ALARM)
                .body(codec.buildAlarmQuery(d.getDeviceId(), sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .targetHost(sub.targetHost)
                .targetPort(sub.targetPort)
                .build();
        // 先登记再发送: 200 OK 可能先于 send 返回到达, 应答路由依赖两份索引
        subs.put(d.getDeviceId(), sub);
        callIdIndex.put(sub.callId, d.getDeviceId());
        transport.send(ctx);
        log.info("报警订阅已发起: device={} callId={} expires={}s", d.getDeviceId(), sub.callId, subscribeExpires);
    }

    /** 同对话续订: Call-ID/双 tag 不变, CSeq 递增（GB28181 无 Route 集, 新事务发送即可） */
    private void sendRefresh(Sub s) {
        long sn = SipIds.nextSn();
        s.sentAt = now();
        // 临时挡板: 200 OK 到达后覆盖为真实下次续订时点
        s.nextRefreshAt = now().plusSeconds(RESPONSE_TIMEOUT_SECONDS);
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(s.stackId)
                .method("SUBSCRIBE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(s.deviceId + "@" + platform.getDomain())
                .callId(s.callId)
                .fromTag(s.fromTag)
                .toTag(s.toTag)
                .sn(sn)
                .expires(subscribeExpires)
                .event(EVENT_ALARM)
                .body(codec.buildAlarmQuery(s.deviceId, sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .targetHost(s.targetHost)
                .targetPort(s.targetPort)
                .build();
        transport.send(ctx);
        log.info("报警订阅续订已发送: device={} callId={} cseq={}", s.deviceId, s.callId, sn);
    }

    /** 200 OK / error response handling: match by Call-ID, confirm / schedule refresh / cleanup on reject */
    public void onSipResponse(SipEvent event) {
        if (!"SUBSCRIBE".equals(event.getMethod())) {
            return;
        }
        String deviceId = callIdIndex.get(event.getCallId());
        if (deviceId == null) {
            return;
        }
        Sub s = subs.get(deviceId);
        if (s == null) {
            callIdIndex.remove(event.getCallId());
            return;
        }
        // 会话已被置换(超时重建)时, 旧对话的应答不再适用于新会话
        if (s.callId == null || !s.callId.equals(event.getCallId())) {
            return;
        }
        int code = event.getStatusCode() == null ? 0 : event.getStatusCode();
        if (code >= 200 && code < 300) {
            s.confirmed = true;
            if (event.getToTag() != null && !event.getToTag().isBlank()) {
                s.toTag = event.getToTag();
            }
            // 续订节奏以对端授予有效期为准(未带/非法 Expires 回退本端请求值), 提前 0.8× 刷新
            int granted = event.getExpires() != null && event.getExpires() > 0
                    ? event.getExpires() : subscribeExpires;
            s.nextRefreshAt = now().plusSeconds((long) (granted * REFRESH_ADVANCE));
            log.info("报警订阅确认: device={} 授予={}s 下次续订={}", deviceId, granted, s.nextRefreshAt);
        } else if (code >= 300) {
            log.warn("报警订阅被拒, 清理会话: device={} code={}", deviceId, code);
            callIdIndex.remove(s.callId);
            subs.remove(deviceId);
        }
        // 1xx 临时应答: 忽略, 继续等待终应答
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 订阅会话（内存态, 风格同 CascadeUpperManager 的上级 UA 会话） */
    private static final class Sub {
        final String deviceId;
        String callId;
        String fromTag;
        String toTag;
        String stackId;
        /** refresh 用目标地址（注册时档案地址, 对话期不变） */
        String targetHost;
        Integer targetPort;
        LocalDateTime sentAt;
        boolean confirmed;
        LocalDateTime nextRefreshAt;

        Sub(String deviceId) {
            this.deviceId = deviceId;
        }
    }
}
