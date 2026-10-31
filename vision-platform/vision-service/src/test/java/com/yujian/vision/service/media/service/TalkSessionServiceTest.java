package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.sip.transport.SipEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TalkSessionService 编排单测（M3-B5）: INVITE s=Talk 发送、200 OK 应答解析、begin 推流、
 * 失败清理、设备 BYE 收尾。纯 Mockito 不起 Spring 上下文（同包测试惯例）。
 */
@ExtendWith(MockitoExtension.class)
class TalkSessionServiceTest {

    private static final String AUDIO = "gb_talk_src_34020000001320000001_ab12";

    @Mock GbMediaSessionMapper sessionMapper;
    @Mock GbDeviceMapper deviceMapper;
    @Mock GbDeviceChannelMapper channelMapper;
    @Mock GbMediaServerMapper serverMapper;
    @Mock SsrcPoolService ssrcPool;
    @Mock InviteService invite;
    @Mock SessionCleanupService cleanup;
    @Mock StreamEventPublisher publisher;
    @Mock RedissonClient redisson;
    @Mock RLock lock;

    private TalkSessionService svc;
    private GbMediaSessionEntity inserted;

    @BeforeAll
    static void initMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
    }

    private GbDeviceChannelEntity channel() {
        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setId(77L);
        ch.setDeviceDbId(11L);
        ch.setChannelId("34020000001320000001");
        ch.setStatus(1);
        return ch;
    }

    private GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(11L);
        d.setDeviceId("34020000002000000001");
        d.setOnline(1);
        d.setDeviceType(1);
        d.setIp("10.0.0.3");
        d.setPort(5060);
        return d;
    }

    private GbMediaServerEntity node() {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setId(1L);
        n.setServerId("srv-1");
        n.setStatus(1);
        n.setPublicIp("172.26.253.3");
        n.setHttpPort(8081);
        return n;
    }

    private GbMediaSessionEntity session(String status) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(99L);
        s.setSessionType(GbMediaSessionEntity.TYPE_TALK);
        s.setStreamId("gb_talk_34020000001320000001_ab12");
        s.setAudioStreamId(AUDIO);
        s.setApp("rtp");
        s.setDeviceDbId(11L);
        s.setChannelDbId(77L);
        s.setChannelId("34020000001320000001");
        s.setServerId("srv-1");
        s.setSsrc("0123456789");
        s.setStatus(status);
        return s;
    }

    private void stubStartHappyPath() {
        when(channelMapper.selectById(77L)).thenReturn(channel());
        when(deviceMapper.selectById(11L)).thenReturn(device());
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(serverMapper.selectOne(any())).thenReturn(node());

        when(sessionMapper.insert(any(GbMediaSessionEntity.class))).thenAnswer(inv -> {
            inserted = inv.getArgument(0);
            inserted.setId(99L);
            return 1;
        });
        when(invite.sendTalkInvite(any(), anyString(), any(), anyString(), anyInt())).thenReturn(inviteHandle());
    }

    private com.yujian.vision.service.media.service.InviteService.InviteHandle inviteHandle() {
        return new InviteService.InviteHandle("CALL-TALK", "tagA");
    }


    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        com.yujian.vision.service.monitor.VisionMetrics metrics =
                new com.yujian.vision.service.monitor.VisionMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        svc = new TalkSessionService(sessionMapper, deviceMapper, channelMapper, serverMapper,
                ssrcPool, invite, cleanup, publisher, metrics, redisson);
    }

    private static final String ANSWER_SDP = String.join("\r\n",
            "v=0",
            "o=34020000002000000001 0 0 IN IP4 10.20.30.40",
            "s=Talk",
            "c=IN IP4 10.20.30.40",
            "t=0 0",
            "m=audio 5061 RTP/AVP 8",
            "a=recvonly",
            "y=0123456789",
            "") + "\r\n";

    @Test
    void start_sendsTalkInvite_withAudioSdp() {
        stubStartHappyPath();
        when(sessionMapper.selectById(99L)).thenAnswer(inv -> inserted);

        svc.start(77L, 1L);

        verify(invite).sendTalkInvite(any(), anyString(), any(), anyString(), anyInt());
        
        // SDP 内容断言走 SdpTemplatesTalkTest; 此处断言会话行与返回体
        assertThat(inserted.getSessionType()).isEqualTo(GbMediaSessionEntity.TYPE_TALK);
        
        assertThat(inserted.getCallId()).isEqualTo("CALL-TALK");
    }

    @Test
    void begin_afterAnswer_goesStreaming() {
        stubStartHappyPath();
        when(sessionMapper.selectById(99L)).thenAnswer(inv -> inserted);
        svc.start(77L, 1L);

        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE).method("INVITE").statusCode(200)
                .callId("CALL-TALK").toTag("tagB").cseq(1L)
                .body(ANSWER_SDP).build());

        when(sessionMapper.update(any(), any())).thenReturn(1);
        when(sessionMapper.selectById(99L)).thenReturn(session("INVITING"), session("STREAMING"));

        TalkSessionService.StartInfo info = svc.begin(99L, 1L);

        assertThat(info.status()).isEqualTo("STREAMING");
        verify(publisher).talkEvent(anyString(), anyString(), eq("TALK_STARTED"), anyString());
    }

    @Test
    void answer403_closesSessionFailed() {
        stubStartHappyPath();
        when(sessionMapper.selectById(99L)).thenAnswer(inv -> inserted);
        svc.start(77L, 1L);
        when(cleanup.closeLocal(eq(99L), eq("FAILED"), eq("invite_rejected"), anyString())).thenReturn(true);

        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE).method("INVITE").statusCode(403)
                .callId("CALL-TALK").body(null).build());

        verify(cleanup, timeout(1000)).closeLocal(eq(99L), eq("FAILED"), eq("invite_rejected"), anyString());
        verify(publisher, timeout(1000)).talkEvent(anyString(), anyString(), eq("TALK_STOPPED"), anyString());
    }

    @Test
    void answerWithoutPcma_closesAsUnavailable() {
        stubStartHappyPath();
        when(sessionMapper.selectById(99L)).thenAnswer(inv -> inserted);
        svc.start(77L, 1L);
        String noPcma = ANSWER_SDP.replace("RTP/AVP 8", "RTP/AVP 0");
        when(cleanup.closeLocal(eq(99L), eq("FAILED"), eq("invalid_answer"), anyString())).thenReturn(true);

        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE).method("INVITE").statusCode(200)
                .callId("CALL-TALK").toTag("tagB").cseq(1L).body(noPcma).build());

        verify(cleanup, timeout(1000)).closeLocal(eq(99L), eq("FAILED"), eq("invalid_answer"), anyString());
    }

    @Test
    void stop_closesSessionAndStopsSend() {
        when(sessionMapper.selectById(99L)).thenReturn(session("STREAMING"));
        when(cleanup.closeLocal(eq(99L), eq("CLOSED"), eq("user_stop"), anyString())).thenReturn(true);

        svc.stop(99L, "user_stop");

        verify(cleanup).closeLocal(eq(99L), eq("CLOSED"), eq("user_stop"), anyString());
        verify(publisher).talkEvent(anyString(), anyString(), eq("TALK_STOPPED"), eq("user_stop"));
    }

    @Test
    void deviceBye_closesSession() {
        // 模拟 start 登记 callIndex 后设备主动 BYE
        stubStartHappyPath();
        when(sessionMapper.selectById(99L)).thenAnswer(inv -> inserted);
        svc.start(77L, 1L);
        when(sessionMapper.selectById(99L)).thenReturn(session("INVITING"));
        when(cleanup.closeLocal(eq(99L), eq("CLOSED"), eq("device_bye"), anyString())).thenReturn(true);

        svc.onDeviceBye("CALL-TALK");

        verify(cleanup).closeLocal(eq(99L), eq("CLOSED"), eq("device_bye"), anyString());
    }

    @Test
    void answerSdp_missingPcma_rejected() {
        assertThatThrownBy(() -> TalkSessionService.TalkAnswerSdp.parse(
                "v=0\r\nc=IN IP4 1.2.3.4\r\nm=audio 5000 RTP/AVP 0\r\ny=0123456789\r\n"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PCMA");
    }
}
