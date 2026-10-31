package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
 * 换节点重试单测（M3-B6 阶段2 挂账①, docs/06 §2）：inviteNew 内 openRtpServer/SSRC 分配失败
 * （ZLM_API_FAILED/SSRC_ALLOC_FAILED）按 vision.media.invite-node-retries 换下一候选节点重试;
 * sendInvite 失败与 insert 的 DB 冲突不重试。纯 Mockito, 不起 Spring 上下文/Redis/数据库。
 * haGate() 为本包各测试共享的 HaRuntimeService 桩（活跃身份放行全部 SIP 门禁）。
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceInviteRetryTest {

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

    /** 本包共享 HA 桩: 活跃身份放行全部 SIP 侧门禁 */
    static com.yujian.vision.service.ha.HaRuntimeService haGate() {
        var m = org.mockito.Mockito.mock(com.yujian.vision.service.ha.HaRuntimeService.class);
        org.mockito.Mockito.lenient().when(m.sipGatesOpen()).thenReturn(true);
        org.mockito.Mockito.lenient().when(m.mayRecoverAtBoot()).thenReturn(true);
        return m;
    }

    /** 备台身份桩: SIP 门禁全关(判死/扫描/对账跳过), 用于 HA 门禁语义用例（M3-B6 阶段2） */
    static com.yujian.vision.service.ha.HaRuntimeService haStandby() {
        var m = org.mockito.Mockito.mock(com.yujian.vision.service.ha.HaRuntimeService.class);
        org.mockito.Mockito.lenient().when(m.sipGatesOpen()).thenReturn(false);
        org.mockito.Mockito.lenient().when(m.mayRecoverAtBoot()).thenReturn(false);
        return m;
    }

    /**
     * 本包共享空指标桩: MediaNodeScheduler 的 ObjectProvider&lt;NodeMetricsTask&gt; 注入,
     * 单测不触发采集（getIfAvailable 默认 null → 调度按"快照未知"兜底）。
     * 裸 mock 收敛在此处统一压制 unchecked, 调用方拿到参数化类型。
     */
    @SuppressWarnings("unchecked")
    static org.springframework.beans.factory.ObjectProvider<com.yujian.vision.service.monitor.NodeMetricsTask> noopMetrics() {
        return org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

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
                new MediaNodeScheduler(serverMapper, noopMetrics(), media),
                haGate(), org.mockito.Mockito.mock(StreamProxyService.class), null);
        ch = new GbDeviceChannelEntity();
        ch.setId(7L);
        ch.setDeviceDbId(3L);
        ch.setChannelId("0102000001");
        ch.setStatus(1);
        dev = new GbDeviceEntity();
        dev.setId(3L);
        dev.setIp("10.0.0.2");
        dev.setPort(5060);
        dev.setOnline(1);
        org.mockito.Mockito.lenient().when(channelMapper.selectById(7L)).thenReturn(ch);
        org.mockito.Mockito.lenient().when(deviceMapper.selectById(3L)).thenReturn(dev);
        org.mockito.Mockito.lenient().when(redisson.getLock(anyString())).thenReturn(lock);
        // 会话查重(无活跃会话复用)与调度器候选查询同走 selectList, 分开 stub
        org.mockito.Mockito.lenient().when(sessionMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(serverMapper.selectList(any())).thenReturn(
                new java.util.ArrayList<>(List.of(node("n1"), node("n2"))));
        org.mockito.Mockito.lenient().when(ssrcPool.allocLive(anyString(), anyString())).thenReturn("0000000001");
        org.mockito.Mockito.lenient().when(invite.sendInvite(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new InviteService.InviteHandle("call-retry-1", "tag"));
        // insert 回填自增 ID(PlayResult.sessionId 为 long 拆箱, null 会 NPE)
        org.mockito.Mockito.lenient().when(sessionMapper.insert(org.mockito.ArgumentMatchers.<GbMediaSessionEntity>any()))
                .thenAnswer(inv -> {
                    inv.getArgument(0, GbMediaSessionEntity.class).setId(99L);
                    return 1;
                });
        org.mockito.Mockito.lenient().when(urlService.buildUrls(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.Map.of("flv", "http://u"));
    }

    private static GbMediaServerEntity node(String id) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(id);
        n.setStatus(1);
        return n;
    }

    @Test
    @DisplayName("首节点 openRtpServer 失败 → 换下一候选成功(insert serverId=n2, n1 SSRC 已释放)")
    void firstNodeFailsThenSecondSucceeds() {
        when(zlm.openRtpServer(eq("n1"), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "connect refused"));
        when(zlm.openRtpServer(eq("n2"), any())).thenReturn(30088);

        svc.play(7L, 0, 1, null, null);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("n2", cap.getValue().getServerId());
        verify(ssrcPool).release(eq("n1"), anyString());
        verify(zlm, times(1)).openRtpServer(eq("n1"), any());
        verify(zlm, times(1)).openRtpServer(eq("n2"), any());
    }

    @Test
    @DisplayName("retries=0 → 历史行为: 单次失败直接抛, 不换节点")
    void zeroRetriesKeepsLegacyBehavior() {
        media.setInviteNodeRetries(0);
        when(zlm.openRtpServer(eq("n1"), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "connect refused"));

        assertThrows(BizException.class, () -> svc.play(7L, 0, 1, null, null));

        verify(zlm, times(1)).openRtpServer(eq("n1"), any());
        verify(zlm, never()).openRtpServer(eq("n2"), any());
        verify(sessionMapper, never()).insert(org.mockito.ArgumentMatchers.<GbMediaSessionEntity>any());
    }

    @Test
    @DisplayName("全候选耗尽 → 抛最后一次失败, 每节点恰好尝试一次")
    void allCandidatesExhaustedThrows() {
        when(zlm.openRtpServer(eq("n1"), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "down-1"));
        when(zlm.openRtpServer(eq("n2"), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "down-2"));

        assertThrows(BizException.class, () -> svc.play(7L, 0, 1, null, null));

        verify(zlm, times(1)).openRtpServer(eq("n1"), any());
        verify(zlm, times(1)).openRtpServer(eq("n2"), any());
        verify(sessionMapper, never()).insert(org.mockito.ArgumentMatchers.<GbMediaSessionEntity>any());
        verify(ssrcPool, times(1)).release(eq("n1"), anyString());
        verify(ssrcPool, times(1)).release(eq("n2"), anyString());
    }

    @Test
    @DisplayName("非可重试错误(INVITE_FAILED) → 不换节点直接抛")
    void nonRetryableErrorDoesNotSwitchNode() {
        when(zlm.openRtpServer(eq("n1"), any())).thenThrow(
                new BizException(ErrorCode.INVITE_FAILED, "人工注入"));

        assertThrows(BizException.class, () -> svc.play(7L, 0, 1, null, null));

        verify(zlm, times(1)).openRtpServer(eq("n1"), any());
        verify(zlm, never()).openRtpServer(eq("n2"), any());
    }

    @Test
    @DisplayName("SSRC 分配失败同样触发换节点(分配未成功无需释放)")
    void ssrcAllocFailureAlsoRetries() {
        when(ssrcPool.allocLive(eq("n1"), anyString())).thenThrow(
                new BizException(ErrorCode.SSRC_ALLOC_FAILED, "SSRC 分配冲突超限"));
        when(zlm.openRtpServer(eq("n2"), any())).thenReturn(30088);

        svc.play(7L, 0, 1, null, null);

        ArgumentCaptor<GbMediaSessionEntity> cap = ArgumentCaptor.forClass(GbMediaSessionEntity.class);
        verify(sessionMapper).insert(cap.capture());
        assertEquals("n2", cap.getValue().getServerId());
        verify(ssrcPool, never()).release(anyString(), anyString());
        verify(zlm, never()).openRtpServer(eq("n1"), any());
        verify(zlm, times(1)).openRtpServer(eq("n2"), any());
    }

    @Test
    @DisplayName("sendInvite 失败 → 不重试不换节点(异常原样上抛)")
    void sendInviteFailureDoesNotRetry() {
        when(invite.sendInvite(any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new BizException(ErrorCode.SYSTEM_ERROR, "SIP 栈不存在"));

        assertThrows(BizException.class, () -> svc.play(7L, 0, 1, null, null));

        verify(zlm, times(1)).openRtpServer(anyString(), any());
        verify(sessionMapper, times(1)).insert(org.mockito.ArgumentMatchers.<GbMediaSessionEntity>any());
    }
}
