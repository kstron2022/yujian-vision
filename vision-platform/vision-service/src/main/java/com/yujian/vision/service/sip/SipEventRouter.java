package com.yujian.vision.service.sip;

import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.alarm.service.AlarmService;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.cascade.service.CascadeAlarmService;
import com.yujian.vision.service.cascade.service.CascadeCatalogService;
import com.yujian.vision.service.cascade.service.CascadeInviteService;
import com.yujian.vision.service.cascade.service.CascadePtzService;
import com.yujian.vision.service.cascade.service.CascadePresetQueryService;
import com.yujian.vision.service.cascade.service.CascadeUpperManager;
import com.yujian.vision.service.device.service.CatalogSyncService;
import com.yujian.vision.service.device.service.CatalogSubscriptionService;
import com.yujian.vision.service.device.service.DeviceRegisterService;
import com.yujian.vision.service.device.service.HeartbeatService;
import com.yujian.vision.service.device.service.PresetQueryService;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.service.media.service.TalkSessionService;
import com.yujian.vision.service.record.RecordQueryService;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipEventListener;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SIP 入站消息路由（docs/01 §3.2 消息路由层）：
 * REGISTER → 注册服务；MESSAGE → 按报文 CmdType 分发（心跳/目录响应/录像检索响应/预置位查询响应）；
 * 级联（M3-B1）：上级对本平台 UAC 的应答先经 CascadeUpperManager 拦截；上级 Query/Catalog → 目录上报。
 * 设备主动 INVITE 暂拒（603），BYE 留待 M1-5 会话模块处理。
 */
@Slf4j
@Component
public class SipEventRouter implements SipEventListener {

    private final ManScdpCodec codec;
    private final SipTransportProvider transport;
    private final DeviceRegisterService registerService;
    private final HeartbeatService heartbeatService;
    private final CatalogSyncService catalogSyncService;
    private final MediaSessionService mediaSessionService;
    private final RecordQueryService recordQueryService;
    private final PresetQueryService presetQueryService;
    private final CascadeUpperManager cascadeUpperManager;
    private final CascadeCatalogService cascadeCatalogService;
    private final CascadeInviteService cascadeInviteService;
    private final CascadePtzService cascadePtzService;
    private final CascadePresetQueryService cascadePresetQueryService;
    private final CatalogSubscriptionService catalogSubscriptionService;
    private final AlarmService alarmService;
    private final AlarmSubscriptionService alarmSubscriptionService;
    private final CascadeAlarmService cascadeAlarmService;
    private final TalkSessionService talkSessionService;

    public SipEventRouter(ManScdpCodec codec, SipTransportProvider transport,
            DeviceRegisterService registerService, HeartbeatService heartbeatService,
            CatalogSyncService catalogSyncService, MediaSessionService mediaSessionService,
            RecordQueryService recordQueryService, PresetQueryService presetQueryService,
            CascadeUpperManager cascadeUpperManager, CascadeCatalogService cascadeCatalogService,
            CascadeInviteService cascadeInviteService, CascadePtzService cascadePtzService,
            CascadePresetQueryService cascadePresetQueryService,
            CatalogSubscriptionService catalogSubscriptionService,
            CascadeAlarmService cascadeAlarmService,
            AlarmService alarmService, AlarmSubscriptionService alarmSubscriptionService,
            TalkSessionService talkSessionService) {
        this.codec = codec;
        this.transport = transport;
        this.registerService = registerService;
        this.heartbeatService = heartbeatService;
        this.catalogSyncService = catalogSyncService;
        this.mediaSessionService = mediaSessionService;
        this.recordQueryService = recordQueryService;
        this.presetQueryService = presetQueryService;
        this.cascadeUpperManager = cascadeUpperManager;
        this.cascadeCatalogService = cascadeCatalogService;
        this.cascadeInviteService = cascadeInviteService;
        this.cascadePresetQueryService = cascadePresetQueryService;
        this.cascadePtzService = cascadePtzService;
        this.catalogSubscriptionService = catalogSubscriptionService;
        this.cascadeAlarmService = cascadeAlarmService;
        this.alarmService = alarmService;
        this.alarmSubscriptionService = alarmSubscriptionService;
        this.talkSessionService = talkSessionService;
    }

    @Override
    public void onEvent(SipEvent event) {
        try {
            if (event.getType() == SipEvent.Type.RESPONSE) {
                // 级联 UA 在途事务（REGISTER/Keepalive MESSAGE）先拦截消费（docs/08 §2.1）
                if (cascadeUpperManager.onSipResponse(event)) {
                    return;
                }
                // 对端对本平台请求的应答：INVITE 200 OK→ACK、4xx/5xx→FAILED（F1 修复: 原先未接线）
                mediaSessionService.onSipResponse(event);
                // M3-B5: 对讲 INVITE 的响应（200 OK 解析设备收流地址 / 4xx/5xx 清理）, callId 不匹配即忽略
                talkSessionService.onSipResponse(event);
                // M3-B3 阶段4: 对本平台 SUBSCRIBE 的 200 OK（订阅确认/续订确认）
                catalogSubscriptionService.onSipResponse(event);
                // M3-B4: 对本平台报警 SUBSCRIBE 的 200 OK（按 Call-ID 过滤, 无关应答静默忽略）
                alarmSubscriptionService.onSipResponse(event);
                return;
            }
            switch (event.getMethod()) {
                case "REGISTER" -> registerService.handle(event);
                case "MESSAGE" -> handleMessage(event);
                case "OPTIONS" -> transport.sendResponse(event, 200, Map.of());
                case "SUBSCRIBE" -> {
                    // M3-B4 阶段2: 上级订阅制报警上报——仅 Event:Alarm 且 From 为在册启用上级时受理建对话;
                    // 非上级/其它事件包仍 501 不建对话（原 200 礼貌应答会诱导对端订阅期待, B3 收口评审决策）
                    if (!cascadeAlarmService.onUpperSubscribe(event)) {
                        transport.sendResponse(event, 501, Map.of());
                        log.info("收到 SUBSCRIBE(非上级或事件包不支持, 已回 501): from={} event={}",
                                event.getFromUser(), event.getEvent());
                    }
                }
                case "NOTIFY" -> {
                    // M3-B3 阶段4: 下级 NOTIFY(Catalog) 增量入库(兼容 2016 整目录/2022 Event 增量);
                    // 其余 NOTIFY 报文(位置/报警)礼貌应答待后续批次
                    try {
                        ManScdpMessage message = codec.parse(event.getBody());
                        if (ManScdpConstants.ROOT_NOTIFY.equalsIgnoreCase(message.getRootType())
                                && ManScdpConstants.CMD_CATALOG.equals(message.getCmdType())) {
                            catalogSubscriptionService.onNotify(event, message);
                        } else if (ManScdpConstants.ROOT_NOTIFY.equalsIgnoreCase(message.getRootType())
                                && ManScdpConstants.CMD_ALARM.equals(message.getCmdType())) {
                            // M3-B4: 订阅推送 NOTIFY/Alarm → 报警入库
                            alarmService.ingest(event, message);
                        } else {
                            log.debug("忽略 NOTIFY 报文: root={} cmd={}", message.getRootType(), message.getCmdType());
                        }
                    } catch (Exception e) {
                        log.warn("NOTIFY 报文解析失败: {}", e.getMessage());
                    }
                    transport.sendResponse(event, 200, Map.of());
                }
                case "INVITE" -> {
                    // 级联-下级: 上级 INVITE 点播共享通道 → 级联转推（docs/08 §2.3）;
                    // 非上级来源在服务内回 603（设备主动点播/对讲仍拒绝, M3 后续批次处理）
                    cascadeInviteService.onUpperInvite(event);
                }
                case "ACK" -> log.debug("收到 ACK: callId={}（转推已在 200 OK 前启动, 无需动作）", event.getCallId());
                case "BYE" -> {
                    // 级联转推的上级 BYE 先按 callId 消费; 设备侧结束会话: 200 OK + 按_callID 关闭本地会话（F6 修复）
                    if (!cascadeInviteService.onUpperBye(event)) {
                        transport.sendResponse(event, 200, Map.of());
                        mediaSessionService.onDeviceBye(event.getCallId());
                        // M3-B5: 对讲设备 BYE → stopSendRtp + 收尾（callId 不匹配即忽略）
                        talkSessionService.onDeviceBye(event.getCallId());
                    }
                }
                default -> log.debug("忽略 SIP 方法: {}", event.getMethod());
            }
        } catch (Exception e) {
            log.error("处理入站消息异常: {} {}", event.getMethod(), event.getFromUser(), e);
        }
    }

    private void handleMessage(SipEvent event) {
        ManScdpMessage message;
        try {
            message = codec.parse(event.getBody());
        } catch (Exception e) {
            log.warn("MESSAGE 报文解析失败: {}", e.getMessage());
            transport.sendResponse(event, 200, Map.of());
            return;
        }
        String cmd = message.getCmdType() == null ? "" : message.getCmdType();
        boolean isNotify = ManScdpConstants.ROOT_NOTIFY.equalsIgnoreCase(message.getRootType());
        boolean isResponse = ManScdpConstants.ROOT_RESPONSE.equalsIgnoreCase(message.getRootType());
        if (isNotify && ManScdpConstants.CMD_KEEPALIVE.equals(cmd)
                && cascadeUpperManager.isEnabledUpper(event.getFromUser())) {
            // 上级探活: 部分上级周期向本级发 Keepalive 校验存活（厂商行为不一）, 只应答不落设备表
            transport.sendResponse(event, 200, Map.of());
        } else if (isNotify && ManScdpConstants.CMD_KEEPALIVE.equals(cmd)) {
            heartbeatService.handle(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (isResponse && ManScdpConstants.CMD_CATALOG.equals(cmd)) {
            catalogSyncService.onResponse(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (ManScdpConstants.ROOT_QUERY.equalsIgnoreCase(message.getRootType())
                && ManScdpConstants.CMD_CATALOG.equals(cmd)) {
            // 级联-下级: 上级 Query/Catalog → 共享通道目录上报（docs/08 §2.2）
            cascadeCatalogService.onUpperQuery(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (ManScdpConstants.ROOT_CONTROL.equalsIgnoreCase(message.getRootType())
                && ManScdpConstants.CMD_DEVICE_CONTROL.equals(cmd)) {
            // 级联-中转: 上级 DeviceControl → PTZ 解码透传真实设备（docs/08 §2.4, B2 阶段 3）
            cascadePtzService.onUpperControl(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (ManScdpConstants.ROOT_QUERY.equalsIgnoreCase(message.getRootType())
                && ManScdpConstants.CMD_PRESET_QUERY.equals(cmd)) {
            // 级联-下级: 上级 Query/PresetQuery → 按协议转查共享通道并应答（2026-09-26 LiveGBS 补）
            cascadePresetQueryService.onUpperPresetQuery(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (isResponse && ManScdpConstants.CMD_RECORD_INFO.equals(cmd)) {
            recordQueryService.onResponse(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (isResponse && ManScdpConstants.CMD_PRESET_QUERY.equals(cmd)) {
            presetQueryService.onResponse(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (isResponse && ManScdpConstants.CMD_MOBILE_POSITION.equals(cmd)) {
            log.debug("位置上报(M3 处理): {}", message.getDeviceId());
            transport.sendResponse(event, 200, Map.of());
        } else if (isNotify && ManScdpConstants.CMD_ALARM.equals(cmd)) {
            // M3-B4: 设备/下级主动 Notify/Alarm 上报（2016 设备常见, 无需订阅）
            alarmService.ingest(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else if (isResponse && ManScdpConstants.CMD_ALARM.equals(cmd)) {
            // M3-B4: Response/Alarm 上报入库（原 M3 桩替换为真实入库）
            alarmService.ingest(event, message);
            transport.sendResponse(event, 200, Map.of());
        } else {
            log.debug("未处理的 MESSAGE: root={} cmd={}", message.getRootType(), cmd);
            transport.sendResponse(event, 200, Map.of());
        }
    }
}
