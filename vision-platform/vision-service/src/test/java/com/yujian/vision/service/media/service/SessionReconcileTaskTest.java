package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 会话对账任务单元测试（docs/06 §7）：纯 Mockito, 不起 Spring 上下文/Redis/数据库。
 */
@ExtendWith(MockitoExtension.class)
class SessionReconcileTaskTest {

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private GbMediaServerMapper serverMapper;

    @Mock
    private ZlmApiClient zlm;

    @Mock
    private SessionCleanupService cleanup;

    @Mock
    private MediaSessionService mediaSessionService;

    @Mock
    private StreamEventPublisher publisher;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @Mock
    private RBucket<String> bucket;

    @Mock
    private NoticeService notices;

    @Mock
    private com.yujian.vision.service.ha.HaRuntimeService ha;
    @Mock
    private StreamProxyService streamProxies;

    private SessionReconcileTask task;

    @BeforeAll
    static void initMpMetadata() {
        // LambdaUpdateWrapper.set(...) 在构建期就解析实体列, 单测需手动初始化 MP lambda 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
    }

    @BeforeEach
    void setUp() {
        task = new SessionReconcileTask(sessionMapper, serverMapper, zlm, cleanup,
                mediaSessionService, publisher, redisson, notices, ha, streamProxies);
        // lenient: 备台跳过用例会覆写此桩, 严格模式下覆写前的公共桩不算"未使用"
        org.mockito.Mockito.lenient().when(ha.sipGatesOpen()).thenReturn(true);
        // M5-B1: 反向对账保护集合并入常驻流, 单测缺省空集合
        org.mockito.Mockito.lenient().when(streamProxies.alwaysOnStreamIds())
                .thenReturn(java.util.Set.of());
    }

    @Test
    @DisplayName("HA 门禁(M3-B6 阶段2): 备台身份整轮跳过对账——判死须读主台进程内暂停豁免态")
    void standbySkipsReconcile() throws Exception {
        org.mockito.Mockito.when(ha.sipGatesOpen()).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(redisson, sessionMapper, serverMapper, zlm, cleanup, publisher);
    }

    /** 抢锁成功, 放行对账主体 */
    private void locked() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(true);
    }

    private GbMediaSessionEntity session(long id, String streamId, Integer viewerCount) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(id);
        s.setStatus("STREAMING");
        s.setServerId("vision-zlm");
        s.setStreamId(streamId);
        s.setViewerCount(viewerCount);
        return s;
    }

    private ZlmMediaInfo media(String app, String stream, Integer readers) {
        return new ZlmMediaInfo(app, stream, readers, null, null, "");
    }

    @Test
    @DisplayName("对账: ZLM 无此流 → 断流兜底(会话 CLOSED + 推送 STREAM_CLOSED + 清暂停态)")
    void closesSessionWhenZlmHasNoStream() throws Exception {
        locked();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_x", 1)));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of());
        when(cleanup.close(10L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)")).thenReturn(true);

        task.reconcile();

        verify(cleanup, times(1)).close(10L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)");
        verify(publisher, times(1)).streamClosed("gb_play_x", "stream_lost");
        verify(mediaSessionService, times(1)).forgetPause(10L);
    }

    @Test
    @DisplayName("对账: 暂停豁免(B2 必改#1) → 暂停会话 ZLM 无此流不判死, 续播重开负责; 恢复后照常判死")
    void pauseProtectedSessionNotClosedByReconcile() throws Exception {
        locked();
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setId(7L);
        node.setServerId("vision-zlm");
        when(serverMapper.selectList(any())).thenReturn(List.of(node));
        GbMediaSessionEntity paused = session(10L, "gb_play_paused", 1);
        paused.setCreateTime(LocalDateTime.now().minusSeconds(SessionReconcileTask.GRACE_SECONDS + 30));
        when(sessionMapper.selectList(any())).thenReturn(List.of(paused));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of());
        when(mediaSessionService.isPauseProtected(10L)).thenReturn(true);

        task.reconcile();

        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(publisher, never()).streamClosed(anyString(), anyString());
        verify(mediaSessionService, never()).forgetPause(anyLong());

        // 暂停态解除(普通断流): 无豁免, 照常判死
        when(mediaSessionService.isPauseProtected(10L)).thenReturn(false);
        when(cleanup.close(10L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)")).thenReturn(true);

        task.reconcile();

        verify(cleanup, times(1)).close(10L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)");
        verify(mediaSessionService, times(1)).forgetPause(10L);
    }

    @Test
    @DisplayName("对账: viewer_count 与 ZLM 不一致 → 条件更新修正(仅仍 STREAMING 的行)")
    @SuppressWarnings("unchecked")
    void fixesViewerCountWhenMismatch() throws Exception {
        locked();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_x", 1)));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(media("rtp", "gb_play_x", 5)));
        when(sessionMapper.update(isNull(), any())).thenReturn(1);

        task.reconcile();

        ArgumentCaptor<Wrapper<GbMediaSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper, times(1)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<GbMediaSessionEntity> uw =
                (LambdaUpdateWrapper<GbMediaSessionEntity>) captor.getValue();
        assertTrue(uw.getSqlSet().toLowerCase().contains("viewer_count"));
        String where = uw.getSqlSegment().toLowerCase();
        assertTrue(where.contains("id"));
        assertTrue(where.contains("status"));
        assertTrue(uw.getParamNameValuePairs().containsValue(10L));
        assertTrue(uw.getParamNameValuePairs().containsValue("STREAMING"));
        assertTrue(uw.getParamNameValuePairs().containsValue(5));
    }

    @Test
    @DisplayName("对账: ZLM 有流且 viewer_count 一致 → 不更新不清理")
    void noUpdateWhenViewerCountMatches() throws Exception {
        locked();
        when(sessionMapper.selectList(any())).thenReturn(
                List.of(session(10L, "gb_play_x", 3), session(11L, "gb_play_y", 2)));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                media("rtp", "gb_play_x", 3), media("rtp", "gb_play_y", 2)));

        task.reconcile();

        verify(sessionMapper, never()).update(any(), any());
        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(publisher, never()).streamClosed(anyString(), anyString());
        verify(zlm, never()).closeStreams(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("对账: 节点不可达 → 本轮整节点跳过, 绝不误杀会话")
    void skipsUnreachableNode() throws Exception {
        locked();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_x", 1)));
        when(zlm.listMedia("vision-zlm")).thenThrow(new RuntimeException("connect timeout"));

        assertDoesNotThrow(() -> task.reconcile());

        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(publisher, never()).streamClosed(anyString(), anyString());
        verify(sessionMapper, never()).update(any(), any());
        verify(zlm, never()).closeStreams(anyString(), anyString(), anyString());
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("对账: ZLM 上平台无主流的 rtp 流 → 强制注销(防串流兜底)")
    void removesOrphanStreams() throws Exception {
        locked();
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_y", 2)));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                media("rtp", "gb_play_x", 1), media("rtp", "gb_play_y", 2), media("hls", "gb_play_x", 0)));

        task.reconcile();

        verify(zlm, times(1)).closeStreams("vision-zlm", "rtp", "gb_play_x");
        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("对账: 抢不到分布式锁 → 本轮直接放弃, 无任何交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(sessionMapper, serverMapper, zlm, cleanup, publisher);
    }

    @Test
    @DisplayName("对账: 节点流数落库 + 端口段 80% 容量告警路径不炸")
    void persistsStreamCountAndAlarmsOnCapacity() throws Exception {
        locked();
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setId(7L);
        node.setServerId("vision-zlm");
        node.setRtpPortStart(30000);
        node.setRtpPortEnd(30500);
        when(serverMapper.selectList(any())).thenReturn(List.of(node));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_0", 1)));

        List<ZlmMediaInfo> streams = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            streams.add(new ZlmMediaInfo("rtp", "gb_play_" + i, 1, null, null, ""));
        }
        when(zlm.listMedia("vision-zlm")).thenReturn(streams);

        assertDoesNotThrow(() -> task.reconcile());

        verify(serverMapper, times(1)).updateById(any(GbMediaServerEntity.class));
        verify(sessionMapper, never()).update(any(), any());
        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("端口容量告警升级(B4): 超阈值 → 站内通知(type=NODE_CAPACITY) + 30 分钟去重命中静默")
    void capacityAlarmPublishesNoticeWithDedup() throws Exception {
        locked();
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setId(7L);
        node.setServerId("vision-zlm");
        node.setRtpPortStart(30000);
        node.setRtpPortEnd(30100);   // 容量 101, 100 流 >= 80% 触发
        when(serverMapper.selectList(any())).thenReturn(List.of(node));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(10L, "gb_play_0", 1)));

        List<ZlmMediaInfo> streams = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            streams.add(new ZlmMediaInfo("rtp", "gb_play_" + i, 1, null, null, ""));
        }
        when(zlm.listMedia("vision-zlm")).thenReturn(streams);
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);
        assertDoesNotThrow(() -> task.reconcile());
        verify(notices, times(1)).publish(eq("NODE_CAPACITY"), anyString(), anyString(), eq(2), eq("vision-zlm"));
    }

    @Test
    @DisplayName("对账: INVITING 保护 → 流不判无主流, 会话不判断流")
    void protectsInvitingSessions() throws Exception {
        locked();
        GbMediaSessionEntity inviting = session(20L, "gb_play_inviting", null);
        inviting.setStatus("INVITING");
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session(10L, "gb_play_x", 1), inviting));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                media("rtp", "gb_play_x", 1), media("rtp", "gb_play_inviting", 0), media("rtp", "orphan", 1)));

        task.reconcile();

        verify(zlm, times(1)).closeStreams("vision-zlm", "rtp", "orphan");
        verify(zlm, never()).closeStreams("vision-zlm", "rtp", "gb_play_inviting");
        verify(zlm, never()).closeStreams("vision-zlm", "rtp", "gb_play_x");
        verify(cleanup, never()).close(eq(20L), anyString(), anyString(), anyString());
        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(publisher, never()).streamClosed(anyString(), anyString());
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("对账: 空闲节点(平台零会话) → 反向注销无主流 + 流数落库")
    void reconcilesIdleNode() throws Exception {
        locked();
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setId(7L);
        node.setServerId("vision-zlm");
        when(serverMapper.selectList(any())).thenReturn(List.of(node));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(media("rtp", "orphan", 1)));

        task.reconcile();

        verify(zlm, times(1)).closeStreams("vision-zlm", "rtp", "orphan");
        verify(serverMapper, times(1)).updateById(any(GbMediaServerEntity.class));
        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(publisher, never()).streamClosed(anyString(), anyString());
    }
    @Test
    @DisplayName("新建会话宽限期: createTime 在 90s 内 ZLM 无流不判死; 超期照常判死")
    void youngSessionWithinGraceNotClosed() throws Exception {
        locked();
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setId(7L);
        node.setServerId("vision-zlm");
        when(serverMapper.selectList(any())).thenReturn(List.of(node));

        // 宽限期内(createTime=now): ZLM 无此流也不判死、不修正 viewer
        GbMediaSessionEntity young = session(10L, "gb_play_young", 1);
        young.setCreateTime(LocalDateTime.now());
        when(sessionMapper.selectList(any())).thenReturn(List.of(young));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of());

        task.reconcile();

        verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
        verify(sessionMapper, never()).update(any(), any());
        verifyNoInteractions(publisher);

        // 超过宽限期(createTime=now-120s): 行为不变, 照常断流兜底
        GbMediaSessionEntity old = session(11L, "gb_play_old", 1);
        old.setCreateTime(LocalDateTime.now().minusSeconds(SessionReconcileTask.GRACE_SECONDS + 30));
        when(sessionMapper.selectList(any())).thenReturn(List.of(old));
        when(cleanup.close(11L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)")).thenReturn(true);

        task.reconcile();

        verify(cleanup, times(1)).close(11L, "CLOSED", "stream_lost", "ZLM 无此流(对账兜底)");
        verify(publisher, times(1)).streamClosed("gb_play_old", "stream_lost");
    }
}
