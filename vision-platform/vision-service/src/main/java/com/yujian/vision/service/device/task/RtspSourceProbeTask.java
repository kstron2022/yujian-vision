package com.yujian.vision.service.device.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.onvif.DeviceProtocolConstants;
import com.yujian.vision.service.device.rtsp.RtspAccessProvider;
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
 * RTSP 拉流代理探活任务（M5-B1, docs/14 §6）: 对探活周期到期的<b>按需</b> RTSP 设备
 * （always_on=0）发 RTSP OPTIONS 探测, 防抖与在线翻转在 RtspAccessProvider.probe 内
 * （连续 N 次失败才判离线; 成功刷 keepalive+翻转上线并联动通道 status）。
 * 常驻设备（always_on=1）不参与——在线态由收敛循环按流活跃驱动（RtspAlwaysOnTask）。
 * 执行模型与 OnvifProbeTask 同构: tick 只做「到期筛选+提交」, 实际探测在独立 3 线程池执行
 * （全工程 @Scheduled 共用单线程调度池, 探测最坏 2×timeout 阻塞不拖迟 HA 租约续期）;
 * in-flight 去重 + 单轮上限 + Redisson tryLock(0) 互斥双实例 tick。
 */
@Slf4j
@Component
public class RtspSourceProbeTask {

    private static final String LOCK_KEY = "gb:lock:task:rtsp-probe";
    /** 单轮最多提交台数（极端批量离线时余下台数顺延下一轮） */
    private static final int MAX_PER_TICK = 50;

    private final GbDeviceMapper deviceMapper;
    private final RtspAccessProvider rtspProvider;
    private final RedissonClient redisson;
    /** 探测专用池: 单设备挂死最多占一个线程一个超时窗口 */
    private final ExecutorService probePool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "rtsp-probe");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public RtspSourceProbeTask(GbDeviceMapper deviceMapper, RtspAccessProvider rtspProvider,
            RedissonClient redisson) {
        this.deviceMapper = deviceMapper;
        this.rtspProvider = rtspProvider;
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
                        .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.RTSP)
                        .and(w -> w.isNull(GbDeviceEntity::getAlwaysOn)
                                .or().eq(GbDeviceEntity::getAlwaysOn, 0))
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
            if (last != null && last.plusSeconds(interval).isAfter(now)) {
                continue;
            }
            if (submitted >= MAX_PER_TICK) {
                log.warn("RTSP 探活单轮提交已达上限 {}, 余下台数顺延下一轮", MAX_PER_TICK);
                break;
            }
            if (!inFlight.add(device.getId())) {
                continue;
            }
            submitted++;
            probePool.execute(() -> {
                try {
                    rtspProvider.probe(device);
                } catch (Throwable t) {
                    // Throwable 而非 Exception: submit 的 Future 会静默吞掉 Error 并杀死
                    // worker 线程(每轮重建), 零日志不可诊断(242 联调实测); execute+catch Throwable
                    // 保证任何 LinkageError/初始化失败都显式留痕且 worker 存活。
                    log.error("RTSP 探活异常: device={} err={}", device.getDeviceId(),
                            t.toString(), t);
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
