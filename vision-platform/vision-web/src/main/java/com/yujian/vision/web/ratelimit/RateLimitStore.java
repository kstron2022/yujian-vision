package com.yujian.vision.web.ratelimit;

/**
 * 限流计数存储 SPI：固定窗口计数器, 首次计数时设 TTL。
 */
public interface RateLimitStore {

    /** 自增并返回计数; 返回值 > limit 即拒绝 */
    long increment(String key, int windowSeconds);
}
