package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.service.SessionCleanupService;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 设备在线/离线翻转动作（M5-B1 从 DeviceOfflineTask 抽出共用, docs/14 §6）：
 * 离线动作 = 置 offline + WS 事件 + 通知 + 清订阅 + 关停活跃会话;
 * 在线动作 = 置 online + 刷 keepalive + WS 事件。
 * M5 联动: RTSP 拉流代理设备翻转时<b>同步联动通道 status</b>——级联目录 Status 每次实时查库
 * 读 gb_device_channel.status（CascadeCatalogService.item）, gb_device.online 与通道表之间
 * 此前无任何联动写入（GB 通道 status 由设备自报, 无需联动）; RTSP 无自报通道, 探活/收敛循环
 * 是唯一真相源, 不联动则上级目录恒 OFF 被拒点。GB/ONVIF 行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceOfflineService {

    /** 活跃态集合（与 DB 生成列 active_flag 口径一致） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final StreamEventPublisher eventPublisher;
    private final SessionCleanupService cleanup;
    private final GbMediaSessionMapper sessionMapper;
    private final NoticeService noticeService;
    private final CatalogSubscriptionService catalogSubscriptionService;
    private final AlarmSubscriptionService alarmSubscriptionService;

    /**
     * 判离线动作（DeviceOfflineTask 兜底判定 / RtspSourceProbeTask 防抖判定共用）。
     * 调用方保证设备当前 online=1（任务侧已按各自口径筛过）。
     */
    public void markOffline(GbDeviceEntity device, String reason) {
        LocalDateTime now = LocalDateTime.now();
        GbDeviceEntity update = new GbDeviceEntity();
        update.setId(device.getId());
        update.setOnline(0);
        update.setOfflineTime(now);
        deviceMapper.updateById(update);
        log.warn("设备离线判定: {} ({})", device.getDeviceId(), reason);
        linkChannels(device, 0);
        eventPublisher.deviceOffline(device.getDeviceId(), device.getName());
        // 消息中心 B1-c: 离线通知落库扇出（publish 内部吞异常, 不影响离线关停会话主流程）
        noticeService.publish("DEVICE_OFFLINE", "设备离线",
                NoticeService.deviceOfflineContent(device.getDeviceId(), device.getName()), 1, device.getDeviceId());
        // M3-B3 阶段4: 离线即清目录订阅(重新上线由注册钩子/tick 重建)
        catalogSubscriptionService.onDeviceOffline(device.getDeviceId());
        // M3-B4: 离线同步清报警订阅
        alarmSubscriptionService.onDeviceOffline(device.getDeviceId());
        // docs/06 §1 OFFLINE 迁移动作: 关停该设备全部活跃会话（清理三件套, BYE 对端可能已死仍尝试）
        closeActiveSessions(device.getId());
    }

    /**
     * 在线动作（探活/收敛循环成功路径共用）: 已在线仅刷 keepalive;
     * 离线→在线翻转时置 1 + 联动通道 ON + WS 事件。
     */
    public void keepaliveAndOnline(GbDeviceEntity device) {
        boolean flip = device.getOnline() == null || device.getOnline() != 1;
        GbDeviceEntity update = new GbDeviceEntity();
        update.setId(device.getId());
        update.setKeepaliveTime(LocalDateTime.now());
        if (flip) {
            update.setOnline(1);
        }
        deviceMapper.updateById(update);
        if (flip) {
            log.info("设备上线翻转: {}", device.getDeviceId());
            linkChannels(device, 1);
            eventPublisher.deviceOnline(device.getDeviceId(), device.getName());
        }
    }

    /** RTSP 设备翻转联动通道 status（单设备单通道, 全量置同值; GB/ONVIF 不联动保持自报语义） */
    private void linkChannels(GbDeviceEntity device, int status) {
        if (DeviceProtocol.of(device) != DeviceProtocol.RTSP) {
            return;
        }
        try {
            channelMapper.update(null, new LambdaUpdateWrapper<GbDeviceChannelEntity>()
                    .eq(GbDeviceChannelEntity::getDeviceDbId, device.getId())
                    .isNull(GbDeviceChannelEntity::getDeletedTime)
                    .set(GbDeviceChannelEntity::getStatus, status));
        } catch (Exception e) {
            log.warn("通道状态联动失败(不影响设备翻转): deviceDbId={} status={}", device.getId(), status, e);
        }
    }

    /** 设备离线 → 逐条关停活跃会话并推送 STREAM_CLOSED（单条失败继续下一条） */
    private void closeActiveSessions(Long deviceDbId) {
        List<GbMediaSessionEntity> active = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .eq(GbMediaSessionEntity::getDeviceDbId, deviceDbId));
        for (GbMediaSessionEntity s : active) {
            try {
                cleanup.close(s.getId(), "CLOSED", "device_offline", "设备离线自动关停");
                eventPublisher.streamClosed(s.getStreamId(), "device_offline");
            } catch (Exception e) {
                log.error("设备离线会话清理失败(继续下一条): id={} stream={}", s.getId(), s.getStreamId(), e);
            }
        }
    }

}
