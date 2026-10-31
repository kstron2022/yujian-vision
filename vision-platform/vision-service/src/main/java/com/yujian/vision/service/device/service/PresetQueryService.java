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
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.Duration;
import java.time.LocalDateTime;
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
 * 预置位查询（docs/09 M1-8 评审挂账, M2-B3）：PresetQuery MESSAGE 下发 → 按 SN 等单包应答
 * → PresetList/Item 映射为预置位列表。5s 超时（预置位量小, 单包语义, 无需像 RecordInfo 那样多包聚合）。
 * v1 内存会话, 单实例；集群化(M3) 迁 Redis（docs/02 §3）。
 */
@Slf4j
@Component
public class PresetQueryService {

    /** 单次预置位查询等待上限（单包应答, 窗口取录像检索的 1/3） */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final ManScdpCodec codec;
    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;
    private final DeviceAccessRegistry accessRegistry;
    /** sn → 会话（预置位应答单包, 不做多包聚合） */
    private final Map<Long, PresetSession> sessions = new ConcurrentHashMap<>();

    public PresetQueryService(ManScdpCodec codec, SipTransportProvider transport,
            GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            GbPlatformProperties platform, DeviceAccessRegistry accessRegistry) {
        this.codec = codec;
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.platform = platform;
        this.accessRegistry = accessRegistry;
    }

    /** 预置位条目（PresetID/PresetName, 应答 Item 字段表平铺而来） */
    public record PresetItem(String presetId, String presetName) {
    }

    /**
     * 通道预置位查询：通道校验 → 设备在线 → 通道级云台开关 → 发 PresetQuery
     * → 阻塞等待（5s 超时）。空 PresetList 返回空列表（设备无预置位属正常）。
     */
    public List<PresetItem> query(Long channelDbId) {
        // 1. 通道校验（存在未删）
        GbDeviceChannelEntity channel = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getId, channelDbId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
        if (channel == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道不存在: " + channelDbId);
        }
        // 2. 设备在线校验（channel.deviceDbId 是 gb_device 主键, 见 CatalogSyncService.converge）
        GbDeviceEntity device = deviceMapper.selectById(channel.getDeviceDbId());
        if (device == null || device.getDeletedTime() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备不存在: " + channel.getDeviceDbId());
        }
        if (device.getOnline() == null || device.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线或未注册: " + device.getDeviceId());
        }
        // 3. 通道级"强制云台"开关（docs/09 M1-8 挂账）：显式禁用即拒, 不看 ptzType
        if (channel.getPtzEnabled() != null && channel.getPtzEnabled() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "云台控制已被通道级开关禁用");
        }
        // 3.5 协议分流（2026-09-25 现场实测: ONVIF 通道此前也走 SIP PresetQuery——设备无 SIP
        // 石沉大海恒 1011 超时; RTSP 拉流代理无预置位语义）。GB/下级级联仍走 SIP PresetQuery。
        DeviceProtocol protocol = DeviceProtocol.of(device);
        if (protocol == DeviceProtocol.ONVIF) {
            return accessRegistry.of(DeviceProtocol.ONVIF).queryPresets(device, channel);
        }
        if (protocol == DeviceProtocol.RTSP) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流代理设备无预置位");
        }
        // 4. 建会话（先 put 再发, 防快速应答漏收——B2 callIndex 同教训）并发起 PresetQuery
        long sn = SipIds.nextSn();
        PresetSession session = new PresetSession(channel.getChannelId());
        sessions.put(sn, session);
        // 外呼栈: 按设备注册传输协议(UDP/TCP)选择, 未启用该协议栈时回退平台默认栈（mirror PtzControlService.send）
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
                .body(codec.buildPresetQuery(channel.getChannelId(), sn))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .build();
        transport.send(ctx);
        log.info("预置位查询已发起: channel={} sn={}", channel.getChannelId(), sn);
        // 5. 阻塞等待单包应答（onResponse complete / sweep 兜底）
        List<PresetItem> result;
        try {
            result = session.future.get(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            throw new BizException(ErrorCode.PRESET_QUERY_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.PRESET_QUERY_TIMEOUT);
        } finally {
            sessions.remove(sn);
        }
        return result;
    }

    /**
     * PresetQuery 应答处理（SipEventRouter 分发, 单包语义）：首个匹配 SN 的 RESPONSE 即 complete；
     * SN 无会话则丢弃（超时后迟到的应答）。items 为 null（无预置位/部分厂商回空容器）→ 空列表。
     */
    public void onResponse(SipEvent event, ManScdpMessage message) {
        if (message.getSn() == null) {
            return;
        }
        PresetSession session = sessions.get(message.getSn());
        if (session == null) {
            log.debug("无进行中的预置位查询, 丢弃: sn={} device={}", message.getSn(), message.getDeviceId());
            return;
        }
        synchronized (session) {
            if (session.future.isDone()) {
                return;
            }
            List<Map<String, String>> items = message.getItems() == null ? List.of() : message.getItems();
            List<PresetItem> presets = new ArrayList<>(items.size());
            for (Map<String, String> item : items) {
                String presetId = item.get("PresetID");
                if (presetId == null || presetId.isBlank()) {
                    continue;   // 无编号的条目无法被 goto/delete 引用, 丢弃
                }
                presets.add(new PresetItem(presetId, item.get("PresetName")));
                if (presets.size() >= MAX_PRESETS) {
                    break;   // 单包容量护栏（协议 PresetID 1-255）, 防异常报文撑爆内存
                }
            }
            session.future.complete(presets);
        }
    }

    /** 预置位条目上限（协议 PresetID 为 1-255, 正常不会触顶） */
    private static final int MAX_PRESETS = 255;

    /**
     * 超时兜底（应答未到 → completeExceptionally(1011) + 会话出清, 镜像 RecordQueryService.sweep）
     */
    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        LocalDateTime deadline = LocalDateTime.now().minus(QUERY_TIMEOUT);
        for (Map.Entry<Long, PresetSession> e : sessions.entrySet()) {
            PresetSession s = e.getValue();
            if (s.future.isDone() || s.startedAt.isAfter(deadline)) {
                continue;
            }
            sessions.remove(e.getKey());
            s.future.completeExceptionally(new BizException(ErrorCode.PRESET_QUERY_TIMEOUT));
            log.warn("预置位查询超时: channel={} sn={}", s.channelId, e.getKey());
        }
    }

    /** 预置位查询会话（内存态, 单包语义） */
    private static final class PresetSession {
        final String channelId;
        final LocalDateTime startedAt = LocalDateTime.now();
        final CompletableFuture<List<PresetItem>> future = new CompletableFuture<>();

        PresetSession(String channelId) {
            this.channelId = channelId;
        }
    }
}
