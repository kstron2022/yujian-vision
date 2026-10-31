package com.yujian.vision.service.device.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.onvif.DeviceProtocolConstants;
import com.yujian.vision.service.device.spi.DeviceAccessProvider;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ONVIF 探活任务（docs/13 §4）：对探活周期到期的 ONVIF 设备发 GetDeviceInformation,
 * 成功刷 keepalive_time/上线；失败交 DeviceOfflineTask 按 interval×timeout 统一判离线。
 *
 * <p>执行模型（B1 评审 S2）: 全工程 @Scheduled 共用默认单线程调度池, 探活是其中第一个
 * 同步等 HTTP 的任务——设备不可达时每台最坏 5~8s, 直接在调度线程上跑会拖迟 HA 租约续期
 * （10s 周期/TTL 30s, 242 双实例形态存在误接管风险）等全部后台任务。因此 tick 只做"到期
 * 筛选+提交", 实际探活在独立小线程池执行（in-flight 去重防堆积, 单轮上限防极端批量）。
 * Redisson tryLock(0) 与 DeviceOfflineTask 同口径互斥双实例的 tick 提交。</p>
 */
@Slf4j
@Component
public class OnvifProbeTask {

    private static final String LOCK_KEY = "gb:lock:task:onvif-probe";
    /** 单轮最多提交台数（极端批量离线时余下台数顺延到下一轮, 10s 一轮消化） */
    private static final int MAX_PER_TICK = 50;

    private final GbDeviceMapper deviceMapper;
    private final DeviceAccessProvider onvifProvider;
    private final RedissonClient redisson;
    /** 探活专用池: 单设备挂死最多占一个线程一个超时窗口, 不阻塞公共调度线程 */
    private final ExecutorService probePool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "onvif-probe");
        t.setDaemon(true);
        return t;
    });
    /** in-flight 去重（deviceDbId）: 上一轮未完成的设备本轮不重复提交 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public OnvifProbeTask(GbDeviceMapper deviceMapper, DeviceAccessRegistry registry,
            RedissonClient redisson) {
        this.deviceMapper = deviceMapper;
        this.onvifProvider = registry.of(DeviceProtocol.ONVIF);
        this.redisson = redisson;
    }

    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            doTick();
        } finally {
            lock.unlock();
        }
    }

    /** 包内可见便于单测: 只筛选+提交, 不阻塞 */
    void doTick() {
        List<GbDeviceEntity> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.ONVIF)
                        .isNull(GbDeviceEntity::getDeletedTime));
        if (devices.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int submitted = 0;
        for (GbDeviceEntity device : devices) {
            int interval = device.getHeartbeatInterval() != null ? device.getHeartbeatInterval() : 60;
            LocalDateTime last = device.getKeepaliveTime() != null ? device.getKeepaliveTime()
                    : device.getRegisterTime() != null ? device.getRegisterTime() : device.getCreateTime();
            // 未到探活周期跳过; 从未探活(全空)视为到期
            if (last != null && last.plusSeconds(interval).isAfter(now)) {
                continue;
            }
            if (submitted >= MAX_PER_TICK) {
                log.warn("ONVIF 探活单轮提交已达上限 {}, 余下台数顺延下一轮", MAX_PER_TICK);
                break;
            }
            if (!inFlight.add(device.getId())) {
                continue;
            }
            submitted++;
            probePool.submit(() -> {
                try {
                    onvifProvider.probe(device);
                } catch (Exception e) {
                    log.error("ONVIF 探活异常: device={} err={}", device.getDeviceId(), e.getMessage());
                } finally {
                    inFlight.remove(device.getId());
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        probePool.shutdown();
    }
}
