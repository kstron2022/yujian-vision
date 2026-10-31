package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * 节点保活检查任务单元测试：纯 Mockito, 不起 Spring 上下文/Redis/数据库。
 */
@ExtendWith(MockitoExtension.class)
class NodeKeepaliveTaskTest {

    @Mock
    private GbMediaServerMapper serverMapper;

    @Mock
    private SessionCleanupService cleanup;

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @Mock
    private RBucket<String> bucket;

    @Mock
    private NoticeService notices;

    private NodeKeepaliveTask task;

    @BeforeEach
    void setUp() {
        task = new NodeKeepaliveTask(serverMapper, cleanup, sessionMapper, redisson, notices);
    }

    /** 抢锁成功, 放行检查主体 */
    private void locked() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(true);
    }

    private GbMediaServerEntity node(long id, String serverId) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setId(id);
        n.setServerId(serverId);
        n.setStatus(1);
        return n;
    }

    private GbMediaSessionEntity session(long id, String streamId) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(id);
        s.setStatus("STREAMING");
        s.setServerId("vision-zlm");
        s.setStreamId(streamId);
        return s;
    }

    @Test
    @DisplayName("保活键在 TTL 内 → 节点保持在线, 无状态变更无清理")
    void healthyNodeUntouched() throws Exception {
        locked();
        when(serverMapper.selectList(any())).thenReturn(List.of(node(7L, "vision-zlm")));
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn("1");

        task.reconcile();

        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
        verify(cleanup, never()).closeAfterRestart(anyLong(), anyString(), anyString(), anyString(),
                anyBoolean());
    }

    @Test
    @DisplayName("保活超时 → 节点置离线 + 活跃会话逐条 tryZlm=false 清理")
    void expiredKeyMarksNodeOfflineAndCleansSessions() throws Exception {
        locked();
        when(serverMapper.selectList(any())).thenReturn(List.of(node(7L, "vision-zlm")));
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(1L, "gb_play_a"), session(2L, "gb_play_b")));

        task.reconcile();

        ArgumentCaptor<GbMediaServerEntity> captor = ArgumentCaptor.forClass(GbMediaServerEntity.class);
        verify(serverMapper, times(1)).updateById(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals(0, captor.getValue().getStatus());

        verify(cleanup, times(1)).closeAfterRestart(1L, "CLOSED", "stream_lost", "ZLM 节点离线(保活超时)", false);
        verify(cleanup, times(1)).closeAfterRestart(2L, "CLOSED", "stream_lost", "ZLM 节点离线(保活超时)", false);
    }

    @Test
    @DisplayName("单条会话清理失败不中断, 继续下一条")
    void continuesWhenSingleSessionCleanupFails() throws Exception {
        locked();
        when(serverMapper.selectList(any())).thenReturn(List.of(node(7L, "vision-zlm")));
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(1L, "gb_play_a"), session(2L, "gb_play_b")));
        doThrow(new RuntimeException("db down")).when(cleanup)
                .closeAfterRestart(1L, "CLOSED", "stream_lost", "ZLM 节点离线(保活超时)", false);

        assertDoesNotThrow(() -> task.reconcile());

        verify(cleanup, times(1)).closeAfterRestart(2L, "CLOSED", "stream_lost", "ZLM 节点离线(保活超时)", false);
    }

    @Test
    @DisplayName("离线节点(status=0)不在检查范围 → 无任何交互")
    void skipsNodesAlreadyOffline() throws Exception {
        locked();
        when(serverMapper.selectList(any())).thenReturn(List.of());

        task.reconcile();

        verifyNoInteractions(sessionMapper, cleanup);
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
        verify(redisson, never()).getBucket(anyString());
    }

    @Test
    @DisplayName("抢不到分布式锁 → 本轮直接放弃, 无任何交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(serverMapper, sessionMapper, cleanup);
    }

    @Test
    @DisplayName("节点离线通知(B4): 保活超时 → 站内通知(type=NODE_OFFLINE, 含清理条数) + 去重命中静默")
    void offlineNodePublishesNoticeOnceWithinDedupWindow() throws Exception {
        locked();
        when(serverMapper.selectList(any())).thenReturn(List.of(node(7L, "vision-zlm")));
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        // 第一次调用 get()=null → 判离线; setIfAbsent=true → 首次发布
        when(bucket.get()).thenReturn(null);
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(1L, "gb_play_a"), session(2L, "gb_play_b")));

        task.reconcile();

        verify(notices, times(1)).publish(eq("NODE_OFFLINE"), anyString(),
                contains("2"), eq(2), eq("vision-zlm"));

        // 30 分钟去重窗口内重入(setIfAbsent=false) → 只 log.debug, 不再发布
        when(bucket.setIfAbsent(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(false);
        when(bucket.get()).thenReturn(null);
        when(serverMapper.selectList(any())).thenReturn(List.of(node(7L, "vision-zlm")));
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        task.reconcile();

        verify(notices, times(1)).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }
}
