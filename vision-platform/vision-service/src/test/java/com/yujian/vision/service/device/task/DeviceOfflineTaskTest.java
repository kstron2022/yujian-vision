package com.yujian.vision.service.device.task;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.service.DeviceOfflineService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 设备离线判定任务单元测试（docs/06 §1 OFFLINE 迁移; M5-B1 动作委托 DeviceOfflineService）:
 * 纯 Mockito。本文件只锁「判定口径 + 委托」——动作细节（关会话/联动/事件/通知）在
 * DeviceOfflineServiceTest。
 */
@ExtendWith(MockitoExtension.class)
class DeviceOfflineTaskTest {

    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private DeviceOfflineService offlineService;
    @Mock
    private org.redisson.api.RedissonClient redisson;
    @Mock
    private org.redisson.api.RLock lock;

    private DeviceOfflineTask task;

    @BeforeEach
    void setUp() throws Exception {
        task = new DeviceOfflineTask(deviceMapper, offlineService, redisson);
        org.mockito.Mockito.lenient().when(redisson.getLock(anyString())).thenReturn(lock);
        org.mockito.Mockito.lenient().when(lock.tryLock(0L, java.util.concurrent.TimeUnit.SECONDS))
                .thenReturn(true);
    }

    private GbDeviceEntity device(String deviceId, LocalDateTime keepalive) {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(5L);
        d.setDeviceId(deviceId);
        d.setName("cam-1");
        d.setOnline(1);
        d.setHeartbeatInterval(60);
        d.setHeartbeatTimeout(3);
        d.setKeepaliveTime(keepalive);
        d.setRegisterTime(keepalive);
        return d;
    }

    @Test
    @DisplayName("心跳远超 interval×timeout → 委托 DeviceOfflineService.markOffline")
    void staleDeviceDelegatedToOfflineService() {
        GbDeviceEntity stale = device("34020000001320000001", LocalDateTime.now().minusHours(2));
        when(deviceMapper.selectList(any())).thenReturn(List.of(stale));

        task.check();

        verify(offlineService).markOffline(any(GbDeviceEntity.class), contains("3 个心跳周期"));
    }

    @Test
    @DisplayName("心跳新鲜未判离线 → 不委托")
    void healthyDeviceUntouched() {
        GbDeviceEntity fresh = device("34020000001320000001", LocalDateTime.now());
        when(deviceMapper.selectList(any())).thenReturn(List.of(fresh));

        task.check();

        verify(offlineService, never()).markOffline(any(), anyString());
    }

    @Test
    @DisplayName("查询口径含软删过滤: 幽灵行(软删但 online 仍=1)不进离线判定")
    void queryExcludesSoftDeletedRows() {
        // deletedTime 无 @TableLogic, 软删行不会被框架自动过滤; 漏掉 isNull 会让
        // 「删除设备→重新注册」留下的幽灵行在 180s 后触发一次假离线
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        task.check();

        verify(deviceMapper).selectList(argThat(w -> {
            String sql = w.getSqlSegment();
            assertTrue(sql.contains("online"), "应筛在线设备: " + sql);
            assertTrue(sql.contains("deleted_time"), "必须过滤软删行: " + sql);
            return true;
        }));
    }

    @Test
    @DisplayName("M3-B6 阶段2: 抢不到双实例互斥锁 → 本轮放弃, 无业务交互")
    void skipsWhenLockNotAcquired() throws Exception {
        org.mockito.Mockito.lenient().when(lock.tryLock(0L, java.util.concurrent.TimeUnit.SECONDS))
                .thenReturn(false);

        task.check();

        verifyNoInteractions(deviceMapper, offlineService);
    }
}
