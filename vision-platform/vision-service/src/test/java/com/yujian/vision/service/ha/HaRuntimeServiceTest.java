package com.yujian.vision.service.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.sip.config.SipStackProperties;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.core.env.Environment;

/**
 * HA 运行时单测（M3-B6 阶段2）：角色仲裁/租约续期/promote/demote/栈补起门禁。
 * 纯 Mockito, 不起 Spring 上下文/Redis。RBucket 链: redisson.getBucket(leaderKey) → bucket。
 */
@ExtendWith(MockitoExtension.class)
class HaRuntimeServiceTest {

    @Mock
    private SipTransportProvider transport;
    @Mock
    private SipStackProperties sipProps;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RBucket<String> bucket;
    @Mock
    private Environment env;

    private HaProperties props() {
        HaProperties p = new HaProperties();
        p.setEnabled(true);
        p.setRole("active");
        return p;
    }

    private HaRuntimeService active() {
        HaRuntimeService s = new HaRuntimeService(props(), sipProps, transport, redisson, env);
        lenient().when(env.getProperty("server.port", "8080")).thenReturn("8080");
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString());
        lenient().when(bucket.setIfAbsent(anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);
        s.run(null);
        return s;
    }

    private HaRuntimeService standby() {
        HaProperties p = props();
        p.setRole("standby");
        HaRuntimeService s = new HaRuntimeService(p, sipProps, transport, redisson, env);
        lenient().when(env.getProperty("server.port", "8080")).thenReturn("8080");
        s.run(null);
        return s;
    }

    private static SipStackProperties.StackSpec spec(String id) {
        SipStackProperties.StackSpec sp = new SipStackProperties.StackSpec();
        sp.setStackId(id);
        sp.setHost("0.0.0.0");
        sp.setPort(5060);
        sp.setTransport("UDP");
        return sp;
    }

    @Test
    @DisplayName("未启用 HA → 恒活跃身份, 全程不碰 Redis 与传输层")
    void disabledKeepsActiveAndNeverTouchesRedis() {
        HaProperties p = new HaProperties();
        p.setEnabled(false);
        HaRuntimeService s = new HaRuntimeService(p, sipProps, transport, redisson, env);
        lenient().when(env.getProperty("server.port", "8080")).thenReturn("8080");

        s.run(null);

        assertFalse(s.isStandby());
        assertTrue(s.sipGatesOpen());
        org.mockito.Mockito.verifyNoInteractions(redisson, transport);
    }

    @Test
    @DisplayName("启动抢到租约 → ACTIVE; 被他人持有 → STANDBY 且不允恢复")
    void leaseArbitratesBootRole() {
        HaRuntimeService a = active();
        assertTrue(a.sipGatesOpen());
        assertEquals("active", a.status().get("role"));

        HaProperties p = props();
        HaRuntimeService b = new HaRuntimeService(p, sipProps, transport, redisson, env);
        lenient().when(env.getProperty("server.port", "8080")).thenReturn("8080");
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(false);
        when(bucket.get()).thenReturn("other-host:8080");
        b.run(null);

        assertTrue(b.isStandby());
        assertFalse(b.mayRecoverAtBoot());
    }

    @Test
    @DisplayName("promote 拉起全部配置栈并写租约(幂等重入安全)")
    void promoteStartsStacksAndWritesLease() {
        HaRuntimeService s = standby();
        when(sipProps.getStacks()).thenReturn(List.of(spec("main")));

        s.promote();
        s.promote();

        verify(transport, times(2)).start(any());
        assertTrue(s.sipGatesOpen());
        assertEquals("active", s.status().get("role"));
    }

    @Test
    @DisplayName("demote 停栈; 租约仅自持时删除")
    void demoteStopsStacksAndDeletesOwnLease() {
        HaRuntimeService s = active();
        when(sipProps.getStacks()).thenReturn(List.of(spec("main")));
        when(bucket.get()).thenReturn(s.selfId());

        s.demote();

        verify(transport, times(1)).stop("main");
        verify(bucket, times(1)).delete();
        assertTrue(s.isStandby());
    }

    @Test
    @DisplayName("demote: 租约被他人持有 → 停栈但不删租约")
    void demoteKeepsOthersLease() {
        HaRuntimeService s = active();
        when(sipProps.getStacks()).thenReturn(List.of(spec("main")));
        when(bucket.get()).thenReturn("other-host:8080");

        s.demote();

        verify(transport, times(1)).stop("main");
        verify(bucket, never()).delete();
        assertTrue(s.isStandby());
    }

    @Test
    @DisplayName("栈补起仅活跃身份: ACTIVE 补起缺失栈, STANDBY 不动")
    void ensureStacksOnlyWhenActive() {
        HaRuntimeService s = active();
        when(sipProps.getStacks()).thenReturn(List.of(spec("main")));
        when(transport.isRunning("main")).thenReturn(false);

        s.ensureStacks();

        verify(transport, times(1)).start(any());

        HaRuntimeService b = standby();
        org.mockito.Mockito.clearInvocations(transport);
        b.ensureStacks();
        verify(transport, never()).start(any());
    }

    @Test
    @DisplayName("栈补起失败(bind 冲突) → 吞异常下一轮重试")
    void ensureStacksFailureSwallowed() {
        HaRuntimeService s = active();
        when(sipProps.getStacks()).thenReturn(List.of(spec("main")));
        when(transport.isRunning("main")).thenReturn(false);
        org.mockito.Mockito.doThrow(
                new BizException(com.yujian.vision.common.api.ErrorCode.SYSTEM_ERROR, "bind failed"))
                .when(transport).start(any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(s::ensureStacks);
        verify(transport, times(1)).start(any());
    }
}
