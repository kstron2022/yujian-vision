package com.yujian.vision.service.device.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.service.SessionCleanupService;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 设备在线/离线翻转动作单元测试（M5-B1 从 DeviceOfflineTaskTest 迁入扩展）:
 * 关会话/事件/通知/清订阅动作 + RTSP 通道 status 联动（GB/ONVIF 不联动）。
 * 纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class DeviceOfflineServiceTest {

    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private GbDeviceChannelMapper channelMapper;
    @Mock
    private StreamEventPublisher eventPublisher;
    @Mock
    private SessionCleanupService cleanup;
    @Mock
    private GbMediaSessionMapper sessionMapper;
    @Mock
    private NoticeService noticeService;
    @Mock
    private CatalogSubscriptionService catalogSubscriptionService;
    @Mock
    private AlarmSubscriptionService alarmSubscriptionService;

    private DeviceOfflineService service;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceChannelEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeviceOfflineService(deviceMapper, channelMapper, eventPublisher, cleanup,
                sessionMapper, noticeService, catalogSubscriptionService, alarmSubscriptionService);
    }

    private GbDeviceEntity device(String accessProtocol, Integer online) {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(5L);
        d.setDeviceId("34020000001320000001");
        d.setName("cam-1");
        d.setAccessProtocol(accessProtocol);
        d.setOnline(online);
        d.setHeartbeatInterval(60);
        d.setHeartbeatTimeout(3);
        d.setKeepaliveTime(LocalDateTime.now().minusHours(2));
        return d;
    }

    private GbMediaSessionEntity session(long id, String streamId) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(id);
        s.setStatus("STREAMING");
        s.setDeviceDbId(5L);
        s.setStreamId(streamId);
        return s;
    }
    @Test
    @DisplayName("markOffline(GB): 置离线+事件+通知+清订阅+关停全部活跃会话, 通道表不联动")
    void markOfflineGbNoChannelLink() {
        GbDeviceEntity d = device("GB", 1);
        when(sessionMapper.selectList(any()))
                .thenReturn(List.of(session(1L, "gb_play_a"), session(2L, "gb_play_b")));

        service.markOffline(d, "心跳超时");

        verify(deviceMapper).updateById(any(GbDeviceEntity.class));
        verify(eventPublisher).deviceOffline("34020000001320000001", "cam-1");
        verify(noticeService).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
        verify(catalogSubscriptionService).onDeviceOffline("34020000001320000001");
        verify(alarmSubscriptionService).onDeviceOffline("34020000001320000001");
        verify(cleanup, times(2)).close(anyLong(), anyString(), anyString(), anyString());
        verify(channelMapper, times(0)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    @DisplayName("markOffline(RTSP): 同上动作外, 通道表联动 status=0")
    void markOfflineRtspLinksChannels() {
        GbDeviceEntity d = device("RTSP", 1);
        when(sessionMapper.selectList(any())).thenReturn(List.of());

        service.markOffline(d, "RTSP 探活连续失败");

        verify(channelMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventPublisher).deviceOffline("34020000001320000001", "cam-1");
    }

    @Test
    @DisplayName("keepaliveAndOnline(离线 RTSP): 翻转置 1+联动通道 ON+上线事件")
    void keepaliveFlipsRtspOnline() {
        GbDeviceEntity d = device("RTSP", 0);

        service.keepaliveAndOnline(d);

        verify(deviceMapper).updateById(any(GbDeviceEntity.class));
        verify(channelMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventPublisher).deviceOnline("34020000001320000001", "cam-1");
    }

    @Test
    @DisplayName("keepaliveAndOnline(已在线): 仅刷 keepalive, 不重发事件不联动")
    void keepaliveOnlineNoReflip() {
        GbDeviceEntity d = device("GB", 1);

        service.keepaliveAndOnline(d);

        verify(deviceMapper).updateById(any(GbDeviceEntity.class));
        verify(channelMapper, times(0)).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventPublisher, times(0)).deviceOnline(any(), any());
    }
}
