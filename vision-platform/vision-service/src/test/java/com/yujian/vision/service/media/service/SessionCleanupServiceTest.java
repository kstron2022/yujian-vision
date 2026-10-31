package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 会话清理三件套单元测试（docs/06 §2.1 / §8）：纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class SessionCleanupServiceTest {

    private static final long SESSION_ID = 10L;
    private static final long DEVICE_DB_ID = 5L;
    private static final String CHANNEL_ID = "34020000001320000078";
    private static final String STREAM_ID = "gb_play_x";
    private static final String SERVER_ID = "vision-zlm";
    private static final String SSRC = "0123456789";
    private static final String CALL_ID = "CALL1";

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private ZlmApiClient zlm;

    @Mock
    private SsrcPoolService ssrcPool;

    @Mock
    private InviteService invite;

    private SessionCleanupService service;

    @BeforeEach
    void setUp() {
        service = new SessionCleanupService(sessionMapper, deviceMapper, zlm, ssrcPool, invite,
                org.mockito.Mockito.mock(StreamProxyService.class));
    }

    @Test
    @DisplayName("close: BYE -> closeRtpServer -> 释放 SSRC -> 状态落库 全链路")
    void closeRunsFullCleanupChain() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(streamingSession());
        when(deviceMapper.selectById(DEVICE_DB_ID)).thenReturn(device());

        service.close(SESSION_ID, "CLOSED", "user_stop", "");

        verify(invite, times(1)).sendBye(CHANNEL_ID, CALL_ID, device(), "127.0.0.1", 5061);
        verify(zlm, times(1)).closeRtpServer(SERVER_ID, STREAM_ID);
        verify(ssrcPool, times(1)).release(SERVER_ID, SSRC);
        verify(zlm, never()).closeStreams(anyString(), anyString(), anyString());

        ArgumentCaptor<GbMediaSessionEntity> captor = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper, times(1)).updateById(captor.capture());
        GbMediaSessionEntity saved = captor.getValue();
        assertEquals(SESSION_ID, saved.getId());
        assertEquals("CLOSED", saved.getStatus());
        assertEquals("user_stop", saved.getStopReason());
        assertNotNull(saved.getStopTime());
    }

    @Test
    @DisplayName("closeAfterRestart: 跳过 BYE, 追加 close_streams 兜底")
    void closeAfterRestartSkipsByeAndClosesStreams() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(streamingSession());

        service.closeAfterRestart(SESSION_ID, "CLOSED", "platform_restart", "崩溃清理");

        verify(invite, never()).sendBye(anyString(), anyString(), any(GbDeviceEntity.class), anyString(), anyInt());
        verify(invite, never()).forgetTags(anyString());
        verify(zlm, times(1)).closeStreams(SERVER_ID, "rtp", STREAM_ID);
        verify(zlm, times(1)).closeRtpServer(SERVER_ID, STREAM_ID);
        verify(ssrcPool, times(1)).release(SERVER_ID, SSRC);

        ArgumentCaptor<GbMediaSessionEntity> captor = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper, times(1)).updateById(captor.capture());
        assertEquals("platform_restart", captor.getValue().getStopReason());
        assertEquals("CLOSED", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("closeAfterRestart(tryZlm=false): 跳过 ZLM 远端清理, 仍释放 SSRC 并落库")
    void closeAfterRestartSkipsZlmRemoteCleanupWhenNodeUnreachable() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(streamingSession());

        service.closeAfterRestart(SESSION_ID, "CLOSED", "platform_restart", "崩溃清理", false);

        verify(invite, never()).sendBye(anyString(), anyString(), any(GbDeviceEntity.class), anyString(), anyInt());
        verify(zlm, never()).closeRtpServer(anyString(), anyString());
        verify(zlm, never()).closeStreams(anyString(), anyString(), anyString());
        verify(ssrcPool, times(1)).release(SERVER_ID, SSRC);

        ArgumentCaptor<GbMediaSessionEntity> captor = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper, times(1)).updateById(captor.capture());
        assertEquals("platform_restart", captor.getValue().getStopReason());
        assertEquals("CLOSED", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("终态会话: close/closeAfterRestart 都直接跳过, 不触碰任何外部资源")
    void terminalSessionIsSkippedWithoutSideEffects() {
        GbMediaSessionEntity closed = streamingSession();
        closed.setStatus("CLOSED");
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(closed);

        service.close(SESSION_ID, "CLOSED", "user_stop", "");
        service.closeAfterRestart(SESSION_ID, "CLOSED", "platform_restart", "崩溃清理");

        verifyNoInteractions(zlm, invite, ssrcPool);
        verify(sessionMapper, never()).updateById(any(GbMediaSessionEntity.class));
    }

    @Test
    @DisplayName("BYE 发送失败: 继续后续清理, 状态仍落库")
    void closeContinuesWhenByeFails() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(streamingSession());
        when(deviceMapper.selectById(DEVICE_DB_ID)).thenReturn(device());
        doThrow(new RuntimeException("sip transport down")).when(invite)
                .sendBye(CHANNEL_ID, CALL_ID, device(), "127.0.0.1", 5061);

        assertDoesNotThrow(() -> service.close(SESSION_ID, "FAILED", "device_offline", "bye error"));

        verify(zlm, times(1)).closeRtpServer(SERVER_ID, STREAM_ID);
        verify(ssrcPool, times(1)).release(SERVER_ID, SSRC);

        ArgumentCaptor<GbMediaSessionEntity> captor = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper, times(1)).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("device_offline", captor.getValue().getStopReason());
    }

    private GbMediaSessionEntity streamingSession() {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(SESSION_ID);
        s.setStatus("STREAMING");
        s.setCallId(CALL_ID);
        s.setDeviceDbId(DEVICE_DB_ID);
        s.setChannelId(CHANNEL_ID);
        s.setStreamId(STREAM_ID);
        s.setServerId(SERVER_ID);
        s.setSsrc(SSRC);
        s.setApp("rtp");
        return s;
    }

    private GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(DEVICE_DB_ID);
        d.setIp("127.0.0.1");
        d.setPort(5061);
        return d;
    }
}
