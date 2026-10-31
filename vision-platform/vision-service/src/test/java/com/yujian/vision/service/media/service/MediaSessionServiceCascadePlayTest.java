package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmOpenRtpServerRequest;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * M3-B3 级联媒体分流测试（docs/08 §3.2/§3.3）:
 * cascade:play 校验（下级平台通道点播/回放独立授权, 缺失即 403 且不碰收流/信令）
 * + tcpMode 档案覆盖（有权限的 HTTP 点播/回放恒取设备档案 mediaTcpMode, 不随请求切换）
 * + 内部调用豁免（perms=null 的 B2 上级转推跳过校验且不覆盖 tcpMode）
 * + 本域设备(type=1)行为不变。
 * 纯 Mockito, 不起 Spring 上下文/Redis/数据库; mock 基建风格对齐
 * MediaSessionServiceControlTest。
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceCascadePlayTest {

    /** 通道/设备 dbId（mock 主键） */
    private static final long CH_DB_ID = 7L;
    private static final long DEV_DB_ID = 3L;

    /** 回放窗口（validateWindow 要求 start<end 且跨度 ≤31 天） */
    private static final LocalDateTime WIN_START = LocalDateTime.of(2026, 9, 12, 8, 0, 0);
    private static final LocalDateTime WIN_END = LocalDateTime.of(2026, 9, 12, 9, 0, 0);

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

    private MediaSessionService svc;
    private final MediaProperties media = new MediaProperties();

    private GbDeviceChannelEntity ch;
    private GbDeviceEntity dev;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
        // #11/#12 目录守卫 isDirectoryChannel 构造 GbDeviceChannelEntity 的 wrapper 需要列缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceChannelEntity.class);
    }

    @BeforeEach
    void setUp() {
        svc = new MediaSessionService(sessionMapper, serverMapper, deviceMapper, channelMapper,
                ssrcPool, zlm, invite, urlService, publisher, cleanup, transport, platform, media, redisson,
                new com.yujian.vision.service.monitor.VisionMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new MediaNodeScheduler(serverMapper, MediaSessionServiceInviteRetryTest.noopMetrics(), media),
                MediaSessionServiceInviteRetryTest.haGate(),
                org.mockito.Mockito.mock(StreamProxyService.class), null);
        // 通道: 在线未删除（deletedTime=null 默认, status=1 过 doPlay 通道离线检查）
        ch = new GbDeviceChannelEntity();
        ch.setId(CH_DB_ID);
        ch.setDeviceDbId(DEV_DB_ID);
        ch.setChannelId("34020000001310000001");
        ch.setStatus(1);
        // 设备: 在线（deviceType/mediaTcpMode 由各用例设定）
        dev = new GbDeviceEntity();
        dev.setId(DEV_DB_ID);
        dev.setDeviceId("34020000002000000001");
        dev.setIp("10.0.0.2");
        dev.setPort(5060);
        dev.setOnline(1);
        // lenient: #11/#12 目录守卫在设备查询之前抛出, 这两条桩不被消费属预期
        lenient().when(channelMapper.selectById(CH_DB_ID)).thenReturn(ch);
        lenient().when(deviceMapper.selectById(DEV_DB_ID)).thenReturn(dev);
    }

    /** 通道/设备置为下级平台档案（docs/08 §3.2: deviceType=2, 档案收流模式 mediaTcpMode） */
    private void cascadeDevice(int mediaTcpMode) {
        dev.setDeviceType(2);
        dev.setMediaTcpMode(mediaTcpMode);
    }

    /**
     * 打通「无活跃会话 → 新建会话」stub 链路: 锁 → 活跃查询 → 节点 → SSRC → 开端口 → INVITE → URL。
     * playback=false 走实况 SSRC(首位 0)/true 走回放 SSRC(首位 1), 分开 stub 避免未用桩
     */
    private void stubNewSessionChain(boolean playback) {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(sessionMapper.selectList(any())).thenReturn(List.of()); // 无活跃会话 → 走新建
        // insert 模拟 DB 自增主键回填: inviteNew 落库后用 s.getId() 登记 callIndex(CHM 禁 null value)
        doAnswer(inv -> {
            GbMediaSessionEntity s = inv.getArgument(0);
            s.setId(99L);
            return 1;
        }).when(sessionMapper).insert(any(GbMediaSessionEntity.class));
        when(serverMapper.selectList(org.mockito.ArgumentMatchers.<LambdaQueryWrapper<GbMediaServerEntity>>any()))
                .thenReturn(java.util.List.of(node()));
        if (playback) {
            when(ssrcPool.allocPlayback(anyString(), anyString())).thenReturn("1123456789");
        } else {
            when(ssrcPool.allocLive(anyString(), anyString())).thenReturn("0123456789");
        }
        when(zlm.openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class))).thenReturn(40000);
        when(invite.sendInvite(any(GbMediaSessionEntity.class), any(GbMediaServerEntity.class),
                any(GbDeviceEntity.class), anyString(), anyInt()))
                .thenReturn(new InviteService.InviteHandle("call-1", "tag-1"));
        when(urlService.buildUrls(anyString(), anyString(), anyLong())).thenReturn(Map.of("flv", "x"));
    }

    /**
     * 在线流媒体节点（status=1 过 inviteNew「无在线节点」检查; hostIp/publicIp/httpPort 供 URL/SDP 组装）
     */
    private static GbMediaServerEntity node() {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId("vision-zlm");
        n.setHostIp("192.168.1.10");
        n.setPublicIp("10.0.0.242");
        n.setHttpPort(8080);
        n.setStatus(1);
        return n;
    }

    @Test
    @DisplayName("#1 级联通道无 cascade:play → 403 且不开收流不发 INVITE")
    void cascadeChannelWithoutPermForbidden() {
        cascadeDevice(1);

        BizException e = assertThrows(BizException.class,
                () -> svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play")));

        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(zlm, never()).openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class));
        verify(invite, never()).sendInvite(any(), any(), any(), anyString(), anyInt());
        // 校验在取锁之前: 未取通道锁、未查活跃会话
        verify(redisson, never()).getLock(anyString());
        verify(sessionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("#2 有 cascade:play → 放行且收流模式恒取设备档案(mediaTcpMode=0 覆盖请求 1)")
    void cascadeChannelWithPermUsesProfileTcpMode() {
        cascadeDevice(0); // 档案: UDP
        stubNewSessionChain(false);

        MediaSessionService.PlayResult r = svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play", "cascade:play"));

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        // 会话落档案值 0(UDP), 而非请求值 1(TCP)
        assertEquals(0, cap.getValue().getTcpMode());
        assertEquals("0123456789", cap.getValue().getSsrc());
        assertEquals("INVITING", r.status());
        assertEquals(Map.of("flv", "x"), r.urls());
        // 收流端口按 UDP 开（enableTcp=false）
        ArgumentCaptor<ZlmOpenRtpServerRequest> openCap = ArgumentCaptor.forClass(ZlmOpenRtpServerRequest.class);
        verify(zlm).openRtpServer(eq("vision-zlm"), openCap.capture());
        assertFalse(openCap.getValue().enableTcp());
    }

    @Test
    @DisplayName("#3 perms=null(内部调用/上级转推) → 跳过校验且不覆盖 tcpMode(保持请求 0)")
    void internalCallSkipsCheckAndKeepsRequestedTcpMode() {
        cascadeDevice(1); // 档案: TCP —— 若被覆盖会话应变 1, 以此证明"未覆盖"
        stubNewSessionChain(false);

        svc.play(CH_DB_ID, 0, 1, null, null); // 内部调用: userId/perms 均可空

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        // 会话保持请求值 0(UDP), 未被档案 1(TCP) 覆盖
        assertEquals(0, cap.getValue().getTcpMode());
        // 收流端口按 UDP 开
        ArgumentCaptor<ZlmOpenRtpServerRequest> openCap = ArgumentCaptor.forClass(ZlmOpenRtpServerRequest.class);
        verify(zlm).openRtpServer(eq("vision-zlm"), openCap.capture());
        assertFalse(openCap.getValue().enableTcp());
    }

    @Test
    @DisplayName("#4 档案 mediaTcpMode=null → 级联点播兜底 TCP 被动(1)")
    void cascadeProfileNullFallsBackToTcp() {
        cascadeDevice(0);
        dev.setMediaTcpMode(null); // 档案未配置收流模式
        stubNewSessionChain(false);

        svc.play(CH_DB_ID, 0, 1, 1L, Set.of("cascade:play"));

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getTcpMode());
    }

    @Test
    @DisplayName("#5 本域设备(type=1)不受影响: 请求 tcpMode=1 原样生效, 档案不参与")
    void localDeviceUnaffected() {
        dev.setDeviceType(1);
        dev.setMediaTcpMode(0); // 本域档案不参与: 会话应保持请求值 1
        stubNewSessionChain(false);

        MediaSessionService.PlayResult r = svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play"));

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getTcpMode());
        assertEquals("INVITING", r.status());
    }

    @Test
    @DisplayName("#6 已有活跃会话的级联通道同样 403(校验在锁/复用判定之前)")
    void reusePathAlsoBlockedWithoutPerm() {
        cascadeDevice(1);
        // 若走到复用判定, 这里存在可复用的活跃会话 —— 但校验在锁前, 不应触达
        GbMediaSessionEntity active = new GbMediaSessionEntity();
        active.setId(10L);
        active.setSessionType(GbMediaSessionEntity.TYPE_LIVE);
        active.setStreamId("gb_play_34020000001310000001");
        active.setChannelDbId(CH_DB_ID);
        active.setStreamProfile(1);
        active.setStatus("STREAMING");
        active.setViewerCount(1);
        lenient().when(sessionMapper.selectList(any())).thenReturn(List.of(active));

        BizException e = assertThrows(BizException.class,
                () -> svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play")));

        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(sessionMapper, never()).selectList(any());
        verify(redisson, never()).getLock(anyString());
        verify(zlm, never()).openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class));
    }

    // ===== M3-B3 阶段3: 级联回放分流（docs/08 §3.3, 回放与点播同策略）=====

    @Test
    @DisplayName("#7 级联通道回放无 cascade:play → 403 且不开收流不发 INVITE")
    void cascadePlaybackWithoutPermForbidden() {
        cascadeDevice(1);

        BizException e = assertThrows(BizException.class, () -> svc.playPlayback(
                CH_DB_ID, 1, 1L, Set.of("record:play"), WIN_START, WIN_END));

        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(zlm, never()).openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class));
        verify(invite, never()).sendInvite(any(), any(), any(), anyString(), anyInt());
        // 校验在取锁之前: 未取通道锁、未查活跃会话
        verify(redisson, never()).getLock(anyString());
        verify(sessionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("#8 级联回放有权限 → 档案 mediaTcpMode=0 覆盖缺省 TCP, 回放 SSRC 首位 1")
    void cascadePlaybackUsesProfileTcpMode() {
        cascadeDevice(0);
        stubNewSessionChain(true);

        MediaSessionService.PlayResult r = svc.playPlayback(
                CH_DB_ID, null, 1L, Set.of("record:play", "cascade:play"), WIN_START, WIN_END);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getTcpMode()); // 档案 UDP 覆盖缺省 TCP 被动
        assertEquals(GbMediaSessionEntity.TYPE_PLAYBACK, cap.getValue().getSessionType());
        assertEquals("1123456789", cap.getValue().getSsrc()); // 回放 SSRC 首位 1
        assertEquals(WIN_START, cap.getValue().getPlaybackStart());
        assertEquals(WIN_END, cap.getValue().getPlaybackEnd());
        assertEquals("INVITING", r.status());
        ArgumentCaptor<ZlmOpenRtpServerRequest> openCap = ArgumentCaptor.forClass(ZlmOpenRtpServerRequest.class);
        verify(zlm).openRtpServer(eq("vision-zlm"), openCap.capture());
        assertFalse(openCap.getValue().enableTcp());
    }

    @Test
    @DisplayName("#9 本域回放不受影响: tcpMode 缺省 1(TCP 被动), perms 传入但不参与分流")
    void localPlaybackUnaffected() {
        dev.setDeviceType(1);
        dev.setMediaTcpMode(0); // 本域档案不参与
        stubNewSessionChain(true);

        MediaSessionService.PlayResult r = svc.playPlayback(
                CH_DB_ID, null, 1L, Set.of("record:play"), WIN_START, WIN_END);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getTcpMode());
        assertEquals(GbMediaSessionEntity.TYPE_PLAYBACK, cap.getValue().getSessionType());
        assertEquals("INVITING", r.status());
        ArgumentCaptor<ZlmOpenRtpServerRequest> openCap = ArgumentCaptor.forClass(ZlmOpenRtpServerRequest.class);
        verify(zlm).openRtpServer(eq("vision-zlm"), openCap.capture());
        assertTrue(openCap.getValue().enableTcp());
    }

    @Test
    @DisplayName("#10 级联回放 perms=null(内部调用) → 跳过校验且不覆盖 tcpMode(保持请求 0)")
    void internalPlaybackSkipsCheckAndKeepsRequestedTcpMode() {
        cascadeDevice(1); // 档案: TCP —— 若被覆盖会话应变 1, 以此证明"未覆盖"
        stubNewSessionChain(true);

        svc.playPlayback(CH_DB_ID, 0, null, null, WIN_START, WIN_END); // 内部调用: userId/perms 均空

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getTcpMode());
    }

    // ===== M5-B2 联调: 目录节点（虚拟分组/行政区划/平台根）不可点播/回放 =====

    @Test
    @DisplayName("#11 目录节点(parental=1)点播 → 拒绝且不开收流不发 INVITE, 权限齐全也拦")
    void directoryChannelByReportedParentalRejected() {
        cascadeDevice(1);
        ch.setParental(1); // 对端上报有子节点; perms 齐全也必须在信令前拦下

        BizException e = assertThrows(BizException.class,
                () -> svc.play(CH_DB_ID, 1, 1, 1L, Set.of("device:play", "cascade:play")));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(zlm, never()).openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class));
        verify(invite, never()).sendInvite(any(), any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("#12 目录节点(parental 漏报, 但本设备存在以其为 parentId 的通道) → 结构判定同样拒绝")
    void directoryChannelByStructureRejected() {
        cascadeDevice(1);
        // LiveGBS 式漏报: 目录节点 parental=0/null, 但有子通道挂在它下面
        when(channelMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> svc.playPlayback(CH_DB_ID, 1, 1L, Set.of("record:play", "cascade:play"),
                        WIN_START, WIN_END));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(zlm, never()).openRtpServer(anyString(), any(ZlmOpenRtpServerRequest.class));
        verify(invite, never()).sendInvite(any(), any(), any(), anyString(), anyInt());
    }
}
