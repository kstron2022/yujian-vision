package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 点播信令发送层单元测试（docs/05 §4 + C5.1 无 tag 守卫）：纯 Mockito, 不起 Spring/SIP 栈。
 */
@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private SipTransportProvider transport;

    @Mock
    private GbMediaServerMapper serverMapper;

    private GbPlatformProperties platform;

    private InviteService service;

    @BeforeEach
    void setUp() {
        platform = new GbPlatformProperties();
        service = new InviteService(transport, platform, serverMapper);
    }

    private GbMediaSessionEntity session() {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setChannelId("34020000001320000001");
        s.setTcpMode(0);
        s.setRtpPort(50000);
        s.setSsrc("0123456789");
        return s;
    }

    private GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setIp("127.0.0.1");
        d.setPort(5061);
        return d;
    }

    private GbMediaServerEntity node() {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setPublicIp("10.0.0.10");
        return n;
    }

    @Test
    @DisplayName("tagStore 有对话条目 → BYE 发出且复用 fromTag")
    void sendsByeWhenTagExists() {
        InviteService.InviteHandle handle = service.sendInvite(session(), node(), device(),
                device().getIp(), device().getPort());

        service.sendBye("34020000001320000001", handle.callId(), device(), device().getIp(), device().getPort());

        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(captor.capture());
        SipMessageContext bye = captor.getAllValues().get(1);
        assertEquals("BYE", bye.getMethod());
        assertNotNull(bye.getFromTag());
        assertEquals(handle.fromTag(), bye.getFromTag());
    }

    @Test
    @DisplayName("tagStore 无条目(对话已结束/重启) → 跳过 BYE 不发送")
    void skipsByeWhenTagMissing() {
        service.sendBye("34020000001320000001", "unknown-call-id", device(), device().getIp(), device().getPort());

        verify(transport, never()).send(any(SipMessageContext.class));
    }

    @Test
    @DisplayName("BYE 后 forgetTags → 再次 BYE 不发送(防重复清理竞态)")
    void skipsByeAfterTagsForgotten() {
        InviteService.InviteHandle handle = service.sendInvite(session(), node(), device(),
                device().getIp(), device().getPort());
        service.forgetTags(handle.callId());

        service.sendBye("34020000001320000001", handle.callId(), device(), device().getIp(), device().getPort());

        // 仅 INVITE 一次, BYE 被守卫拦截
        verify(transport, times(1)).send(any(SipMessageContext.class));
    }

    @Test
    @DisplayName("markAnswered → isAnswered=true(重传 200 OK 补发 ACK / 超时扫描判据)")
    void markAnsweredMakesCallIdAnswered() {
        String callId = service.sendInvite(session(), node(), device(), device().getIp(), device().getPort()).callId();

        // 标记前未应答
        assertFalse(service.isAnswered(callId));
        service.markAnswered(callId);
        assertTrue(service.isAnswered(callId));
    }

    @Test
    @DisplayName("forgetTags 一并清理已应答标记(对话关闭即清理, 防跨对话串扰)")
    void forgetTagsClearsAnsweredFlag() {
        String callId = service.sendInvite(session(), node(), device(), device().getIp(), device().getPort()).callId();
        service.markAnswered(callId);
        assertTrue(service.isAnswered(callId));

        service.forgetTags(callId);

        assertFalse(service.isAnswered(callId));
    }

    @Test
    @DisplayName("markAnswered/isAnswered null 安全")
    void answeredApiIsNullSafe() {
        service.markAnswered(null);
        assertFalse(service.isAnswered(null));
    }

    // ---------------------------------------------------------------- 回放（B2-a）

    private GbMediaSessionEntity playbackSession() {
        GbMediaSessionEntity s = session();
        s.setSessionType(GbMediaSessionEntity.TYPE_PLAYBACK);
        s.setTcpMode(1);
        s.setServerId("vision-zlm");
        s.setPlaybackStart(java.time.LocalDateTime.of(2026, 9, 9, 8, 0, 0));
        s.setPlaybackEnd(java.time.LocalDateTime.of(2026, 9, 9, 9, 0, 0));
        return s;
    }

    @Test
    @DisplayName("sendInfo: 复用对话 tag + MANSRTSP Content-Type + body 原样透传")
    void sendsInfoInDialog() {
        InviteHandleFixture f = invite(playbackSession());
        service.sendInfo("34020000001320000001", f.callId(), device(), device().getIp(), device().getPort(), "<Control/>");

        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(captor.capture());
        SipMessageContext info = captor.getAllValues().get(1);
        assertEquals("INFO", info.getMethod());
        assertEquals(f.callId(), info.getCallId());
        assertEquals(f.fromTag(), info.getFromTag());
        assertTrue(info.isInDialog());
        assertEquals("<Control/>", info.getBody());
        assertEquals("Application/MANSRTSP+xml", info.getContentType());
    }

    @Test
    @DisplayName("sendInfo: 无 tag 守卫(对话已结束) → 跳过发送")
    void skipsInfoWhenTagMissing() {
        service.sendInfo("34020000001320000001", "unknown-call-id", device(), device().getIp(), device().getPort(), "x");
        verify(transport, never()).send(any(SipMessageContext.class));
    }

    @Test
    @DisplayName("回放 INVITE: SDP 用 playback 模板(s=Playback + t=起止 epoch 秒)")
    void playbackInviteUsesPlaybackSdp() {
        GbMediaSessionEntity s = playbackSession();
        long startEpoch = s.getPlaybackStart().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        long endEpoch = s.getPlaybackEnd().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        invite(s);
        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(captor.capture());
        String sdp = captor.getValue().getBody();
        assertTrue(sdp.contains("s=Playback"));
        assertTrue(sdp.contains("t=" + startEpoch + " " + endEpoch), sdp);
    }

    @Test
    @DisplayName("实况 INVITE: SDP 仍为 play 模板(t=0 0, s=Play)")
    void liveInviteUsesPlaySdp() {
        invite(session());
        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(captor.capture());
        assertTrue(captor.getValue().getBody().contains("s=Play"));
        assertTrue(captor.getValue().getBody().contains("t=0 0"));
    }

    @Test
    @DisplayName("sendReInvite: 同对话(callId/tag 不变) + t=行 = startOverride ~ 原窗口止")
    void reInviteOverridesStart() {
        GbMediaSessionEntity s = playbackSession();
        InviteHandleFixture f = invite(s);
        when(serverMapper.selectOne(any())).thenReturn(node());
        java.time.LocalDateTime seek = s.getPlaybackStart().plusMinutes(10);
        service.sendReInvite(s, f.callId(), device(), device().getIp(), device().getPort(), seek);

        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(captor.capture());
        SipMessageContext reInvite = captor.getAllValues().get(1);
        assertEquals("INVITE", reInvite.getMethod());
        assertEquals(f.callId(), reInvite.getCallId());
        assertTrue(reInvite.isInDialog());
        long seekEpoch = seek.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        long endEpoch = s.getPlaybackEnd().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        assertTrue(reInvite.getBody().contains("t=" + seekEpoch + " " + endEpoch), reInvite.getBody());
    }

    @Test
    @DisplayName("sendReInvite: 无 tag 守卫 → 跳过发送")
    void skipsReInviteWhenTagMissing() {
        service.sendReInvite(playbackSession(), "unknown-call-id", device(), device().getIp(), device().getPort(), null);
        verify(transport, never()).send(any(SipMessageContext.class));
    }

    /** INVITE 发送句柄（callId/fromTag 取值用） */
    private record InviteHandleFixture(String callId, String fromTag) {
    }

    private InviteHandleFixture invite(GbMediaSessionEntity s) {
        var handle = service.sendInvite(s, node(), device(), device().getIp(), device().getPort());
        return new InviteHandleFixture(handle.callId(), handle.fromTag());
    }
}
