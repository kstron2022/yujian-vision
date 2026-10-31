package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.config.MediaProperties;
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
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

/**
 * listSessions 观测上限参数化测试（M3-B4 阶段3, B6 压测口径挂账收口）:
 * LIMIT 取 vision.media.sessions-limit（默认 100）, 0/负值钳到下限 1。
 * #4/#5: getSessionStatus 主键精确查询（M3-B2 挂账清偿, 级联 awaitStreaming 消费方）。
 * 纯 Mockito, 夹具风格对齐 MediaSessionServiceCascadePlayTest。
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceSessionsLimitTest {

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
    private RedissonClient redisson;
    /** 泛型 captor 走 @Captor 注入, 避免 forClass((Class) ...) 裸类型强转 */
    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<GbMediaSessionEntity>> wrapperCap;

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
                ssrcPool, zlm, invite, urlService, publisher, cleanup, transport,
                new GbPlatformProperties(), media, redisson,
                new VisionMetrics(new SimpleMeterRegistry()),
                new MediaNodeScheduler(serverMapper, MediaSessionServiceInviteRetryTest.noopMetrics(), media),
                MediaSessionServiceInviteRetryTest.haGate(),
                org.mockito.Mockito.mock(StreamProxyService.class), null);
        lenient().when(sessionMapper.selectList(any())).thenReturn(List.of());
    }

    private String limitOfLastQuery() {
        verify(sessionMapper).selectList(wrapperCap.capture());
        return wrapperCap.getValue().getSqlSegment();
    }

    @Test
    @DisplayName("#1 默认 LIMIT 100（行为与参数化前一致）")
    void defaultLimit100() {
        svc.listSessions(null);
        assertTrue(limitOfLastQuery().endsWith("LIMIT 100"), limitOfLastQuery());
    }

    @Test
    @DisplayName("#2 配置 500 → LIMIT 500（压测观测放开口径）")
    void configuredLimitApplied() {
        media.setSessionsLimit(500);
        svc.listSessions(null);
        assertTrue(limitOfLastQuery().endsWith("LIMIT 500"), limitOfLastQuery());
    }

    @Test
    @DisplayName("#3 非法配置 0/负数 → 钳到下限 1")
    void invalidLimitClampedToOne() {
        media.setSessionsLimit(0);
        svc.listSessions(null);
        assertTrue(limitOfLastQuery().endsWith("LIMIT 1"), limitOfLastQuery());
    }

    @Test
    @DisplayName("#4 getSessionStatus 命中 → 返回会话状态（M3-B2 挂账清偿, 替换 listSessions 窗口近似）")
    void getSessionStatusHit() {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(7L);
        s.setStatus("STREAMING");
        when(sessionMapper.selectById(7L)).thenReturn(s);
        assertEquals("STREAMING", svc.getSessionStatus(7L));
    }

    @Test
    @DisplayName("#5 getSessionStatus 未命中（未落库/已清理）→ null, 调用方继续等待/超时兜底")
    void getSessionStatusMiss() {
        when(sessionMapper.selectById(99L)).thenReturn(null);
        assertNull(svc.getSessionStatus(99L));
    }
}
