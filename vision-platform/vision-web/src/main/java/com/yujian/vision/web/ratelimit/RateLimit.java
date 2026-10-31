package com.yujian.vision.web.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级限流（docs/10 §1: 登录/点播接口限流, M1）。
 * Redis 固定窗口计数, 维度=IP（登录）或用户（点播）; 触发后抛 1009 业务码, 由全局异常处理器统一出包络。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流名（进 Redis key: vision:rl:{name}:*） */
    String name();

    /** 窗口内最大请求数 */
    int limit();

    /** 窗口秒数 */
    int windowSeconds() default 60;

    /** 计数维度 */
    Dimension dimension() default Dimension.IP;

    enum Dimension { IP, USER }
}
