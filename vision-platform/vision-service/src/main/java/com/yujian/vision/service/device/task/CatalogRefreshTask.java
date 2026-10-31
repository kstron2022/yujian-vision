package com.yujian.vision.service.device.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.service.CatalogSyncService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期目录全量刷新任务：上级定时对在线设备/级联下级重发 Catalog 查询，全量比对收敛。
 * 背景（M1-10 评审补充）：GB28181 无强制的下级目录变更推送——很多下级平台改名/增删通道后不通知上级，
 * 只靠"注册上线时同步"会造成上级目录陈旧（名称对不上）。周期刷新不依赖下级配合，是最可靠的对齐手段；
 * 目录订阅通知（SUBSCRIBE/NOTIFY CatalogInfo）随 M3 级联模块实现（docs/09）。
 * 默认关闭，按环境配置开启（vision.catalog.auto-refresh-enabled）。
 */
@Slf4j
@Component
public class CatalogRefreshTask {

    private static final String LOCK_KEY = "gb:lock:task:catalog-refresh";

    private final GbDeviceMapper deviceMapper;
    private final CatalogSyncService catalogSyncService;
    private final RedissonClient redisson;
    private final boolean enabled;
    /** SIP 主备（M3-B6 阶段2）: 备台身份停跑（sync 触发设备侧 SIP 查询, 须本进程持栈） */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;

    public CatalogRefreshTask(GbDeviceMapper deviceMapper, CatalogSyncService catalogSyncService,
            RedissonClient redisson,
            @Value("${vision.catalog.auto-refresh-enabled:false}") boolean enabled,
            com.yujian.vision.service.ha.HaRuntimeService ha) {
        this.deviceMapper = deviceMapper;
        this.catalogSyncService = catalogSyncService;
        this.redisson = redisson;
        this.enabled = enabled;
        this.ha = ha;
    }

    /** 按配置 cron 触发（默认每日 04:17 低峰）；未启用时直接返回 */
    @Scheduled(cron = "${vision.catalog.refresh-cron:0 17 4 * * ?}")
    public void refresh() {
        if (!enabled || !ha.sipGatesOpen()) {
            return;
        }
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
            doRefresh();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void doRefresh() {
        List<GbDeviceEntity> online = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getOnline, 1)
                        .isNull(GbDeviceEntity::getDeletedTime));
        log.info("周期目录刷新开始: 在线设备 {} 台", online.size());
        int triggered = 0;
        int skipped = 0;
        for (GbDeviceEntity d : online) {
            try {
                catalogSyncService.sync(d.getDeviceId());
                triggered++;
            } catch (BizException e) {
                // 同步进行中/设备恰离线等业务态: 跳过不中断其余设备
                skipped++;
                log.debug("周期目录刷新跳过: device={} ({})", d.getDeviceId(), e.getMessage());
            } catch (Exception e) {
                skipped++;
                log.warn("周期目录刷新失败(继续下一台): device={}, {}", d.getDeviceId(), e.getMessage());
            }
        }
        log.info("周期目录刷新完成: 触发 {} 台, 跳过 {} 台", triggered, skipped);
    }
}
