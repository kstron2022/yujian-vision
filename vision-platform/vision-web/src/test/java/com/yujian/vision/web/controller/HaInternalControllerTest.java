package com.yujian.vision.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yujian.vision.service.ha.HaRuntimeService;
import com.yujian.vision.service.media.service.MediaSessionService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * HaInternalController 单测（M3-B6 阶段2）：X-Vision-Ha-Token 恒定时间比对,
 * 缺失/错误一律 401 且不触 promote/demote; 正确 token 触发接管/回切/状态查询。
 * M3-B7 增补: /on-demand 备台委托建流口同鉴权, 建流失败回 code 非 0 而非抛。
 */
class HaInternalControllerTest {

    private static final String TOKEN = "ha-tok-123";

    private final HaRuntimeService ha = mock(HaRuntimeService.class);
    private final MediaSessionService sessions = mock(MediaSessionService.class);
    private final HaInternalController controller = new HaInternalController(ha, sessions, TOKEN);

    private static final Map<String, Object> ST = Map.of("role", "active");

    @Test
    @DisplayName("token 缺失/空白/错误 → 401 且不触 promote/demote/status")
    void authFailuresReturn401() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.start(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.start("  ").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.start("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.stop("wrong").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.status("wrong").getStatusCode());
        verify(ha, never()).promote();
        verify(ha, never()).demote();
    }

    @Test
    @DisplayName("正确 token: start→promote, stop→demote, status→运行态")
    void validTokenTriggersLifecycle() {
        org.mockito.Mockito.when(ha.promote()).thenReturn(ST);
        org.mockito.Mockito.when(ha.demote()).thenReturn(ST);
        org.mockito.Mockito.when(ha.status()).thenReturn(ST);

        assertEquals(HttpStatus.OK, controller.start(TOKEN).getStatusCode());
        verify(ha).promote();
        assertEquals(HttpStatus.OK, controller.stop(TOKEN).getStatusCode());
        verify(ha).demote();
        assertEquals(HttpStatus.OK, controller.status(TOKEN).getStatusCode());
        verify(ha).status();
        org.mockito.Mockito.verifyNoMoreInteractions(ha);
    }

    @Test
    @DisplayName("M3-B7 on-demand: token 错误 → 401 且不建流")
    void onDemandRejectsBadToken() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                controller.onDemand("wrong", Map.of("streamId", "gb_play_34020000001310000001"))
                        .getStatusCode());
        verify(sessions, never()).playOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("M3-B7 on-demand: 正确 token → 委托活跃实例建流并回 status/serverId")
    void onDemandDelegatesToSession() {
        org.mockito.Mockito.when(sessions.playOnDemand("gb_play_34020000001310000001", "vision-zlm", 2))
                .thenReturn(new MediaSessionService.PlayResult(7L, "gb_play_34020000001310000001",
                        "STREAMING", Map.of(), "H264", "vision-zlm"));

        ResponseEntity<com.yujian.vision.common.api.R<Map<String, Object>>> resp = controller.onDemand(
                TOKEN, Map.of("streamId", "gb_play_34020000001310000001",
                        "serverId", "vision-zlm", "streamProfile", 2));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        com.yujian.vision.common.api.R<Map<String, Object>> body = java.util.Objects.requireNonNull(resp.getBody());
        assertEquals(0, body.getCode());
        assertEquals("STREAMING", body.getData().get("status"));
        assertEquals("vision-zlm", body.getData().get("serverId"));
    }

    @Test
    @DisplayName("M3-B7 on-demand: 建流异常 → HTTP 200 但 code 非 0(调用方只记日志, 播放器重试自愈)")
    void onDemandFailureReturnsNonZeroCode() {
        org.mockito.Mockito.when(sessions.playOnDemand(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("无在线流媒体节点"));

        ResponseEntity<com.yujian.vision.common.api.R<Map<String, Object>>> resp =
                controller.onDemand(TOKEN, Map.of("streamId", "gb_play_34020000001310000001"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        org.junit.jupiter.api.Assertions.assertNotEquals(0,
                java.util.Objects.requireNonNull(resp.getBody()).getCode());
    }
}
