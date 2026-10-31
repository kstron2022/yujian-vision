package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
import java.util.Map;
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
 * M5-B1 RTSP 拉流代理点播单测: doPlay 分流 → rtspInviteNew 三路径
 * （按需建流/常驻复用直接 STREAMING/回放拒绝）。拉流地址经接入面 SPI 解密, 无设备外呼。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MediaSessionServiceRtspPlayTest {

    private static final long CH_DB_ID = 11L;
    private static final String CH_ID = "34020000001320000011";
    private static final String STREAM = MediaSessionService.LIVE_STREAM_PREFIX + CH_ID;
    private static final String PULL_URL = "rtsp://u:p@10.0.0.11:554/live";

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
        svc.onvifAwaitTimeoutMs = 150;
        svc.onvifAwaitIntervalMs = 10;
    }

    /** 构造在线 RTSP 按需设备+通道, 接入面返回固定拉流地址 */
    private void stubRtspPath(boolean alwaysOn) {
        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setId(CH_DB_ID);
        ch.setDeviceDbId(5L);
        ch.setChannelId(CH_ID);
        ch.setStatus(1);
        GbDeviceEntity dev = new GbDeviceEntity();
        dev.setId(5L);
        dev.setOnline(1);
        dev.setAccessProtocol("RTSP");
        dev.setRtspTransport("udp");
        dev.setAlwaysOn(alwaysOn ? 1 : 0);
        lenient().when(channelMapper.selectById(CH_DB_ID)).thenReturn(ch);
        lenient().when(deviceMapper.selectById(5L)).thenReturn(dev);
        lenient().when(redisson.getLock(anyString())).thenReturn(lock);
        lenient().when(serverMapper.selectList(any())).thenReturn(
                new java.util.ArrayList<>(java.util.List.of(node("n1"))));
        lenient().when(accessRegistry.of(DeviceProtocol.RTSP)).thenReturn(provider);
        lenient().when(provider.resolveStreamUrl(any(), any(), anyInt())).thenReturn(PULL_URL);
        // insert 回填自增 id(ConcurrentHashMap.getOrDefault(null) 会 NPE)
        lenient().when(sessionMapper.insert(any(GbMediaSessionEntity.class))).thenAnswer(inv -> {
            ((GbMediaSessionEntity) inv.getArgument(0)).setId(77L);
            return 1;
        });
    }

    private GbMediaServerEntity node(String serverId) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(serverId);
        n.setHostIp("10.0.0.1");
        n.setHttpPort(8080);
        n.setStatus(1);
        return n;
    }

    @Test
    @DisplayName("#1 按需建流: SPI 解密取流 → forRtspPull(udp→rtp_type=1) → 建会话等待流注册")
    void rtspOnDemandPlay() {
        stubRtspPath(false);
        when(streamProxies.alwaysOnEntries(STREAM)).thenReturn(Map.of());
        lenient().when(zlm.addStreamProxy(anyString(), any(ZlmAddStreamProxyRequest.class))).thenReturn("pkey");
        when(urlService.buildUrls(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(Map.of());

        svc.play(CH_DB_ID, 1, 1, null, java.util.Set.of());

        ArgumentCaptor<ZlmAddStreamProxyRequest> reqCap =
                ArgumentCaptor.forClass(ZlmAddStreamProxyRequest.class);
        verify(zlm).addStreamProxy(anyString(), reqCap.capture());
        assertEquals(1, reqCap.getValue().rtpType(), "udp 档案应映射 rtp_type=1");
        assertEquals(PULL_URL, reqCap.getValue().url());
        verify(streamProxies).registerSource(anyString(), anyString(), anyString());
        ArgumentCaptor<GbMediaSessionEntity> sCap =
                ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(sCap.capture());
        assertEquals("INVITING", sCap.getValue().getStatus());
        assertEquals("", sCap.getValue().getSsrc(), "无 SIP 语义, ssrc 恒空");
    }

    @Test
    @DisplayName("#2 常驻复用: 常驻索引命中且流活跃 → 会话直接 STREAMING, 不再 addStreamProxy")
    void rtspAlwaysOnReuse() {
        stubRtspPath(true);
        when(streamProxies.alwaysOnEntries(STREAM)).thenReturn(Map.of("n1", "akey"));
        when(streamProxies.streamActiveOn("n1", STREAM)).thenReturn(true);
        when(urlService.buildUrls(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(Map.of());

        var result = svc.play(CH_DB_ID, 1, 1, null, java.util.Set.of());

        assertEquals("STREAMING", result.status());
        verify(zlm, org.mockito.Mockito.never()).addStreamProxy(anyString(),
                any(ZlmAddStreamProxyRequest.class));
        ArgumentCaptor<GbMediaSessionEntity> sCap =
                ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(sCap.capture());
        assertEquals("STREAMING", sCap.getValue().getStatus());
        assertEquals("n1", sCap.getValue().getServerId());
        assertNotNull(sCap.getValue().getStartTime(), "复用会话 startTime 立即落库");
    }

    @Test
    @DisplayName("#3 回放拒绝: RTSP 源无国标录像语义, 给明确业务错误")
    void rtspPlaybackRejected() {
        stubRtspPath(false);
        BizException e = assertThrows(BizException.class,
                () -> svc.playPlayback(CH_DB_ID, null, null, java.util.Set.of(),
                        java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now()));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }
}
