package com.yujian.vision.web.ratelimit;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.web.security.ClientIpResolver;
import com.yujian.vision.web.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 接口级限流切面（docs/10 §1: 登录/点播接口限流, M1）。
 * <p>Redis 固定窗口计数（{@link RateLimitStore}）, 计数 &gt; limit 抛 1009 由全局异常处理器出包络；
 * 维度=IP（经 {@link ClientIpResolver} 可信代理解析, 防 XFF 伪造绕过限流）或登录用户。
 * 计数异常时 fail-open 放行：旁路保护不因 Redis 抖动打挂业务。</p>
 * <p>Order(1)：先于审计切面（AuditAspect）执行, 被限流请求不产生审计日志。</p>
 */
@Slf4j
@Aspect
@Order(1)
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    static final String KEY_PREFIX = "vision:rl:";

    private final RateLimitStore store;
    private final RateLimitProperties props;
    private final ClientIpResolver clientIpResolver;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (!props.isEnabled()) {
            return pjp.proceed();
        }
        String dimensionValue = dimensionValue(rateLimit.dimension());
        if (dimensionValue == null) {
            // 非 Web 上下文（内部直调）无限流意义, 放行
            return pjp.proceed();
        }
        String key = KEY_PREFIX + rateLimit.name() + ":" + dimensionValue;
        int limit = effectiveLimit(rateLimit);
        long count;
        try {
            count = store.increment(key, rateLimit.windowSeconds());
        } catch (Exception e) {
            // fail-open: 计数通道故障只损失限流精度, 不放大成业务不可用
            log.warn("限流计数异常, 降级放行: {}", e.getMessage());
            return pjp.proceed();
        }
        if (count > limit) {
            log.warn("接口限流触发: name={}, key={}, count={}, limit={}/{}s",
                    rateLimit.name(), key, count, limit, rateLimit.windowSeconds());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return pjp.proceed();
    }

    /** 生效阈值: yaml vision.rate-limit.limits.{name} 覆盖注解兜底值 */
    private int effectiveLimit(RateLimit rateLimit) {
        return props.getLimits().getOrDefault(rateLimit.name(), rateLimit.limit());
    }

    /**
     * 维度取值：IP → "ip:{客户端IP}"; USER → "u:{userId}", 未认证回退 IP（登录接口即此形态）。
     * 无请求上下文返回 null（放行）。
     */
    private String dimensionValue(RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.USER) {
            LoginUser user = currentUser();
            if (user != null) {
                return "u:" + user.userId();
            }
        }
        HttpServletRequest request = currentRequest();
        return request == null ? null : "ip:" + clientIpResolver.resolve(request);
    }

    private LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser lu ? lu : null;
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                ? sra.getRequest()
                : null;
    }
}
