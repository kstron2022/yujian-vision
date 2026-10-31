package com.yujian.vision.service.device.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.service.CatalogSyncService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 周期目录刷新任务单元测试：纯 Mockito, 不起 Spring 上下文/Redis/数据库。
 */
@ExtendWith(MockitoExtension.class)
class CatalogRefreshTaskTest {

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private CatalogSyncService catalogSyncService;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @Mock
    private com.yujian.vision.service.ha.HaRuntimeService ha;

    private CatalogRefreshTask task;

    @BeforeEach
    void setUp() {
        task = new CatalogRefreshTask(deviceMapper, catalogSyncService, redisson, true, ha);
        // M3-B6 阶段2: refresh 前置 SIP 门禁; 缺省活跃身份放行
        org.mockito.Mockito.lenient().when(ha.sipGatesOpen()).thenReturn(true);
    }

    private GbDeviceEntity device(String deviceId) {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setDeviceId(deviceId);
        d.setOnline(1);
        return d;
    }

    @Test
    @DisplayName("enabled=false → refresh 直接返回, 无任何交互")
    void disabledTaskDoesNothing() {
        CatalogRefreshTask off = new CatalogRefreshTask(deviceMapper, catalogSyncService, redisson, false, ha);

        off.refresh();

        verifyNoInteractions(deviceMapper, catalogSyncService, redisson);
    }

    @Test
    @DisplayName("M3-B6 阶段2: HA 门禁关闭(备台身份) → refresh 不触发 sync")
    void skipsWhenHaGateClosed() {
        org.mockito.Mockito.lenient().when(ha.sipGatesOpen()).thenReturn(false);
        org.mockito.Mockito.lenient().when(redisson.getLock(anyString())).thenReturn(lock);

        task.refresh();

        org.mockito.Mockito.verifyNoInteractions(deviceMapper, catalogSyncService);
    }

    @Test
    @DisplayName("在线设备逐台触发 sync")
    void syncsEachOnlineDevice() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device("d1"), device("d2")));

        task.doRefresh();

        verify(catalogSyncService, times(1)).sync("d1");
        verify(catalogSyncService, times(1)).sync("d2");
    }

    @Test
    @DisplayName("业务态 BizException(目录同步进行中) → 跳过该台, 其余仍触发")
    void bizExceptionSkipsSingleDevice() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device("d1"), device("d2")));
        doThrow(new BizException(ErrorCode.CATALOG_SYNC_RUNNING, "目录同步进行中，请勿重复触发"))
                .when(catalogSyncService).sync("d1");

        assertDoesNotThrow(() -> task.doRefresh());

        verify(catalogSyncService, times(1)).sync("d1");
        verify(catalogSyncService, times(1)).sync("d2");
    }

    @Test
    @DisplayName("非业务异常 → 同样跳过该台, 其余仍触发")
    void runtimeExceptionSkipsSingleDevice() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device("d1"), device("d2")));
        doThrow(new RuntimeException("sip down")).when(catalogSyncService).sync("d1");

        assertDoesNotThrow(() -> task.doRefresh());

        verify(catalogSyncService, times(1)).sync("d1");
        verify(catalogSyncService, times(1)).sync("d2");
    }

    @Test
    @DisplayName("离线设备不在查询结果中 → 不触发 sync")
    void offlineDevicesNotQueried() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        task.doRefresh();

        verify(catalogSyncService, never()).sync(anyString());
    }

    @Test
    @DisplayName("抢不到分布式锁 → 无业务交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task.refresh();

        verifyNoInteractions(deviceMapper, catalogSyncService);
    }
}
