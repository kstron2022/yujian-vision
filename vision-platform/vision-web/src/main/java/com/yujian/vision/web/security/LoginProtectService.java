package com.yujian.vision.web.security;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import java.time.Duration;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

/**
 * 登录防爆破锁定（docs/10 §1: 失败 5 次锁 10 分钟, Redis 计数, 维度=用户名+IP）。
 * <p>失败计数与首次 PEXPIRE 用 Lua 原子完成: 先例的 INCR + expire 两步之间失败会留下无 TTL 的 key
 * → 计数永不过期 → 账号被永久锁定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginProtectService {

    static final String FAIL_PREFIX = "vision:auth:fail:";
    static final int MAX_FAILS = 5;
    static final long LOCK_MILLIS = Duration.ofMinutes(10).toMillis();

    /** 原子 INCR + 首次 PEXPIRE（ARGV[1]=锁定窗口毫秒数） */
    public static final String INCR_PEXPIRE_LUA =
            "local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; return c";

    private final RedissonClient redisson;

    private String key(String username, String ip) {
        return FAIL_PREFIX + username + ":" + ip;
    }

    /** 锁定检查：达到阈值即拒绝（含未过期残留计数） */
    public void checkLocked(String username, String ip) {
        long fails = redisson.getAtomicLong(key(username, ip)).get();
        if (fails >= MAX_FAILS) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "失败次数过多，账号已锁定，请10分钟后再试");
        }
    }

    /** 登录失败计数，达到阈值锁定 10 分钟（Lua 原子 INCR + 首次 PEXPIRE） */
    public void onFail(String username, String ip) {
        RScript script = redisson.getScript(StringCodec.INSTANCE);
        long fails = script.eval(RScript.Mode.READ_WRITE, INCR_PEXPIRE_LUA, RScript.ReturnType.INTEGER,
                Collections.singletonList(key(username, ip)), String.valueOf(LOCK_MILLIS));
        if (fails == MAX_FAILS) {
            log.warn("登录失败达阈值, 账号锁定 10 分钟: user={}, ip={}", username, ip);
        }
    }

    /** 登录成功清零 */
    public void onSuccess(String username, String ip) {
        redisson.getAtomicLong(key(username, ip)).delete();
    }
}
