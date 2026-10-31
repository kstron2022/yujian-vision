package com.yujian.vision.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.onvif.model.OnvifRecordingSegment;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.spi.DeviceAccessProvider;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** M4-B3 ONVIF 检索分流: Search 成功转换 / 失败整窗单段降级 / 空结果照实返回 */
@ExtendWith(MockitoExtension.class)
class OnvifRecordSearchServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 18, 0, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 19, 0, 0, 0);

    @Mock
    private DeviceAccessRegistry registry;
    @Mock
    private DeviceAccessProvider provider;

    private OnvifRecordSearchService svc;
    private GbDeviceEntity device;
    private GbDeviceChannelEntity channel;

    @BeforeEach
    void setUp() {
        svc = new OnvifRecordSearchService(registry);
        device = new GbDeviceEntity();
        device.setId(3L);
        device.setDeviceId("34020000001320000006");
        device.setAccessProtocol("ONVIF");
        channel = new GbDeviceChannelEntity();
        channel.setId(7L);
        channel.setChannelId("34020000001320000007");
        channel.setName("mainStream");
        when(registry.of(DeviceProtocol.ONVIF)).thenReturn(provider);
    }

    @Test
    @DisplayName("Search 成功: 段映射 RecordSegment 并窗口收敛, 非降级")
    void searchOk() {
        when(provider.searchRecordings(device, channel, START, END)).thenReturn(List.of(
                new OnvifRecordingSegment("tok1", "IpCamera_1",
                        START.plusHours(2), START.plusHours(3)),
                new OnvifRecordingSegment("tok2", "IpCamera_1",
                        START.plusHours(1), START.plusHours(1).plusMinutes(30))));
        OnvifRecordSearchService.SearchOutcome r = svc.queryOutcome(device, channel, START, END);
        // normalize: 按 start 排序
        assertEquals(2, r.segments().size());
        assertFalse(r.degraded());
        assertEquals("tok2", r.segments().get(0).recordId());
        assertEquals(START.plusHours(1), r.segments().get(0).startTime());
        assertEquals("34020000001320000007", r.segments().get(0).channelId());
        assertEquals("time", r.segments().get(0).type());
    }

    @Test
    @DisplayName("Search 失败 → 整窗单段降级(recordId 空, 窗口=查询窗, degraded=true 供调用方免缓存)")
    void degradedWholeWindow() {
        when(provider.searchRecordings(device, channel, START, END)).thenThrow(
                new BizException(ErrorCode.DEVICE_OFFLINE, "ONVIF 录像检索失败"));
        OnvifRecordSearchService.SearchOutcome r = svc.queryOutcome(device, channel, START, END);
        assertTrue(r.degraded());
        assertEquals(1, r.segments().size());
        assertEquals("", r.segments().get(0).recordId());
        assertEquals(START, r.segments().get(0).startTime());
        assertEquals(END, r.segments().get(0).endTime());
    }

    @Test
    @DisplayName("Search 正常完成但无录像 → 空结果照实返回(非降级, 可缓存)")
    void emptyWhenNoRecordings() {
        when(provider.searchRecordings(device, channel, START, END)).thenReturn(List.of());
        OnvifRecordSearchService.SearchOutcome r = svc.queryOutcome(device, channel, START, END);
        assertFalse(r.degraded());
        assertTrue(r.segments().isEmpty());
    }
}
