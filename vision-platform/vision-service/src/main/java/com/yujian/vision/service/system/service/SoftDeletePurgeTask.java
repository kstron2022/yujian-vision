package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.mapper.GbGroupMapper;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.entity.SysUserRoleEntity;
import com.yujian.vision.service.system.mapper.SysUserMapper;
import com.yujian.vision.service.system.mapper.SysUserRoleMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 软删数据物理回收任务：把 deleted_time 超过保留期的死行物理删除, 防 DB 尸体行无界膨胀
 * （来源=设备删除/NVR 通道目录重报重建/用户删除等软删操作; 每日 02:50, 与 02:30 日志清理、
 * 02:40 云录像清理错峰）。保留期默认 30 天, 留出误删恢复窗口（docs/02 §0）。
 * 分批 DELETE ... LIMIT 1000 循环到删尽, 表级 try/catch 互不影响;
 * Redisson 分布式锁保证多实例部署时单实例执行（口径同 DataRetentionTask）。
 * 覆盖面：gb_device / gb_device_channel / gb_group / sys_user（含其 sys_user_role 残留引用）;
 * 不含 sys_menu（低频配置数据）与 sys_notice（DataRetentionTask 已按 90 天整体清理）。
 */
@Slf4j
@Component
public class SoftDeletePurgeTask {

    private static final String LOCK_KEY = "gb:lock:task:soft-delete-purge";
    /** 单批删除行数 */
    private static final int BATCH = 1000;

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbGroupMapper groupMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RedissonClient redisson;
    private final int retainDays;

    public SoftDeletePurgeTask(GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            GbGroupMapper groupMapper, SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
            RedissonClient redisson,
            @Value("${vision.retention.soft-delete-days:30}") int retainDays) {
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.groupMapper = groupMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.redisson = redisson;
        this.retainDays = retainDays;
    }

    /** 每日 02:50 执行 */
    @Scheduled(cron = "0 50 2 * * ?")
    public void reconcile() {
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
            doSweep();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void doSweep() {
        try {
            long n = purgeTable(deviceMapper);
            log.info("软删回收: 表=gb_device 删除 {} 条 (保留 {} 天)", n, retainDays);
        } catch (Exception e) {
            log.error("软删回收失败: 表=gb_device", e);
        }
        try {
            long n = purgeTable(channelMapper);
            log.info("软删回收: 表=gb_device_channel 删除 {} 条 (保留 {} 天)", n, retainDays);
        } catch (Exception e) {
            log.error("软删回收失败: 表=gb_device_channel", e);
        }
        try {
            long n = purgeTable(groupMapper);
            log.info("软删回收: 表=gb_group 删除 {} 条 (保留 {} 天)", n, retainDays);
        } catch (Exception e) {
            log.error("软删回收失败: 表=gb_group", e);
        }
        try {
            long n = purgeUsers(LocalDateTime.now());
            log.info("软删回收: 表=sys_user 删除 {} 条 (保留 {} 天)", n, retainDays);
        } catch (Exception e) {
            log.error("软删回收失败: 表=sys_user", e);
        }
    }

    /** 通用软删死行分批回收: deleted_time 非空且早于保留期, 删到删尽 */
    private <T> long purgeTable(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        long total = 0;
        while (true) {
            int n = mapper.delete(new QueryWrapper<T>()
                    .isNotNull("deleted_time")
                    .lt("deleted_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    /** 软删用户回收: 分批选 id → 先删 sys_user_role 残留引用 → 删用户本体, 到删尽 */
    private long purgeUsers(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retainDays);
        long total = 0;
        while (true) {
            List<Long> ids = userMapper.selectList(new QueryWrapper<SysUserEntity>()
                            .select("id")
                            .isNotNull("deleted_time")
                            .lt("deleted_time", cutoff)
                            .last("LIMIT " + BATCH))
                    .stream().map(SysUserEntity::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            userRoleMapper.delete(new QueryWrapper<SysUserRoleEntity>().in("user_id", ids));
            int n = userMapper.delete(new QueryWrapper<SysUserEntity>()
                    .isNotNull("deleted_time")
                    .lt("deleted_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0 || ids.size() < BATCH) {
                break;
            }
        }
        return total;
    }
}
