package com.yujian.vision.web.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.device.service.ChannelQueryService;
import com.yujian.vision.service.device.service.DeviceManageService;
import com.yujian.vision.service.device.service.GroupManageService;
import com.yujian.vision.service.device.service.PtzControlService;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.entity.OpenAppEntity;
import com.yujian.vision.service.openapi.service.OpenApiScopeService;
import com.yujian.vision.service.record.RecordQueryService;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 开放接口 controller 单测 (docs/15 步骤4): mock 各 service + 手工构造带 OpenAppAuth attribute
 * 的请求, 重点钉死 channel_scope 越权防线（scope 外通道点播被拒 403、scope 过滤器强制透传）。
 */
class OpenApiControllerTest {

    private final DeviceManageService deviceService = mock(DeviceManageService.class);
    private final ChannelQueryService channelService = mock(ChannelQueryService.class);
    private final GroupManageService groupService = mock(GroupManageService.class);
    private final MediaSessionService sessionService = mock(MediaSessionService.class);
    private final RecordQueryService recordService = mock(RecordQueryService.class);
    private final PtzControlService ptzService = mock(PtzControlService.class);
    private final OpenApiScopeService scopeService = mock(OpenApiScopeService.class);
    private final OpenApiController controller = new OpenApiController(deviceService, channelService,
            groupService, sessionService, recordService, ptzService, scopeService);

    private static OpenAppAuth auth() {
        OpenAppEntity e = new OpenAppEntity();
        e.setId(7L);
        e.setAppKey("k7");
        e.setAppSecret("s");
        e.setChannelScope("1,2");
        e.setStatus(1);
        return OpenAppAuth.from(e, "secret", "hmac-sm3");
    }

    private MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/openapi/v1/channels");
        r.setRequestURI("/openapi/v1/channels");
        r.setAttribute(OpenApiSignatureFilter.OPENAPI_APP_ATTRIBUTE, auth());
        return r;
    }

    private static ChannelDataScopeService.ScopeFilter scope(Set<Long> ids) {
        return new ChannelDataScopeService.ScopeFilter(false, ids);
    }

    @Test
    @DisplayName("channels: scope 过滤器强制透传 channelQueryService.page")
    void channelsAppliesScope() {
        when(scopeService.resolve(any())).thenReturn(scope(Set.of(1L, 2L)));
        when(channelService.page(any(), any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        controller.channels(null, null, null, null, 1, 20, req());
        verify(channelService).page(isNull(), isNull(), isNull(), isNull(), eq(1), eq(20),
                eq(scope(Set.of(1L, 2L))));
    }

    @Test
    @DisplayName("play: scope 外通道 → 403, 不触发点播")
    void playOutsideScopeRejected() {
        doThrow(new BizException(com.yujian.vision.common.api.ErrorCode.FORBIDDEN, "无该通道的数据权限"))
                .when(scopeService).checkAccess(any(), eq(999L));
        assertThrows(BizException.class,
                () -> controller.play(new OpenApiController.OpenPlayReq(999L, 1, 1), req()));
        verify(sessionService, never()).play(anyLong(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("play: scope 内通道 → 透传 play(缺省 tcpMode=1 主码流=1)")
    void playInsideScopeDelegates() {
        when(scopeService.resolve(any())).thenReturn(ChannelDataScopeService.ScopeFilter.ALL());
        when(sessionService.play(anyLong(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new MediaSessionService.PlayResult(1L, "st", "ON", java.util.Map.of(), "H264", "srv", ""));
        controller.play(new OpenApiController.OpenPlayReq(5L, null, null), req());
        verify(sessionService).play(eq(5L), eq(1), eq(1), isNull(), isNull());
    }

    @Test
    @DisplayName("ptz: scope 外通道 → 403")
    void ptzOutsideScopeRejected() {
        doThrow(new BizException(com.yujian.vision.common.api.ErrorCode.FORBIDDEN, "无该通道的数据权限"))
                .when(scopeService).checkAccess(any(), eq(9L));
        assertThrows(BizException.class,
                () -> controller.ptzControl(new OpenApiController.OpenPtzReq(9L, "stop", 50), req()));
        verify(ptzService, never()).control(anyLong(), any(), any());
    }

    @Test
    @DisplayName("playbackControl: 无活跃会话时跳过 scope 校验, 仍透传控制指令")
    void playbackControlNoSessionPassesThrough() {
        when(sessionService.channelDbIdOfStream("st1")).thenReturn(null);
        controller.playbackControl(new OpenApiController.OpenPlaybackControlReq(
                "st1", "PAUSE", java.math.BigDecimal.ONE, null), req());
        verify(sessionService).playbackControl(eq("st1"), eq("PAUSE"),
                eq(java.math.BigDecimal.ONE), isNull());
    }

    @Test
    @DisplayName("play/stop: 会话所在通道 scope 外 → 403, 不执行 stop")
    void playStopOutsideScopeRejected() {
        when(sessionService.channelDbIdOfStream("gb_play_x")).thenReturn(999L);
        doThrow(new BizException(com.yujian.vision.common.api.ErrorCode.FORBIDDEN, "无该通道的数据权限"))
                .when(scopeService).checkAccess(any(), eq(999L));
        assertThrows(BizException.class, () -> controller.playStop(
                new OpenApiController.OpenStopReq("gb_play_x"), req()));
        verify(sessionService, never()).stop(anyString(), any());
    }

    @Test
    @DisplayName("playback/stop: 会话所在通道 scope 内 → 放行 stop")
    void playbackStopInsideScopePasses() {
        when(sessionService.channelDbIdOfStream("st9")).thenReturn(2L);
        when(scopeService.resolve(any())).thenReturn(scope(Set.of(1L, 2L)));
        controller.playbackStop(new OpenApiController.OpenStopReq("st9"), req());
        verify(sessionService).stop(eq("st9"), isNull());
    }

    @Test
    @DisplayName("records: 时间格式非法 → 400")
    void recordsBadTimeRejected() {
        when(scopeService.resolve(any())).thenReturn(ChannelDataScopeService.ScopeFilter.ALL());
        BizException ex = assertThrows(BizException.class,
                () -> controller.records(1L, "2026-09-26 00:00:00", "bad", req()));
        assertEquals(400, ex.getErrorCode().getCode());
    }
}
