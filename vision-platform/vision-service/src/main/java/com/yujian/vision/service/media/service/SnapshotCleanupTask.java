package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.media.config.SnapshotProperties;
import com.yujian.vision.service.media.entity.GbChannelSnapshotEntity;
import com.yujian.vision.service.media.mapper.GbChannelSnapshotMapper;
import com.yujian.vision.service.record.CloudRecordService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 截图保留清理任务（M3-B7 阶段2）。形制克隆 CloudRecordRetentionTask:
 * Redisson 分布式锁 tryLock(0) + finally unlock, 单方执行（HA 双实例只有一方抢到锁）。
 *
 * <p>表容量有界（PK=channel_db_id 每通道一行, 千级）不适用分批——一次全量。三个收扫面:</p>
 * <ol>
 *   <li>失效行: 通道已删/不存在 → 删行+连删文件（N+1 但千级日扫可接受）; 原图与缩略图都缺失 → 删行自愈</li>
 *   <li>孤儿文件: 目录中存在但无任何行引用, 且 mtime 早于 1h 保护窗口（防"文件刚写、行未 upsert"竞态误删）</li>
 *   <li>开关关闭 = 功能整体下线, 目录不再收扫（enabled=false 直接 return）</li>
 * </ol>
 */
@Slf4j
@Component
public class SnapshotCleanupTask {

    private static final String LOCK_KEY = "gb:lock:task:snapshot-retention";
    /** 孤儿文件保护窗口: 跳过 1h 内新写的文件 */
    private static final Duration ORPHAN_MIN_AGE = Duration.ofHours(1);

    private final GbChannelSnapshotMapper snapshotMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final SnapshotProperties props;
    private final RedissonClient redisson;

    public SnapshotCleanupTask(GbChannelSnapshotMapper snapshotMapper, GbDeviceChannelMapper channelMapper,
            SnapshotProperties props, RedissonClient redisson) {
        this.snapshotMapper = snapshotMapper;
        this.channelMapper = channelMapper;
        this.props = props;
        this.redisson = redisson;
    }

    /** 每日 02:50 执行（错开 DataRetentionTask 02:30 / CloudRecordRetentionTask 02:40, 避免同峰写库） */
    @Scheduled(cron = "0 50 2 * * ?")
    public void reconcile() {
        if (!props.isEnabled()) {
            return;   // 开关关闭 = 功能整体下线, 目录不再收扫
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
            doSweep();
        } finally {
            lock.unlock();
        }
    }

    /** 包私有便于单测 */
    void doSweep() {
        Path dir = Paths.get(props.getDir());
        try {
            // 1. 失效行清理: 通道已删/不存在 → 行+文件一起回收; 原图+缩略图都缺失 → 删行自愈
            List<GbChannelSnapshotEntity> rows = snapshotMapper.selectList(
                    new LambdaQueryWrapper<>());
            Set<String> referenced = new HashSet<>();
            int rowsDeleted = 0;
            for (GbChannelSnapshotEntity row : rows) {
                if (isChannelGone(row.getChannelDbId())) {
                    snapshotMapper.deleteById(row.getChannelDbId());
                    CloudRecordService.deleteFileIfExists(dir.resolve(row.getFileName()).toString());
                    if (row.getThumbName() != null) {
                        CloudRecordService.deleteFileIfExists(dir.resolve(row.getThumbName()).toString());
                    }
                    rowsDeleted++;
                    continue;
                }
                if (missing(dir, row.getFileName()) && missing(dir, row.getThumbName())) {
                    // 文件都不在(被手工清理等): 行已无意义, 删行自愈, 无文件可删
                    snapshotMapper.deleteById(row.getChannelDbId());
                    rowsDeleted++;
                    continue;
                }
                referenced.add(row.getFileName());
                if (row.getThumbName() != null) {
                    referenced.add(row.getThumbName());
                }
            }
            // 2. 孤儿文件收扫: 目录中存在但无任何行引用, 且 mtime 早于保护窗口
            int filesDeleted = 0;
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        if (referenced.contains(p.getFileName().toString())) {
                            continue;
                        }
                        try {
                            long ageMs = System.currentTimeMillis()
                                    - Files.getLastModifiedTime(p).toMillis();
                            if (Duration.ofMillis(ageMs).compareTo(ORPHAN_MIN_AGE) < 0) {
                                // 1h 保护窗口内: 可能是"刚写文件、行未入库"的在途文件
                                continue;
                            }
                        } catch (IOException e) {
                            continue;
                        }
                        if (Files.deleteIfExists(p)) {
                            filesDeleted++;
                        }
                    }
                }
            }
            log.info("截图保留清理: 删除失效行 {}, 孤儿文件 {} (目录={})", rowsDeleted, filesDeleted, props.getDir());
        } catch (Exception e) {
            log.error("截图保留清理失败: 目录={}", props.getDir(), e);
        }
    }

    /** 通道已删/不存在判定（千级日扫 N+1 可接受; 对齐 CloudRecordRetentionTask 的逐行语义） */
    private boolean isChannelGone(Long channelDbId) {
        if (channelDbId == null) {
            return true;
        }
        GbDeviceChannelEntity ch = channelMapper.selectById(channelDbId);
        return ch == null || ch.getDeletedTime() != null;
    }

    private static boolean missing(Path dir, String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return !Files.isRegularFile(dir.resolve(name));
    }
}
