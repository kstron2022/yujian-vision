package com.yujian.vision.service.system.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.mapper.GbGroupMapper;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.mapper.SysUserMapper;
import com.yujian.vision.service.system.mapper.SysUserRoleMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 软删数据物理回收任务单元测试：纯 Mockito, 不起 Spring 上下文/数据库。
 * 锁语义/分批删除口径与 DataRetentionTask 同源。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoftDeletePurgeTaskTest {

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private GbDeviceChannelMapper channelMapper;

    @Mock
    private GbGroupMapper groupMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    private SoftDeletePurgeTask task;

    @BeforeEach
    void setUp() {
        task = new SoftDeletePurgeTask(deviceMapper, channelMapper, groupMapper,
                userMapper, userRoleMapper, redisson, 30);
    }

    @Test
    @DisplayName("抢不到分布式锁 → 本轮直接放弃, 无任何交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(deviceMapper, channelMapper, groupMapper, userMapper, userRoleMapper);
    }

    @Test
    @DisplayName("无死行: 四张表各扫一遍即停, 用户表只查不删")
    void noDeadRowsSinglePass() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(true);
        when(deviceMapper.delete(any())).thenReturn(0);
        when(channelMapper.delete(any())).thenReturn(0);
        when(groupMapper.delete(any())).thenReturn(0);
        when(userMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> task.doSweep());

        InOrder order = inOrder(deviceMapper, channelMapper, groupMapper);
        order.verify(deviceMapper, times(1)).delete(any());
        order.verify(channelMapper, times(1)).delete(any());
        order.verify(groupMapper, times(1)).delete(any());
        verify(userRoleMapper, never()).delete(any());
        verify(userMapper, never()).delete(any());
    }

    @Test
    @DisplayName("死行超过一批 → 同表循环删到删尽")
    void loopsBatchesUntilExhausted() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(true);
        when(deviceMapper.delete(any())).thenReturn(1000, 1000, 0);
        when(channelMapper.delete(any())).thenReturn(0);
        when(groupMapper.delete(any())).thenReturn(0);
        when(userMapper.selectList(any())).thenReturn(List.of());

        task.doSweep();

        verify(deviceMapper, times(3)).delete(any());
    }

    @Test
    @DisplayName("软删用户回收: 先删 sys_user_role 残留引用, 再删用户本体")
    void purgesUserRoleRefsBeforeUser() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(true);
        SysUserEntity u = new SysUserEntity();
        u.setId(7L);
        when(userMapper.selectList(any())).thenReturn(List.of(u)).thenReturn(List.of());
        when(userMapper.delete(any())).thenReturn(1);

        task.doSweep();

        InOrder order = inOrder(userRoleMapper, userMapper);
        order.verify(userRoleMapper).delete(any());
        order.verify(userMapper).delete(any());
    }
}
