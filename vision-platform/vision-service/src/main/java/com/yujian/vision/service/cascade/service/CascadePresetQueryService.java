package com.yujian.vision.service.cascade.service;

import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.service.PresetQueryService;
import com.yujian.vision.service.device.service.PresetQueryService.PresetItem;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 级联预置位转查（2026-09-26 LiveGBS 联调补齐, docs/08 §2.4）：上级 Query/PresetQuery →
 * DeviceID 三级反查共享通道 → 复用 {@link PresetQueryService#query} 按协议转查
 * （ONVIF=GetPresets 同步 / GB=SIP PresetQuery 转发设备 / RTSP=空）→ 应答
 * Response/PresetQuery（SN 全包回显）。此前只有 Response 侧（应答本级发起的查询）,
 * 入站查询无分支, 上级查预置位恒拿不到列表。
 *
 * <p>GB 转查阻塞等应答（最长 5s）, 投递 cascade-preset 单线程（不占 sip-dispatch,
 * 与 cascade-catalog/cascade-invite 同模式）。查询失败/通道未共享回空 PresetList
 * （Num=0）让上级立即收敛——与目录应答空列表同口径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CascadePresetQueryService {

    private final CascadeInviteService cascadeInvites;
    private final PresetQueryService presetQueryService;
    private final CascadeSipSender sender;
    private final ManScdpCodec codec;

    /** GB 转查为阻塞语义（最长 5s）, 专用单线程避免占 sip-dispatch 信令线程 */
    private final ExecutorService presetExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cascade-preset");
                t.setDaemon(true);
                return t;
            });

    /** 上级 PresetQuery 入口（SipEventRouter Query/PresetQuery 分支调用） */
    public void onUpperPresetQuery(SipEvent event, ManScdpMessage message) {
        String upperDeviceId = event.getFromUser();
        GbCascadeUpperEntity u = cascadeInvites.findByUpperDeviceId(upperDeviceId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            log.debug("PresetQuery 非启用上级, 忽略: {}", upperDeviceId);
            return;
        }
        String targetId = message.getDeviceId();
        long sn = message.getSn() != null ? message.getSn() : SipIds.nextSn();
        Long channelDbId = targetId == null || targetId.isBlank()
                ? null : cascadeInvites.resolveChannelDbId(u, targetId);
        if (channelDbId == null) {
            // 通道未共享/缺 DeviceID: 回空列表让上级收敛（不算错, 与目录口径一致）
            answer(u, targetId, sn, List.of(), 0);
            return;
        }
        presetExecutor.execute(() -> {
            try {
                List<PresetItem> items = presetQueryService.query(channelDbId);
                answer(u, targetId, sn, items, items.size());
            } catch (Exception e) {
                // 设备离线/不支持/超时: 国标无错误应答语义, 回空列表让上级收敛
                log.info("级联预置位转查失败, 回空列表: upper={} channel={} err={}",
                        upperDeviceId, targetId, e.getMessage());
                answer(u, targetId, sn, List.of(), 0);
            }
        });
    }

    /** 应答（新 MESSAGE 事务, SN 回显查询 SN; 条目转 PresetID/PresetName 平铺） */
    private void answer(GbCascadeUpperEntity u, String targetId, long sn,
                        List<PresetItem> items, int count) {
        List<Map<String, String>> rows = new ArrayList<>(items.size());
        for (PresetItem p : items) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("PresetID", p.presetId());
            if (p.presetName() != null) {
                m.put("PresetName", p.presetName());
            }
            rows.add(m);
        }
        sender.sendMessage(u, codec.buildPresetResponse(targetId, sn, rows), SipIds.nextSn());
        log.info("级联预置位查询应答: upper={} channel={} {} 条 (SN={})", u.getName(), targetId, count, sn);
    }
}
