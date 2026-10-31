package com.yujian.vision.web.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.web.security.ClientIpResolver;
import com.yujian.vision.web.security.LoginUser;
import com.yujian.vision.web.security.TrustedProxyProperties;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


/**
 * 限流切面单测：key 组装（IP/USER 维度）、yaml 阈值覆盖注解、总开关关闭放行、
 * 超限抛 1009 且不执行业务方法。注解样例挂在本类静态载体 Samples 上。
 */
class RateLimitAspectTest {

    /** 注解样例载体（真实注解元数据, 供切面反射读取） */
    static class Samples {

        @RateLimit(name = "login", limit = 2, windowSeconds = 30)
        public void byIp() {
        }

        @RateLimit(name = "play", limit = 1, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
        public void byUser() {
        }
    }

    private RateLimitStore store;
    private RateLimitProperties props;
    private ClientIpResolver resolver;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(RateLimitStore.class);
        props = new RateLimitProperties();
        resolver = new ClientIpResolver(new TrustedProxyProperties());
        aspect = new RateLimitAspect(store, props, resolver);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void belowLimitProceeds() throws Throwable {
        requestWithRemoteAddr("9.9.9.9");
        when(store.increment(anyString(), anyInt())).thenReturn(1L);

        Object out = aspect.around(pjp("byIp"), annotationOf("byIp"));

        assertSame("ok", out);
        verify(store).increment(eq("vision:rl:login:ip:9.9.9.9"), eq(30));
    }

    @Test
    void exceedingLimitThrows1009WithoutProceeding() throws Throwable {
        requestWithRemoteAddr("9.9.9.9");
        when(store.increment(anyString(), anyInt())).thenReturn(3L);

        ProceedingJoinPoint join = pjp("byIp");
        BizException e = assertThrows(BizException.class,
                () -> aspect.around(join, annotationOf("byIp")));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, e.getErrorCode());
        verify(join, never()).proceed();
        verify(store).increment(eq("vision:rl:login:ip:9.9.9.9"), eq(30));
    }

    @Test
    void storeFailureFailsOpenAndProceeds() throws Throwable {
        requestWithRemoteAddr("9.9.9.9");
        when(store.increment(anyString(), anyInt())).thenThrow(new RuntimeException("redis down"));
        ProceedingJoinPoint join = pjp("byIp");

        Object out = aspect.around(join, annotationOf("byIp"));

        // 计数通道故障 → 放行业务, 不向调用方抛异常
        assertSame("ok", out);
        verify(join).proceed();
    }

    @Test
    void yamlLimitOverridesAnnotationValue() throws Throwable {
        props.getLimits().put("login", 5);
        requestWithRemoteAddr("9.9.9.9");
        when(store.increment(anyString(), anyInt())).thenReturn(3L);

        Object out = aspect.around(pjp("byIp"), annotationOf("byIp"));

        assertSame("ok", out); // 注解 limit=2 会拒, yaml=5 放行 → 证明 yaml 覆盖生效
    }

    @Test
    void disabledProceedsWithoutCounting() throws Throwable {
        props.setEnabled(false);
        requestWithRemoteAddr("9.9.9.9");
        when(store.increment(anyString(), anyInt())).thenReturn(99L);

        Object out = aspect.around(pjp("byIp"), annotationOf("byIp"));

        assertSame("ok", out);
        verify(store, never()).increment(anyString(), anyInt());
    }

    @Test
    void userDimensionKeysByUserId() throws Throwable {
        requestWithRemoteAddr("192.168.5.5");
        LoginUser user = new LoginUser(7L, "admin", "管理员", Set.of(), Set.of(), false, "t-1");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, "N/A"));
        when(store.increment(anyString(), anyInt())).thenReturn(1L);

        aspect.around(pjp("byUser"), annotationOf("byUser"));

        verify(store).increment(eq("vision:rl:play:u:7"), eq(60));
    }

    @Test
    void anonymousUserDimensionFallsBackToIp() throws Throwable {
        requestWithRemoteAddr("192.168.5.5");
        when(store.increment(anyString(), anyInt())).thenReturn(1L);

        aspect.around(pjp("byUser"), annotationOf("byUser"));

        verify(store).increment(eq("vision:rl:play:ip:192.168.5.5"), eq(60));
    }

    @Test
    void noWebContextProceedsWithoutLimiting() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        when(store.increment(anyString(), anyInt())).thenReturn(99L);

        Object out = aspect.around(pjp("byIp"), annotationOf("byIp"));

        assertSame("ok", out);
        verify(store, never()).increment(anyString(), anyInt());
    }

    // ---- helpers ----

    private static void requestWithRemoteAddr(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static RateLimit annotationOf(String method) throws Exception {
        return Samples.class.getDeclaredMethod(method).getAnnotation(RateLimit.class);
    }

    private static ProceedingJoinPoint pjp(String method) throws Throwable {
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(Samples.class.getDeclaredMethod(method));
        ProceedingJoinPoint pjp = Mockito.mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }
}
