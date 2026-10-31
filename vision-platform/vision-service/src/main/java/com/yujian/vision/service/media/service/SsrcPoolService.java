package com.yujian.vision.service.media.service;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * SSRC 资源池（docs/06 §4）：10 位十进制, 首位 0=实况 1=回放,
 * Redis INCR 取序号 + HSETNX 占用, 冲突取下一个。
 */
@Service
public class SsrcPoolService {

    private static final String SEQ_KEY = "gb:ssrc:seq:%s:%s";
    private static final String INUSE_KEY = "gb:ssrc:inuse:%s";
    private static final int MAX_RETRY = 3;

    private final RedissonClient redisson;

    public SsrcPoolService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 分配实况 SSRC（首位 0） */
    public String allocLive(String serverId, String sessionIdKey) {
        return alloc("0", serverId, sessionIdKey);
    }

    /** 分配回放 SSRC（首位 1, M2） */
    public String allocPlayback(String serverId, String sessionIdKey) {
        return alloc("1", serverId, sessionIdKey);
    }

    /** 释放（与 closeRtpServer 成对, docs/06 §4） */
    public void release(String serverId, String ssrc) {
        if (ssrc == null || ssrc.isBlank()) {
            return;
        }
        redisson.<String, String>getMap(String.format(INUSE_KEY, serverId)).remove(ssrc);
    }

    /** 重建节点占用表（崩溃恢复, docs/06 §8.4）：活跃会话清理后应为空集, 直接清掉残留脏数据 */
    public void resetInUse(String serverId) {
        redisson.<String, String>getMap(String.format(INUSE_KEY, serverId)).delete();
    }

    /**
     * 节点当前占用 SSRC 数（docs/09 M2 #8 监控指标 vision.node.ssrc.allocated 数据源）。
     * Redis 异常原样上抛, 由调用方（NodeMetricsTask）兜底——指标采集不因缓存故障误报 0。
     */
    public int inUseCount(String serverId) {
        return redisson.<String, String>getMap(String.format(INUSE_KEY, serverId)).size();
    }

    private String alloc(String prefix, String serverId, String sessionIdKey) {
        RAtomicLong seq = redisson
                .getAtomicLong(String.format(SEQ_KEY, prefix.equals("0") ? "live" : "playback", serverId));
        for (int i = 0; i < MAX_RETRY; i++) {
            long n = seq.incrementAndGet();
            String ssrc = prefix + (100000000 + n % 900000000);
            String prev = redisson.<String, String>getMap(String.format(INUSE_KEY, serverId)).putIfAbsent(ssrc,
                    sessionIdKey);
            if (prev == null) {
                return ssrc;
            }
        }
        throw new BizException(ErrorCode.SSRC_ALLOC_FAILED, "SSRC 分配冲突超限");
    }
}
