package com.yujian.vision.service.device.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.service.DeviceOfflineService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 设备离线判定（docs/05 §2, docs/06 §7）：在线设备 keepalive_time 超过 interval × timeout
 * 个心跳周期未刷新 → 判离线。M5-B1 起离线动作委托 DeviceOfflineService（探活防抖判定同源
 * 同动作——RTSP 设备由此联动通道 status, GB/ONVIF 不联动）。
 * M3-B6 阶段2: 双实例(HA)并存时 Redisson 锁互斥——离线判定事件/通知不许双发;
 * 不加 HA 角色门禁: 备台判设备离线+关会话是期望行为(主台死→设备心跳经 VIP 落备台前,
 * deadline 由共享 DB 的 keepalive_time 驱动, 判定口径与主台完全一致)。
 * 探活兜底口径: RTSP 探活任务(RtspSourceProbeTask)成功刷 keepalive, 失败不刷——本任务对
 * RTSP 设备同为兜底(探活任务整体失效时按超时判离线)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOfflineTask {

    private static final String LOCK_KEY = "gb:lock:task:device-offline";

    private final GbDeviceMapper deviceMapper;
    private final DeviceOfflineService offlineService;
    private final RedissonClient redisson;

    @Scheduled(fixedDelay = 10_000)
    public void check() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            // 非阻塞抢锁(waitTime=0): 拿不到 = 另一实例本轮正在跑, 放弃本轮
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            doCheck();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void doCheck() {
        // 必须带软删过滤: deletedTime 无 @TableLogic, 软删行 online 仍停在 1 且 keepalive_time
        // 从此定格——漏掉过滤会让「删除设备→重新注册」产生的幽灵行在 180s 后触发一次假离线
        // （假 deviceOffline 事件/通知, 并按 deviceId 误清活行的目录/报警订阅）。口径与
        // CatalogSubscriptionService.tick / CatalogRefreshTask / Onvif/Rtsp 探活任务一致。
        List<GbDeviceEntity> onlineDevices = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getOnline, 1)
                        .isNull(GbDeviceEntity::getDeletedTime));
        LocalDateTime now = LocalDateTime.now();
        for (GbDeviceEntity device : onlineDevices) {
            int interval = device.getHeartbeatInterval() != null ? device.getHeartbeatInterval() : 60;
            int timeoutCount = device.getHeartbeatTimeout() != null ? device.getHeartbeatTimeout() : 3;
            LocalDateTime deadline = device.getKeepaliveTime() == null
                    ? device.getRegisterTime()
                    : device.getKeepaliveTime();
            if (deadline == null || deadline.plusSeconds((long) interval * timeoutCount).isBefore(now)) {
                offlineService.markOffline(device, "超过 " + timeoutCount + " 个心跳周期未刷新");
            }
        }
    }
}
