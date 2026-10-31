package com.yujian.vision.service.openapi.service;

import com.yujian.vision.service.openapi.entity.OpenApiLogEntity;
import com.yujian.vision.service.openapi.mapper.OpenApiLogMapper;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开放接口调用日志异步落库（docs/15 步骤2）：单线程 daemon executor,
 * 队列满丢弃+计数 WARN（日志量上限=频控 QPS, 丢弃策略优先保主流程）,
 * 落库失败仅记日志不影响调用方（docs/15 风险表: 高频写入治理）。
 * 仅 vision.openapi.enabled=true 时装配。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenApiLogService {

    /** 队列容量: 频控 QPS 上限量级 × 突发余量 */
    private static final int QUEUE_CAPACITY = 10_000;
    /** 丢弃计数 WARN 步长（避免日志风暴: 首次与每 1000 次告警） */
    private static final long DROP_WARN_STEP = 1_000;
    /** param 落库截断长度（列 VARCHAR(1024), 留余量） */
    private static final int PARAM_MAX_LEN = 1000;

    private final OpenApiLogMapper logMapper;
    private final ExecutorService executor;
    private final AtomicLong dropCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    public OpenApiLogService(OpenApiLogMapper logMapper) {
        this.logMapper = logMapper;
        RejectedExecutionHandler discardWithCount = (r, e) -> warnDrop();
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "openapi-log");
                    t.setDaemon(true);
                    return t;
                },
                discardWithCount);
    }

    /** 异步记录一条调用日志（永不抛出; 队列满静默丢弃计数; remark=拒绝原因/空） */
    public void record(Long appId, String appKey, String method, String path,
            String param, int resultCode, String clientIp, int costMs, String remark) {
        executor.execute(() -> {
            try {
                OpenApiLogEntity e = new OpenApiLogEntity();
                e.setAppId(appId);
                e.setAppKey(appKey);
                e.setMethod(method);
                e.setPath(path);
                e.setParam(truncate(param));
                e.setResultCode(resultCode);
                e.setClientIp(clientIp);
                e.setCostMs(costMs);
                e.setRemark(remark == null ? "" : remark);
                logMapper.insert(e);
            } catch (Exception ex) {
                // 落库失败不影响主流程; 计数可见即可
                if (failCount.incrementAndGet() % DROP_WARN_STEP == 1) {
                    log.warn("open_api_log 落库失败(累计 {} 次): {}", failCount.get(), ex.getMessage());
                }
            }
        });
    }

    /** 分页查询（管理页; appKey/resultCode 可选筛选, 时间倒序） */
    public com.baomidou.mybatisplus.core.metadata.IPage<OpenApiLogEntity> page(int page, int count,
            String appKey, Integer resultCode) {
        return logMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, count),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenApiLogEntity>()
                        .eq(appKey != null && !appKey.isBlank(), OpenApiLogEntity::getAppKey, appKey)
                        .eq(resultCode != null, OpenApiLogEntity::getResultCode, resultCode)
                        .orderByDesc(OpenApiLogEntity::getId));
    }

    /** 已丢弃条数（观测用） */
    public long dropCount() {
        return dropCount.get();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void warnDrop() {
        long n = dropCount.incrementAndGet();
        if (n == 1 || n % DROP_WARN_STEP == 0) {
            log.warn("open_api_log 队列满, 已丢弃 {} 条调用日志(上限=频控 QPS, 量级可控)", n);
        }
    }

    /** 按 UTF-8 字节截断到列宽内（URL 编码的中文 3 字节/字符, 按字符截会超 VARCHAR(1024)） */
    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= PARAM_MAX_LEN) {
            return s;
        }
        String out = new String(bytes, 0, PARAM_MAX_LEN, java.nio.charset.StandardCharsets.UTF_8);
        // 末字符可能被截半, 去掉替换符; 长度按字符已 ≤ PARAM_MAX_LEN
        return out.endsWith("�") ? out.substring(0, out.length() - 1) : out;
    }
}
