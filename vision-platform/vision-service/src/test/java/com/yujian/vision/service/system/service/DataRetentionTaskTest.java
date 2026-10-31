package com.yujian.vision.service.system.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.system.entity.SysNoticeEntity;
import com.yujian.vision.service.system.mapper.SysLoginLogMapper;
import com.yujian.vision.service.system.mapper.SysNoticeMapper;
import com.yujian.vision.service.system.mapper.SysOperLogMapper;
import com.yujian.vision.service.system.mapper.SysUserMessageMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 数据保留清理任务单元测试：纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionTaskTest {

    @Mock
    private SysOperLogMapper operLogMapper;

    @Mock
    private SysLoginLogMapper loginLogMapper;

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private SysNoticeMapper noticeMapper;

    @Mock
    private SysUserMessageMapper userMessageMapper;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    private DataRetentionTask task;

    @BeforeEach
    void setUp() {
        task = new DataRetentionTask(operLogMapper, loginLogMapper, sessionMapper,
                noticeMapper, userMessageMapper, redisson, 190, 190, 30, 90);
    }


    @Test
    @DisplayName("抢不到分布式锁 → 本轮直接放弃, 无任何交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(operLogMapper, loginLogMapper, sessionMapper, noticeMapper, userMessageMapper);
    }

    @Test
    @DisplayName("分批删除循环: 1000/500/0 序列删尽即止")
    void batchLoopTerminates() throws Exception {
        when(operLogMapper.delete(any())).thenReturn(1000, 500, 0);
        when(loginLogMapper.delete(any())).thenReturn(0);
        when(sessionMapper.delete(any())).thenReturn(0);
        when(noticeMapper.selectList(any())).thenReturn(List.of());

        task.doSweep();

        verify(operLogMapper, times(3)).delete(any());
        verify(loginLogMapper, times(1)).delete(any());
        verify(sessionMapper, times(1)).delete(any());
    }

    @Test
    @DisplayName("通知清理: 先删扇出已读态再删通知本体, 删尽即止")
    void noticePurgeDeletesUserMessagesBeforeNotices() throws Exception {
        when(operLogMapper.delete(any())).thenReturn(0);
        when(loginLogMapper.delete(any())).thenReturn(0);
        when(sessionMapper.delete(any())).thenReturn(0);
        SysNoticeEntity old = new SysNoticeEntity();
        old.setId(77L);
        when(noticeMapper.selectList(any())).thenReturn(List.of(old));
        when(noticeMapper.delete(any())).thenReturn(1);
        when(userMessageMapper.delete(any())).thenReturn(2);

        task.doSweep();

        InOrder order = inOrder(noticeMapper, userMessageMapper);
        order.verify(noticeMapper, times(1)).selectList(any());
        order.verify(userMessageMapper, times(1)).delete(any());
        order.verify(noticeMapper, times(1)).delete(any());
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("单表异常不影响其他表")
    void singleTableFailureDoesNotAffectOthers() throws Exception {
        when(operLogMapper.delete(any())).thenThrow(new RuntimeException("db down"));
        when(loginLogMapper.delete(any())).thenReturn(0);
        when(sessionMapper.delete(any())).thenReturn(0);

        assertDoesNotThrow(() -> task.doSweep());

        verify(loginLogMapper, times(1)).delete(any());
        verify(sessionMapper, times(1)).delete(any());
    }

    @Test
    @DisplayName("媒体会话仅清理终态: wrapper 含 status IN 与 stop_time 条件")
    void mediaSessionOnlyPurgesTerminal() throws Exception {
        when(operLogMapper.delete(any())).thenReturn(0);
        when(loginLogMapper.delete(any())).thenReturn(0);
        when(sessionMapper.delete(any())).thenReturn(0);

        task.doSweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<GbMediaSessionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper, times(1)).delete(captor.capture());
        String sql = ((QueryWrapper<GbMediaSessionEntity>) captor.getValue()).getSqlSegment().toLowerCase();
        assertTrue(sql.contains("status in"));
        assertTrue(sql.contains("stop_time <"));
    }
}
