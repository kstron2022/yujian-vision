package com.yujian.vision.service.openapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.yujian.vision.service.openapi.entity.OpenApiLogEntity;
import com.yujian.vision.service.openapi.mapper.OpenApiLogMapper;
import com.yujian.vision.service.openapi.service.OpenApiLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 开放接口调用日志异步落库单元测试（docs/15 步骤2）：纯 Mockito,
 * 异步用 Mockito timeout 等待单线程 executor 消费。
 */
@ExtendWith(MockitoExtension.class)
class OpenApiLogServiceTest {

    @Mock
    private OpenApiLogMapper logMapper;

    @Test
    @DisplayName("record: 异步落库字段完整; param 超长截断到 1000")
    void recordInsertsAsync() {
        OpenApiLogService svc = new OpenApiLogService(logMapper);
        String longParam = "x".repeat(2000);
        svc.record(7L, "k7", "GET", "/openapi/v1/devices", longParam, 0, "10.0.0.1", 55, "");

        ArgumentCaptor<OpenApiLogEntity> captor = ArgumentCaptor.forClass(OpenApiLogEntity.class);
        verify(logMapper, timeout(2000)).insert(captor.capture());
        OpenApiLogEntity e = captor.getValue();
        assertEquals(7L, e.getAppId());
        assertEquals("k7", e.getAppKey());
        assertEquals("GET", e.getMethod());
        assertEquals("/openapi/v1/devices", e.getPath());
        assertEquals(1000, e.getParam().length());
        assertEquals(0, e.getResultCode());
        assertEquals("10.0.0.1", e.getClientIp());
        assertEquals(55, e.getCostMs());
        svc.shutdown();
    }

    @Test
    @DisplayName("record: 落库异常不影响主流程(不抛出), 丢弃计数初始为 0")
    void recordNeverThrows() {
        OpenApiLogService svc = new OpenApiLogService(logMapper);
        doThrow(new RuntimeException("db down")).when(logMapper).insert(any(OpenApiLogEntity.class));
        assertDoesNotThrow(() ->
                svc.record(1L, "k", "GET", "/p", "", 401, "ip", 1, ""));
        assertDoesNotThrow(() ->
                svc.record(1L, "k", "GET", "/p", "", 401, "ip", 1, ""));
        assertEquals(0, svc.dropCount());
        svc.shutdown();
    }
}
