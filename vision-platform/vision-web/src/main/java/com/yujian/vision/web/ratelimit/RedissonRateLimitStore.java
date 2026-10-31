package com.yujian.vision.web.ratelimit;

import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * Redis 固定窗口计数实现（RScript Lua 原子 INCR+PEXPIRE）。
 * <p>先例实现是 RAtomicLong.incrementAndGet() + 计数为 1 时 expire()：两步非原子, 中间失败会留下
 * 无 TTL 的 key → 窗口永不重置 → 永久限流/永久锁定。Lua 把 INCR 与首次 PEXPIRE 收进一个原子命令,
 * 窗口过期即自然重置。</p>
 */
@Component
@RequiredArgsConstructor
public class RedissonRateLimitStore implements RateLimitStore {

    /** 原子 INCR + 首次 PEXPIRE（ARGV[1]=窗口毫秒数） */
    public static final String INCR_PEXPIRE_LUA =
            "local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; return c";

    private final RedissonClient redisson;

    @Override
    public long increment(String key, int windowSeconds) {
        // 显式 StringCodec：key 字节与 RAtomicLong 的 rawName 一致（纯字符串, 不经全局 codec 序列化）,
        // 且 ARGV[1] 以原样字符串传给 PEXPIRE（默认 Kryo/Jackson codec 会加序列化头/引号导致 PEXPIRE 报错）
        RScript script = redisson.getScript(StringCodec.INSTANCE);
        Long count = script.eval(RScript.Mode.READ_WRITE, INCR_PEXPIRE_LUA, RScript.ReturnType.INTEGER,
                Collections.singletonList(key), String.valueOf(windowSeconds * 1000L));
        return count == null ? 0L : count;
    }
}
