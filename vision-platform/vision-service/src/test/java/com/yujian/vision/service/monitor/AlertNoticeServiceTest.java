package com.yujian.vision.service.monitor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * Alertmanager webhook 告警转站内通知单元测试（docs/09 M2 #8）：纯 Mockito,
 * 不起 Spring 上下文/Redis/数据库。覆盖 firing 发布/指纹去重/resolved 静默/畸形报文自吞。
 */
@ExtendWith(MockitoExtension.class)
class AlertNoticeServiceTest {

    private static final String FIRING_BODY = """
            {
              "version": "4",
              "alerts": [
                {
                  "status": "firing",
                  "labels": {"alertname": "NodeStreamsHigh", "severity": "critical", "server": "vision-zlm"},
                  "annotations": {"description": "节点 vision-zlm 流数超过阈值"},
                  "startsAt": "2026-09-11T00:00:00Z",
                  "endsAt": "0001-01-01T00:00:00Z",
                  "fingerprint": "fingerprint-abc123"
                }
              ]
            }""";

    @Mock
    private NoticeService notices;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RBucket<String> bucket;

    private AlertNoticeService service;

    @BeforeEach
    void setUp() {
        service = new AlertNoticeService(notices, redisson, new ObjectMapper());
    }

    @Test
    @DisplayName("firing 告警 → 发布通知(type=PLATFORM_ALERT, critical=2, bizId=fingerprint)")
    void publishesFiringAlert() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);

        service.handle(FIRING_BODY);

        verify(notices, times(1)).publish(eq("PLATFORM_ALERT"), contains("NodeStreamsHigh"),
                contains("节点 vision-zlm 流数超过阈值"), eq(2), eq("fingerprint-abc123"));
    }

    @Test
    @DisplayName("同指纹 30 分钟去重窗口内第二次 → 静默不重复发布")
    void dedupesSameFingerprintWithinWindow() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true, false);

        service.handle(FIRING_BODY);
        service.handle(FIRING_BODY);

        verify(notices, times(1)).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("resolved 告警 → 不发通知, 不触碰 Redis")
    void ignoresResolvedAlerts() {
        String body = """
                {"alerts":[{"status":"resolved","labels":{"alertname":"NodeStreamsHigh","severity":"warning"},
                  "annotations":{"description":"已恢复"},"fingerprint":"fp-resolved-1"}]}""";

        service.handle(body);

        verifyNoInteractions(notices, redisson);
    }

    @Test
    @DisplayName("畸形报文/缺字段 → 自吞不抛, 不发通知")
    void swallowsMalformedPayloads() {
        assertDoesNotThrow(() -> service.handle("not-a-json"));
        assertDoesNotThrow(() -> service.handle("{\"alerts\":{}}"));
        assertDoesNotThrow(() -> service.handle(null));
        assertDoesNotThrow(() -> service.handle(""));

        verifyNoInteractions(notices);
        verify(redisson, never()).getBucket(anyString());
    }

    @Test
    @DisplayName("缺 description → labels 摘要兜底; 缺 severity 默认 warning=1; 缺 fingerprint 用 startsAt 兜底")
    void fallsBackForMissingFields() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);

        service.handle("""
                {"alerts":[{"status":"FIRING","labels":{"alertname":"ZlmDown"},
                  "startsAt":"2026-09-11T01:02:03Z"}]}""");

        verify(notices, times(1)).publish(eq("PLATFORM_ALERT"), contains("ZlmDown"),
                contains("alertname=ZlmDown"), eq(1), contains("ZlmDown@2026-09-11T01:02:03Z"));
    }
}
