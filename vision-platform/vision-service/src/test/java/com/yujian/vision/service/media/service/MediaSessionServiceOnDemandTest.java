package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.monitor.VisionMetrics;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipTransportProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * M3-B7 按需点播服务端单测: playOnDemand 的复用/新建/跨节点补代理三条路径。
 *
 * <p>复用语义是核心: streamId（gb_play_{channelId}）与码流档无关, 只要该通道有任意档的活跃
 * 实况会话就直接复用（不新建 INVITE）——否则第二个观众会拿到一条新流, 与 on_play 的
 * hasActiveStream 裁决冲突。</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceOnDemandTest {

    private static final long CH_DB_ID = 7L;
    private static final String CH_ID = "0102000001";
    private static final String STREAM = MediaSessionService.LIVE_STREAM_PREFIX + CH_ID;

    @Mock
    private GbMediaSessionMapper sessionMapper;
    @Mock
    private GbMediaServerMapper serverMapper;
    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private GbDeviceChannelMapper channelMapper;
    @Mock
    private SsrcPoolService ssrcPool;
    @Mock
    private ZlmApiClient zlm;
    @Mock
    private InviteService invite;
    @Mock
    private PlayUrlService urlService;
    @Mock
    private StreamEventPublisher publisher;
    @Mock
    private SessionCleanupService cleanup;
    @Mock
    private SipTransportProvider transport;
    @Mock
    private GbPlatformProperties platform;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;
    @Mock
    private StreamProxyService streamProxies;

    private MediaSessionService svc;
    private final MediaProperties media = new MediaProperties();

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
    }

    @BeforeEach
    void setUp() {
        svc = new MediaSessionService(sessionMapper, serverMapper, deviceMapper, channelMapper,
                ssrcPool, zlm, invite, urlService, publisher, cleanup, transport, platform, media,
                redisson, new VisionMetrics(new SimpleMeterRegistry()),
                new MediaNodeScheduler(serverMapper,
                        MediaSessionServiceInviteRetryTest.noopMetrics(), media),
                MediaSessionServiceInviteRetryTest.haGate(), streamProxies, null);

        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setId(CH_DB_ID);
        ch.setDeviceDbId(3L);
        ch.setChannelId(CH_ID);
        ch.setStatus(1);
        GbDeviceEntity dev = new GbDeviceEntity();
        dev.setId(3L);
        dev.setIp("10.0.0.2");
        dev.setPort(5060);
        dev.setOnline(1);

        // playOnDemand 按 channel_id 查一次, doPlay 主干再按主键查一次
        org.mockito.Mockito.lenient().when(channelMapper.selectOne(any())).thenReturn(ch);
        org.mockito.Mockito.lenient().when(channelMapper.selectById(CH_DB_ID)).thenReturn(ch);
        org.mockito.Mockito.lenient().when(deviceMapper.selectById(3L)).thenReturn(dev);
        org.mockito.Mockito.lenient().when(redisson.getLock(anyString())).thenReturn(lock);
        org.mockito.Mockito.lenient().when(serverMapper.selectList(any())).thenReturn(
                new java.util.ArrayList<>(List.of(node("n1"), node("n2"))));
        org.mockito.Mockito.lenient().when(ssrcPool.allocLive(anyString(), anyString())).thenReturn("0000000001");
        org.mockito.Mockito.lenient().when(invite.sendInvite(any(), any(), any(), anyString(), anyInt()))
                .thenReturn(new InviteService.InviteHandle("call-od-1", "tag"));
        org.mockito.Mockito.lenient().when(sessionMapper.insert(any(GbMediaSessionEntity.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(0, GbMediaSessionEntity.class).setId(99L);
                    return 1;
                });
        org.mockito.Mockito.lenient().when(zlm.openRtpServer(anyString(), any())).thenReturn(30088);
        org.mockito.Mockito.lenient().when(urlService.buildUrls(anyString(), anyString(), anyInt()))
                .thenReturn(java.util.Map.of("flv", "http://u"));
    }

    private static GbMediaServerEntity node(String id) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(id);
        n.setStatus(1);
        return n;
    }

    /** 活跃实况会话桩（状态 STREAMING, 承载在 serverId 节点） */
    private static GbMediaSessionEntity liveSession(String serverId, int viewerCount) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(55L);
        s.setStreamId(STREAM);
        s.setChannelDbId(CH_DB_ID);
        s.setSessionType(GbMediaSessionEntity.TYPE_LIVE);
        s.setStatus("STREAMING");
        s.setServerId(serverId);
        s.setViewerCount(viewerCount);
        return s;
    }

    @Test
    @DisplayName("非法 streamId(非 gb_play_ 前缀/空后缀): BAD_REQUEST")
    void rejectsNonLiveStreamId() {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> svc.playOnDemand("gb_playback_0102000001_101010_01", "n1", 1)).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> svc.playOnDemand(MediaSessionService.LIVE_STREAM_PREFIX, "n1", 1)).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> svc.playOnDemand(null, "n1", 1)).getErrorCode());
    }

    @Test
    @DisplayName("channelId 查无通道(已删除/不存在): SESSION_NOT_FOUND, 不建流")
    void rejectsUnknownChannel() {
        when(channelMapper.selectOne(any())).thenReturn(null);

        assertEquals(ErrorCode.SESSION_NOT_FOUND, assertThrows(BizException.class,
                () -> svc.playOnDemand(STREAM, "n1", 1)).getErrorCode());
        verify(zlm, never()).openRtpServer(anyString(), any());
    }

    @Test
    @DisplayName("已有活跃实况会话(同节点): 直接复用, 不新建 INVITE、不补代理")
    void reusesActiveSessionOnSameNode() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(liveSession("n1", 1)));

        MediaSessionService.PlayResult r = svc.playOnDemand(STREAM, "n1", 1);

        assertEquals(55L, r.sessionId());
        assertEquals(STREAM, r.streamId());
        assertEquals("STREAMING", r.status());
        assertEquals("n1", r.serverId());
        verify(zlm, never()).openRtpServer(anyString(), any());
        verify(sessionMapper, never()).insert(any(GbMediaSessionEntity.class));
        verify(streamProxies, never()).ensureProxy(anyString(), anyString());
    }

    @Test
    @DisplayName("已有活跃会话但在别的节点: 复用 + 给请求节点补跨节点回源代理(播放器在该节点等流)")
    void reusesAndProxiesWhenSessionOnOtherNode() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(liveSession("n2", 3)));

        MediaSessionService.PlayResult r = svc.playOnDemand(STREAM, "n1", 1);

        assertEquals("n2", r.serverId());
        verify(streamProxies).ensureProxy(STREAM, "n1");
        verify(zlm, never()).openRtpServer(anyString(), any());
    }

    @Test
    @DisplayName("无活跃会话: 在 hook 请求节点上新建流(on-demand-profile 决定档位)")
    void createsNewSessionPinnedToRequestNode() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        MediaSessionService.PlayResult r = svc.playOnDemand(STREAM, "n2", 2);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("n2", cap.getValue().getServerId());
        assertEquals(2, cap.getValue().getStreamProfile());
        assertEquals(STREAM, cap.getValue().getStreamId());
        assertEquals("n2", r.serverId());
        verify(zlm).openRtpServer(eq("n2"), any());
    }

    @Test
    @DisplayName("请求节点不在线: 回落策略节点建流(不因单节点状态异常整体失效)")
    void fallsBackWhenPreferredNodeOffline() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        MediaSessionService.PlayResult r = svc.playOnDemand(STREAM, "ghost-node", 1);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("n1", cap.getValue().getServerId());
        assertEquals("n1", r.serverId());
    }
}
