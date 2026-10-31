package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
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
 * 目录订阅（M3-B3 阶段4, docs/08 §3.4）：本平台作为上级，向下级平台(device_type=2)主动
 * SUBSCRIBE(Event: Catalog) 订阅目录，下级设备/通道变化经 NOTIFY(Catalog) 增量推送并实时入库。
 * 订阅状态内存态（重启后由 tick 扫描 DB 重建, 风格同 CascadeUpperManager）；周期全量刷新
 * (CatalogRefreshTask, 默认关)作为兜底，通知作为实时增量补充。
 * 增量语义：2022 Event 属性（on/off/add/del/update, 大小写不敏感）
 * + 2016 整目录（无 Event 逐条 upsert）；增量永不触发「缺失即软删」——软删只来自 del 事件
 * 与全量收敛，防误删。
 */
@Slf4j
@Component
public class CatalogSubscriptionService {

    /** 事件包名（GB/T 28181 目录订阅） */
    static final String EVENT_CATALOG = "Catalog";
    /** 订阅请求应答超时: 超 15s 未获 200 视为失败, 由 tick 丢弃重建 */
    private static final long RESPONSE_TIMEOUT_SECONDS = 15;
    /** 单包 NOTIFY 条目上限(收口评审: 增量包防写放大, 大批量应走全量同步) */
    private static final int MAX_NOTIFY_ITEMS = 1000;
    /** 到期续订提前量: 到期前 20%（0.8×）发 re-SUBSCRIBE 刷新 */
    private static final double REFRESH_ADVANCE = 0.8;

    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;
    private final ManScdpCodec codec;
    private final StreamEventPublisher eventPublisher;
    /** SIP 主备（M3-B6 阶段2）: 备台身份停跑订阅扫描（SUBSCRIBE 需本进程 SIP 栈） */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;

    /** SUBSCRIBE Expires 秒数(默认 1h, 到期前 0.8× 自动续订, vision.cascade.catalog-subscribe-expires) */
    @Value("${vision.cascade.catalog-subscribe-expires:3600}")
    private int subscribeExpires;

    /** 订阅会话: 国标编码 → 会话（内存态, 重启失, tick 扫描在线 type=2 重建） */
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    /** Call-ID → 国标编码（200 OK 应答按 callId 关联会话） */
    private final Map<String, String> callIdIndex = new ConcurrentHashMap<>();

    public CatalogSubscriptionService(SipTransportProvider transport, GbDeviceMapper deviceMapper,
            GbDeviceChannelMapper channelMapper, GbPlatformProperties platform,
            ManScdpCodec codec, StreamEventPublisher eventPublisher,
            com.yujian.vision.service.ha.HaRuntimeService ha) {
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.platform = platform;
        this.codec = codec;
        this.eventPublisher = eventPublisher;
        this.ha = ha;
    }

    // ---------------------------------------------------------------- 生命周期钩子

    /**
     * 设备上线钩子(注册成功/重新注册): type=2 自动发起订阅。
     * 失败只记日志不阻塞注册(与 asyncCatalogSync 同策略)。
     */
    public void onDeviceOnline(GbDeviceEntity device) {
        if (device == null || !isCascadeDevice(device)) {
            return;
        }
        try {
            trySubscribe(device);
        } catch (Exception e) {
            log.warn("目录订阅发起失败: device={}", device.getDeviceId(), e);
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
     * 扫描式调度（单 tick 每 10s, 风格同 CascadeUpperManager/CatalogSyncService.sweep）：
     * ① 扫描在线 type=2 设备补建缺失订阅（重启恢复 + 注册钩子漏网兜底）;
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
                        .eq(GbDeviceEntity::getDeviceType, 2)
                        .isNull(GbDeviceEntity::getDeletedTime));
        LocalDateTime now = LocalDateTime.now();
        for (GbDeviceEntity d : devices) {
            if (!subs.containsKey(d.getDeviceId())) {
                // 逐设备隔离: 单台发送失败(TCP 连接拒绝等)不中断整轮巡检
                try {
                    trySubscribe(d);
                } catch (Exception e) {
                    log.warn("目录订阅发起失败: device={}", d.getDeviceId(), e);
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
                .event(EVENT_CATALOG)
                .body(codec.buildCatalogQuery(d.getDeviceId(), sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .targetHost(sub.targetHost)
                .targetPort(sub.targetPort)
                .build();
        // 先登记再发送: 200 OK 可能先于 send 返回到达, 应答路由依赖两份索引
        subs.put(d.getDeviceId(), sub);
        callIdIndex.put(sub.callId, d.getDeviceId());
        transport.send(ctx);
        log.info("目录订阅已发起: device={} callId={} expires={}s", d.getDeviceId(), sub.callId, subscribeExpires);
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
                .event(EVENT_CATALOG)
                .body(codec.buildCatalogQuery(s.deviceId, sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .targetHost(s.targetHost)
                .targetPort(s.targetPort)
                .build();
        transport.send(ctx);
        log.info("目录订阅续订已发送: device={} callId={} cseq={}", s.deviceId, s.callId, sn);
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
            log.info("目录订阅确认: device={} 授予={}s 下次续订={}", deviceId, granted, s.nextRefreshAt);
        } else if (code >= 300) {
            log.warn("目录订阅被拒, 清理会话: device={} code={}", deviceId, code);
            callIdIndex.remove(s.callId);
            subs.remove(deviceId);
        }
        // 1xx 临时应答: 忽略, 继续等待终应答
    }

    // ---------------------------------------------------------------- NOTIFY 增量入库

    /**
     * 下级 NOTIFY(Catalog) 增量入库（路由见 SipEventRouter, 已限定 root=Notify + CmdType=Catalog）：
     * 2022 带 Event 属性（on/off/add/del/update, 大小写不敏感）做增量;
     * 2016 无 Event 按整目录逐条 upsert —— 两种形态都不做「缺失即软删」, 软删只来自 del 事件,
     * 全量收敛仍由 CatalogSyncService 兜底, 防通知丢包误删通道。
     */
    public void onNotify(SipEvent event, ManScdpMessage message) {
        String deviceId = message.getDeviceId() != null ? message.getDeviceId() : event.getFromUser();
        // 来源校验(收口评审): SIP/UDP 可裸伪造, 仅处理本方已确认订阅对话内的 NOTIFY,
        // 且 From 与报文 DeviceID 交叉一致 —— 未订阅来源的目录通知一律丢弃, 防通道表被篡改
        String subDeviceId = callIdIndex.get(event.getCallId());
        Sub sub = subDeviceId == null ? null : subs.get(subDeviceId);
        if (subDeviceId == null || sub == null || !sub.confirmed || !subDeviceId.equals(deviceId)
                || (event.getFromUser() != null && !event.getFromUser().equals(deviceId))) {
            log.warn("目录通知来源未通过订阅对话校验, 丢弃: device={} callId={} from={}",
                    deviceId, event.getCallId(), event.getFromUser());
            return;
        }
        GbDeviceEntity device = selectDevice(deviceId);
        if (device == null || !isCascadeDevice(device)) {
            log.debug("notify 目录变更但无有效下级档案, 丢弃: device={}", deviceId);
            return;
        }
        List<Map<String, String>> items = message.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        if (items.size() > MAX_NOTIFY_ITEMS) {
            // 大批量目录应走全量同步(Query/Catalog 聚合), 增量通道防写放大
            log.warn("目录通知条目超上限, 拒绝处理: device={} items={} 上限={}", deviceId, items.size(), MAX_NOTIFY_ITEMS);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, String> item : items) {
            String channelId = item.get("DeviceID");
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            // 逐条隔离: 单条入库失败不影响本包其余条目(异常也不再上抛, 路由层不误判为解析失败)
            try {
                String evt = item.get("Event");
                String action = evt == null ? "" : evt.trim().toLowerCase();
                GbDeviceChannelEntity ch = selectChannel(device.getId(), channelId);
                switch (action) {
                    case "del" -> softDelete(ch, channelId, now);
                    case "on", "off" -> changeStatus(ch, channelId, "on".equals(action), now);
                    case "add", "update", "" -> upsert(device, ch, channelId, item, now);
                    default -> log.debug("忽略未知目录事件: device={} channel={} event={}", deviceId, channelId, evt);
                }
            } catch (Exception e) {
                log.error("目录通知条目入库失败(继续下一条): device={} channel={}", deviceId, channelId, e);
            }
        }
        eventPublisher.channelChange(deviceId);
    }

    /** 逐条 upsert：add/update 与 2016 无 Event 整目录共用; 永不软删（不查全量列表） */
    private void upsert(GbDeviceEntity device, GbDeviceChannelEntity ch, String channelId,
            Map<String, String> item, LocalDateTime now) {
        boolean isNew = ch == null;
        if (ch == null) {
            ch = new GbDeviceChannelEntity();
            ch.setDeviceDbId(device.getId());
            ch.setChannelId(channelId);
            ch.setCreateTime(now);
        }
        // 新实体 status 本为 null: 与旧通道一样在 applyItemFields 覆盖前读现值
        Integer prevStatus = ch.getStatus();
        CatalogSyncService.applyItemFields(ch, item);
        if (!item.containsKey("Status")) {
            // 2022 增量 update 可只带变更字段: 无 Status 时不强置 OFF, 保留通道现值
            ch.setStatus(prevStatus);
        }
        if (isNew) {
            channelMapper.insert(ch);
            log.info("notify 新增通道: device={} channel={} name={}", device.getDeviceId(), channelId, ch.getName());
        } else {
            channelMapper.updateById(ch);
            log.debug("notify 更新通道: device={} channel={}", device.getDeviceId(), channelId);
        }
    }

    /** on/off 状态变化（2022 Event 增量）: 通道不存在时忽略（等全量同步补齐）, 不建档 */
    private void changeStatus(GbDeviceChannelEntity ch, String channelId, boolean on, LocalDateTime now) {
        if (ch == null) {
            log.debug("notify 状态变化但通道不存在, 忽略: channel={} event={}", channelId, on ? "on" : "off");
            return;
        }
        ch.setStatus(on ? 1 : 0);
        ch.setCatalogTime(now);
        channelMapper.updateById(ch);
        log.debug("notify 通道状态: channel={} status={}", channelId, ch.getStatus());
    }

    /** del 软删（2022 Event 增量）: 只按事件删, 不做缺失比对; 通道不存在时幂等忽略 */
    private void softDelete(GbDeviceChannelEntity ch, String channelId, LocalDateTime now) {
        if (ch == null) {
            log.debug("notify 删除事件但通道不存在, 忽略: channel={}", channelId);
            return;
        }
        GbDeviceChannelEntity del = new GbDeviceChannelEntity();
        del.setId(ch.getId());
        del.setDeletedTime(now);
        channelMapper.updateById(del);
        log.info("notify 删除通道: channel={}", channelId);
    }

    private GbDeviceEntity selectDevice(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    private GbDeviceChannelEntity selectChannel(Long deviceDbId, String channelId) {
        return channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getDeviceDbId, deviceDbId)
                .eq(GbDeviceChannelEntity::getChannelId, channelId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    private static boolean isCascadeDevice(GbDeviceEntity device) {
        return device.getDeviceType() != null && device.getDeviceType() == 2;
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
