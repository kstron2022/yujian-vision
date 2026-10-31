package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmAddStreamProxyRequest;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.monitor.VisionMetrics;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipTransportProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
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
 * M4-B2 ONVIF 点播单测: doPlay 分流 → onvifInviteNew（GetStreamUri→addStreamProxy）三路径
 * （正常建流/设备取流失败不落会话/回放拒绝）。streamId/app 与 GB 同规范是复用播放链路的前提。
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceOnvifPlayTest {

    private static final long CH_DB_ID = 9L;
    private static final String CH_ID = "34020000001320000007";
    private static final String STREAM = MediaSessionService.LIVE_STREAM_PREFIX + CH_ID;
    private static final String PULL_URL = "rtsp://u:p@10.0.0.9:50554/Streaming/Channels/101";

    @Mock private GbMediaSessionMapper sessionMapper;
    @Mock private GbMediaServerMapper serverMapper;
    @Mock private GbDeviceMapper deviceMapper;
    @Mock private GbDeviceChannelMapper channelMapper;
    @Mock private SsrcPoolService ssrcPool;
    @Mock private ZlmApiClient zlm;
    @Mock private InviteService invite;
    @Mock private PlayUrlService urlService;
    @Mock private StreamEventPublisher publisher;
    @Mock private SessionCleanupService cleanup;
    @Mock private SipTransportProvider transport;
    @Mock private GbPlatformProperties platform;
    @Mock private RedissonClient redisson;
    @Mock private RLock lock;
    @Mock private org.redisson.api.RBucket<String> seekBucket;
    @Mock private StreamProxyService streamProxies;
    @Mock private DeviceAccessRegistry accessRegistry;
    @Mock private com.yujian.vision.service.device.spi.DeviceAccessProvider provider;

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
                MediaSessionServiceInviteRetryTest.haGate(), streamProxies, accessRegistry);
    }

    /** 构造在线 ONVIF 通道+设备, registry 返回固定拉流地址 */
    private void stubOnvifPath() {
        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setId(CH_DB_ID);
        ch.setDeviceDbId(3L);
        ch.setChannelId(CH_ID);
        ch.setStatus(1);
        GbDeviceEntity dev = new GbDeviceEntity();
        dev.setId(3L);
        dev.setIp("10.0.0.9");
        dev.setPort(50080);
        dev.setOnline(1);
        dev.setAccessProtocol("ONVIF");
        lenient().when(channelMapper.selectById(CH_DB_ID)).thenReturn(ch);
        lenient().when(deviceMapper.selectById(3L)).thenReturn(dev);
        lenient().when(redisson.getLock(anyString())).thenReturn(lock);
        lenient().when(serverMapper.selectList(any())).thenReturn(
                new java.util.ArrayList<>(java.util.List.of(node("n1"), node("n2"))));
        lenient().when(accessRegistry.of(DeviceProtocol.ONVIF)).thenReturn(provider);
        lenient().when(provider.resolveStreamUrl(any(), any(), anyInt())).thenReturn(PULL_URL);
        lenient().when(provider.resolveReplayUrl(any(), any(),
                org.mockito.ArgumentMatchers.<java.time.LocalDateTime>any(),
                org.mockito.ArgumentMatchers.<java.time.LocalDateTime>any()))
                .thenReturn(PULL_URL);
        // mock insert 回填自增 ID(模拟真实 MyBatis-Plus 行为)
        lenient().doAnswer(inv -> {
            ((GbMediaSessionEntity) inv.getArgument(0)).setId(101L);
            return 1;
        }).when(sessionMapper).insert(any(GbMediaSessionEntity.class));
    }

    private static GbMediaServerEntity node(String id) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(id);
        n.setStatus(1);
        return n;
    }
    @Test
    @DisplayName("ONVIF 点播: 取流→addStreamProxy(rtp/gb_play_x)→会话 INVITING, 无 SIP 语义")
    void onvifPlayBuildsProxySession() {
        stubOnvifPath();
        lenient().when(zlm.addStreamProxy(anyString(), any())).thenReturn("k1");
        lenient().when(urlService.buildUrls(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.Map.of("flv", "http://x"));
        MediaSessionService.PlayResult r = svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play"));
        assertEquals("INVITING", r.status());
        verify(zlm).addStreamProxy(org.mockito.ArgumentMatchers.eq("n1"),
                argThat((ZlmAddStreamProxyRequest q) ->
                        "rtp".equals(q.app()) && (MediaSessionService.LIVE_STREAM_PREFIX + CH_ID).equals(q.stream())
                                && PULL_URL.equals(q.url())));
        verify(streamProxies).registerSource(STREAM, "n1", "k1");
        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("", cap.getValue().getSsrc());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().getCallId());
    }

    @Test
    @DisplayName("设备取流失败 → 不落会话行, 抛 DEVICE_OFFLINE")
    void resolveFailureLeavesNoSession() {
        stubOnvifPath();
        lenient().when(provider.resolveStreamUrl(any(), any(), anyInt()))
                .thenThrow(new BizException(ErrorCode.DEVICE_OFFLINE, "ONVIF 取流地址获取失败"));
        BizException ex = assertThrows(BizException.class,
                () -> svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play")));
        assertEquals(ErrorCode.DEVICE_OFFLINE, ex.getErrorCode());
        verify(sessionMapper, never()).insert(argThat((GbMediaSessionEntity e) -> e != null));
    }

    @Test
    @DisplayName("ONVIF 回放: resolveReplayUrl→addStreamProxy(gb_playback_*)→controlMode=seek, 无 SIP 语义")
    void onvifPlaybackBuildsProxySession() {
        stubOnvifPath();
        lenient().when(zlm.addStreamProxy(anyString(), any())).thenReturn("k1");
        lenient().when(urlService.buildUrls(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.Map.of("flv", "http://x"));
        MediaSessionService.PlayResult r = svc.playPlayback(CH_DB_ID, 1, 1L, Set.of("record:play"),
                java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now());
        assertEquals("seek", r.controlMode());
        org.junit.jupiter.api.Assertions.assertTrue(
                r.streamId().startsWith("gb_playback_" + CH_ID + "_"), "回放 streamId 前缀: " + r.streamId());
        verify(zlm).addStreamProxy(org.mockito.ArgumentMatchers.eq("n1"),
                argThat((ZlmAddStreamProxyRequest q) -> "rtp".equals(q.app())
                        && q.stream().startsWith("gb_playback_" + CH_ID + "_")
                        && PULL_URL.equals(q.url())));
        // 回放无 SIP 语义: ssrc 空串且不分配收流端口（与 GB 回放 INVITE 的关键差异）
        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("", cap.getValue().getSsrc());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().getRtpPort());
        org.junit.jupiter.api.Assertions.assertNotNull(cap.getValue().getPlaybackStart());
    }

    @Test
    @DisplayName("回放取流地址解析失败 → 不落会话行, 原样上抛")
    void playbackResolveFailureLeavesNoSession() {
        stubOnvifPath();
        lenient().when(provider.resolveReplayUrl(any(), any(),
                org.mockito.ArgumentMatchers.<java.time.LocalDateTime>any(),
                org.mockito.ArgumentMatchers.<java.time.LocalDateTime>any()))
                .thenThrow(new BizException(ErrorCode.DEVICE_OFFLINE, "ONVIF 取回放流地址失败"));
        BizException ex = assertThrows(BizException.class,
                () -> svc.playPlayback(CH_DB_ID, 1, 1L, Set.of("record:play"),
                        java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now()));
        assertEquals(ErrorCode.DEVICE_OFFLINE, ex.getErrorCode());
        verify(sessionMapper, never()).insert(argThat((GbMediaSessionEntity e) -> e != null));
    }

    @Test
    @DisplayName("ONVIF 回放 PAUSE → 明确拒绝(拉流代理无暂停能力, §5.3 定级)")
    void onvifPlaybackPauseRejected() {
        streamingPlaybackSession(); // 只取打桩副作用, 断言面不引用会话实体
        BizException ex = assertThrows(BizException.class,
                () -> svc.playbackControl(STREAM, "PAUSE", null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(zlm, never()).addStreamProxy(anyString(), any());
    }

    @Test
    @DisplayName("ONVIF 回放 SEEK 窗口外 → BAD_REQUEST, 不动代理")
    void onvifPlaybackSeekOutsideWindow() {
        GbMediaSessionEntity s = streamingPlaybackSession();
        java.time.LocalDateTime out = s.getPlaybackEnd().plusMinutes(1);
        BizException ex = assertThrows(BizException.class,
                () -> svc.playbackControl(STREAM, "SEEK", null, out));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(zlm, never()).addStreamProxy(anyString(), any());
    }

    @Test
    @DisplayName("ONVIF 回放 SEEK: 取通道锁→终态复核→挂豁免标记→del→add(新窗口地址)")
    void onvifPlaybackSeekRebuildsProxy() {
        GbMediaSessionEntity s = streamingPlaybackSession();
        java.time.LocalDateTime to = s.getPlaybackStart().plusMinutes(5);
        lenient().doReturn(seekBucket).when(redisson).getBucket(org.mockito.ArgumentMatchers.anyString());
        lenient().when(sessionMapper.selectById(501L)).thenReturn(s);
        lenient().when(serverMapper.selectOne(any())).thenReturn(node("n1"));
        lenient().when(zlm.addStreamProxy(org.mockito.ArgumentMatchers.eq("n1"), any())).thenReturn("k2");
        svc.playbackControl(STREAM, "SEEK", null, to);
        // 与 doPlay/stop 同一把通道锁（评审 B3-P2-3: 并发 SEEK/stop 竞速防护）
        verify(lock).lock();
        verify(lock).unlock();
        verify(streamProxies).cleanupForStream(STREAM);
        verify(streamProxies).registerSource(org.mockito.ArgumentMatchers.eq(STREAM),
                org.mockito.ArgumentMatchers.eq("n1"), org.mockito.ArgumentMatchers.eq("k2"));
        verify(zlm).addStreamProxy(org.mockito.ArgumentMatchers.eq("n1"),
                argThat((ZlmAddStreamProxyRequest q) -> q.stream().equals(STREAM)));
    }

    @Test
    @DisplayName("ONVIF 回放 SEEK 时会话已终态 → 不重建代理(防孤儿代理泄漏, 评审 B3-P2-3)")
    void onvifPlaybackSeekSkipsTerminalSession() {
        GbMediaSessionEntity s = streamingPlaybackSession();
        java.time.LocalDateTime to = s.getPlaybackStart().plusMinutes(5);
        // SOAP 期间会话被 stop 关停: selectList 命中 STREAMING 过前置检查, 锁内 selectById 已终态
        GbMediaSessionEntity closed = new GbMediaSessionEntity();
        closed.setId(501L);
        closed.setStatus("CLOSED");
        lenient().doReturn(seekBucket).when(redisson).getBucket(org.mockito.ArgumentMatchers.anyString());
        lenient().when(sessionMapper.selectById(501L)).thenReturn(closed);
        svc.playbackControl(STREAM, "SEEK", null, to);
        verify(zlm, never()).addStreamProxy(anyString(), any());
        verify(streamProxies, never()).cleanupForStream(org.mockito.ArgumentMatchers.anyString());
    }

    // ------------------------------------------------------------- 回放控制用例共享桩

    /** STREAMING 回放会话（deviceDbId=3 对应 stubOnvifPath 的 ONVIF 设备; selectList 查活跃会话命中） */
    private GbMediaSessionEntity streamingPlaybackSession() {
        stubOnvifPath();
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(501L);
        s.setSessionType(GbMediaSessionEntity.TYPE_PLAYBACK);
        s.setStreamId(STREAM);
        s.setStatus("STREAMING");
        s.setDeviceDbId(3L);
        s.setChannelDbId(CH_DB_ID);
        s.setServerId("n1");
        s.setPlaybackStart(java.time.LocalDateTime.now().minusHours(1));
        s.setPlaybackEnd(java.time.LocalDateTime.now());
        lenient().when(sessionMapper.selectList(any()))
                .thenReturn(java.util.List.of(s));
        return s;
    }

    @Test
    @DisplayName("addStreamProxy 失败 → 会话补偿置 FAILED, ZLM_API_FAILED 换节点重试")
    void proxyFailureCompensates() {
        stubOnvifPath();
        // 回归守护(242 实测 Duplicate entry '909'): 换节点重试的二次 insert 前必须清掉已回填自增 id
        java.util.List<Long> idsAtInsert = new java.util.ArrayList<>();
        lenient().doAnswer(inv -> {
            GbMediaSessionEntity e = inv.getArgument(0);
            idsAtInsert.add(e.getId());
            e.setId(101L);
            return 1;
        }).when(sessionMapper).insert(any(GbMediaSessionEntity.class));
        lenient().when(zlm.addStreamProxy(anyString(), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "zlm down"));
        lenient().when(sessionMapper.selectById(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play")));
        // 重试耗尽后抛最后节点原始异常(与 GB inviteNew throw e 一致)
        assertEquals(ErrorCode.ZLM_API_FAILED, ex.getErrorCode());
        // 补偿: 两次尝试(n1+n2, retries=1)各置一次 FAILED 补偿
        verify(cleanup, org.mockito.Mockito.times(2)).close(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("FAILED"),
                anyString(), anyString());
        verify(sessionMapper, org.mockito.Mockito.times(2)).insert(argThat((GbMediaSessionEntity e) -> e != null));
        // 第二次 insert 时 id 已被清空(修复前残留首次回填的 101L → DB 主键冲突 Duplicate entry;
        // insert 调用时点在回填前, 故首条记录天然为 null)
        org.junit.jupiter.api.Assertions.assertNull(idsAtInsert.get(1));
    }

    @Test
    @DisplayName("受理后同步等流注册: hook 置 STREAMING → 响应 STREAMING(与 GB「响应即流就绪」对齐)")
    void onvifPlayWaitsForStreaming() {
        stubOnvifPath();
        svc.onvifAwaitIntervalMs = 1;
        lenient().when(zlm.addStreamProxy(anyString(), any())).thenReturn("k1");
        lenient().when(urlService.buildUrls(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.Map.of("flv", "http://x"));
        // 调用序: 锁内 findActive(空, 不复用) → awaitStreaming 第1轮(INVITING) → 第2轮(STREAMING)
        GbMediaSessionEntity inviting = new GbMediaSessionEntity();
        inviting.setStreamId(STREAM);
        inviting.setStatus("INVITING");
        GbMediaSessionEntity streaming = new GbMediaSessionEntity();
        streaming.setStreamId(STREAM);
        streaming.setStatus("STREAMING");
        // 单参链式与 varargs 语义一致(按序返回, 末值驻留), 规避泛型数组创建告警
        when(sessionMapper.selectList(any())).thenReturn(java.util.List.of())
                .thenReturn(java.util.List.of(inviting))
                .thenReturn(java.util.List.of(streaming));
        MediaSessionService.PlayResult r = svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play"));
        assertEquals("STREAMING", r.status());
    }

    @Test
    @DisplayName("等待窗口超时未就绪 → 响应退化 INVITING(WS 事件驱动兜底, 向后兼容)")
    void onvifPlayTimeoutFallsBackToInviting() {
        stubOnvifPath();
        svc.onvifAwaitTimeoutMs = 0;
        lenient().when(zlm.addStreamProxy(anyString(), any())).thenReturn("k1");
        lenient().when(urlService.buildUrls(anyString(), anyString(), anyLong()))
                .thenReturn(java.util.Map.of("flv", "http://x"));
        MediaSessionService.PlayResult r = svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play"));
        assertEquals("INVITING", r.status());
    }
}
