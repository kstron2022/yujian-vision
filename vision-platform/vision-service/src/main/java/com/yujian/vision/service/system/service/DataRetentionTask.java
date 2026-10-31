package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.entity.SysLoginLogEntity;
import com.yujian.vision.service.system.entity.SysNoticeEntity;
import com.yujian.vision.service.system.entity.SysOperLogEntity;
import com.yujian.vision.service.system.entity.SysUserMessageEntity;
import com.yujian.vision.service.system.mapper.SysLoginLogMapper;
import com.yujian.vision.service.system.mapper.SysNoticeMapper;
import com.yujian.vision.service.system.mapper.SysOperLogMapper;
import com.yujian.vision.service.system.mapper.SysUserMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据保留清理任务（docs/06 §7 数据归档）：操作/登录日志 ≥6 个月(等保二级), 媒体会话 30 天, 站内通知 90 天。
 * 分批 DELETE ... LIMIT 1000 循环到删尽, 避免长事务/大范围行锁; 表级 try/catch 互不影响。
 * Redisson 分布式锁保证多实例部署时单实例执行。
 */
@Slf4j
@Component
public class DataRetentionTask {

    private static final String LOCK_KEY = "gb:lock:task:retention";
    /** 单批删除行数 */
    private static final int BATCH = 1000;
    /** 媒体会话仅清理终态（活跃/进行中会话永不误删） */
    private static final Set<String> TERMINAL = Set.of("CLOSED", "FAILED", "CANCELLED");

    private final SysOperLogMapper operLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final GbMediaSessionMapper sessionMapper;
    private final SysNoticeMapper noticeMapper;
    private final SysUserMessageMapper userMessageMapper;
    private final RedissonClient redisson;
    private final int operLogDays;
    private final int loginLogDays;
    private final int mediaSessionDays;
    private final int noticeDays;

    public DataRetentionTask(SysOperLogMapper operLogMapper, SysLoginLogMapper loginLogMapper,
            GbMediaSessionMapper sessionMapper, SysNoticeMapper noticeMapper,
            SysUserMessageMapper userMessageMapper, RedissonClient redisson,
            @Value("${vision.retention.oper-log-days:190}") int operLogDays,
            @Value("${vision.retention.login-log-days:190}") int loginLogDays,
            @Value("${vision.retention.media-session-days:30}") int mediaSessionDays,
            @Value("${vision.retention.notice-days:90}") int noticeDays) {
        this.operLogMapper = operLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.sessionMapper = sessionMapper;
        this.noticeMapper = noticeMapper;
        this.userMessageMapper = userMessageMapper;
        this.redisson = redisson;
        this.operLogDays = operLogDays;
        this.loginLogDays = loginLogDays;
        this.mediaSessionDays = mediaSessionDays;
        this.noticeDays = noticeDays;
    }

    /** 每日 02:30 执行 */
    @Scheduled(cron = "0 30 2 * * ?")
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
        LocalDateTime now = LocalDateTime.now();
        try {
            long n = purgeOperLogs(now);
            log.info("数据保留清理: 表=sys_oper_log 删除 {} 条 (保留 {} 天)", n, operLogDays);
        } catch (Exception e) {
            log.error("数据保留清理失败: 表=sys_oper_log", e);
        }
        try {
            long n = purgeLoginLogs(now);
            log.info("数据保留清理: 表=sys_login_log 删除 {} 条 (保留 {} 天)", n, loginLogDays);
        } catch (Exception e) {
            log.error("数据保留清理失败: 表=sys_login_log", e);
        }
        try {
            long n = purgeMediaSessions(now);
            log.info("数据保留清理: 表=gb_media_session 删除 {} 条 (保留 {} 天)", n, mediaSessionDays);
        } catch (Exception e) {
            log.error("数据保留清理失败: 表=gb_media_session", e);
        }
        try {
            long n = purgeNotices(now);
            log.info("数据保留清理: 表=sys_notice 删除 {} 条 (保留 {} 天)", n, noticeDays);
        } catch (Exception e) {
            log.error("数据保留清理失败: 表=sys_notice", e);
        }
    }

    /** 操作日志: create_time < cutoff, 分批删到删尽 */
    private long purgeOperLogs(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(operLogDays);
        long total = 0;
        while (true) {
            int n = operLogMapper.delete(new QueryWrapper<SysOperLogEntity>()
                    .lt("create_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    /** 登录日志: create_time < cutoff, 分批删到删尽 */
    private long purgeLoginLogs(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(loginLogDays);
        long total = 0;
        while (true) {
            int n = loginLogMapper.delete(new QueryWrapper<SysLoginLogEntity>()
                    .lt("create_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    /** 媒体会话: 终态 + stop_time < cutoff, 分批删到删尽 */
    private long purgeMediaSessions(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(mediaSessionDays);
        long total = 0;
        while (true) {
            int n = sessionMapper.delete(new QueryWrapper<GbMediaSessionEntity>()
                    .in("status", TERMINAL)
                    .lt("stop_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0) {
                break;
            }
        }
        return total;
    }

    /** 站内通知: create_time < cutoff 分批选 id → 先删扇出已读态再删通知本体, 分批删到删尽 */
    private long purgeNotices(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(noticeDays);
        long total = 0;
        while (true) {
            List<Long> ids = noticeMapper.selectList(new QueryWrapper<SysNoticeEntity>()
                            .select("id")
                            .lt("create_time", cutoff)
                            .last("LIMIT " + BATCH))
                    .stream().map(SysNoticeEntity::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            userMessageMapper.delete(new QueryWrapper<SysUserMessageEntity>().in("notice_id", ids));
            int n = noticeMapper.delete(new QueryWrapper<SysNoticeEntity>()
                    .lt("create_time", cutoff)
                    .last("LIMIT " + BATCH));
            total += n;
            if (n == 0 || ids.size() < BATCH) {
                break;
            }
        }
        return total;
    }
}
