package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.service.record.CloudRecordService;
import com.yujian.vision.service.record.entity.GbCloudRecordEntity;
import com.yujian.vision.service.record.mapper.GbCloudRecordMapper;
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
 * 云端录像保留策略清理（docs/06 §7 数据归档, docs/09 M2 #3）。
 * <p>独立成任务而不并入 DataRetentionTask: 本任务逐行带文件系统删除 IO（语义是"库行+落盘文件"
 * 一起回收, 与纯 SQL 批删不同）, 保留期口径也不同（云端录像默认 30 天）; 分开互不影响,
 * 也避免触碰 B1 已验证稳定的 DataRetentionTask。风格对齐: Redisson 分布式锁 + 分批删除 + 表级 try/catch。</p>
 */
@Slf4j
@Component
public class CloudRecordRetentionTask {

    private static final String LOCK_KEY = "gb:lock:task:cloud-record-retention";
    /** 单批删除行数 */
    private static final int BATCH = 500;

    private final GbCloudRecordMapper recordMapper;
    private final RedissonClient redisson;
    private final int retainDays;

    public CloudRecordRetentionTask(GbCloudRecordMapper recordMapper, RedissonClient redisson,
            @Value("${vision.retention.cloud-record-days:30}") int retainDays) {
        this.recordMapper = recordMapper;
        this.redisson = redisson;
        this.retainDays = retainDays;
    }

    /** 每日 02:40 执行（错开 DataRetentionTask 02:30, 避免同峰写库） */
    @Scheduled(cron = "0 40 2 * * ?")
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
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retainDays);
        long total = 0;
        try {
            while (true) {
                // 先取 id+file_path: 文件路径必须读出来才能删文件（不能像纯 SQL 批删那样只按条件删）
                List<GbCloudRecordEntity> rows = recordMapper.selectList(new QueryWrapper<GbCloudRecordEntity>()
                        .select("id", "file_path")
                        .and(w -> w.lt("end_time", cutoff)
                                .or(q -> q.isNull("end_time").lt("create_time", cutoff)))
                        .last("LIMIT " + BATCH));
                if (rows.isEmpty()) {
                    break;
                }
                // B3 P1: 先删行后删文件 —— 行删失败(异常)时本轮文件一个不动, 不产生"文件删了行还在"
                // 的悬挂; 反向(文件先删)删行失败会留下孤儿文件且行下次还会被重选出来。
                // 文件缺失/删除失败仅记日志, 不影响行删除（下次清扫自然收敛）。
                int n = recordMapper.deleteByIds(rows.stream().map(GbCloudRecordEntity::getId).toList());
                total += n;
                for (GbCloudRecordEntity row : rows) {
                    CloudRecordService.deleteFileIfExists(row.getFilePath());
                }
                if (rows.size() < BATCH) {
                    break;
                }
            }
            log.info("数据保留清理: 表=gb_cloud_record 删除 {} 条 (保留 {} 天)", total, retainDays);
        } catch (Exception e) {
            log.error("数据保留清理失败: 表=gb_cloud_record", e);
        }
    }
}
