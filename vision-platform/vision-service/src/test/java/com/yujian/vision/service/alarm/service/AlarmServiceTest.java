package com.yujian.vision.service.alarm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.alarm.entity.GbAlarmEvent;
import com.yujian.vision.service.alarm.mapper.GbAlarmEventMapper;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.system.service.NoticeService;
import com.yujian.vision.sip.transport.SipEvent;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 报警入库/处理单测（M3-B4）：来源校验 + 字段归一化 + 处理护栏 + 旁路通知。
 * 纯 Mockito, 不起 Spring（风格同 CatalogSubscriptionServiceTest）。
 */
@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

    private static final String DEV_ID = "34020000001320000001";
    private static final String CH_ID = "34020000001320000011";

    @Mock
    private GbAlarmEventMapper alarmMapper;
    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private GbDeviceChannelMapper channelMapper;
    @Mock
    private StreamEventPublisher eventPublisher;
    @Mock
    private NoticeService noticeService;
    @Mock
    private com.yujian.vision.service.cascade.service.CascadeAlarmService cascadeAlarmService;

    private AlarmService svc;
    private GbDeviceEntity device;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbAlarmEvent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceChannelEntity.class);
    }

    @BeforeEach
    void setUp() {
        svc = new AlarmService(alarmMapper, deviceMapper, channelMapper, eventPublisher, noticeService,
                cascadeAlarmService);
        device = device();
        lenient().when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    // ---------------------------------------------------------------- fixtures

    /** 本域设备档案: id 5 / 编码 DEV_ID / type=1 / 在线 */
    private static GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(5L);
        d.setDeviceId(DEV_ID);
        d.setDeviceType(1);
        d.setOnline(1);
        return d;
    }

    /** 入站报警 SIP 事件（MESSAGE/NOTIFY 共用形态, fromUser=设备编码） */
    private static SipEvent alarmEvent(String fromUser) {
        return SipEvent.builder()
                .type(SipEvent.Type.REQUEST)
                .method("MESSAGE")
                .callId("alarm-call-id")
                .fromUser(fromUser)
                .build();
    }

    /** Notify/Alarm 报文解析结果（alarmTime 传 null 用默认 ISO 时刻） */
    private static ManScdpMessage alarmMessage(String source, String description) {
        return ManScdpMessage.builder()
                .rootType("Notify")
                .cmdType("Alarm")
                .sn(9L)
                .deviceId(source)
                .alarmMethod(3)
                .alarmType(2)
                .alarmPriority(1)
                .alarmTime("2026-09-14T10:00:00")
                .alarmDescription(description)
                .build();
    }

    // ---------------------------------------------------------------- 入库（#1~#4）

    @Test
    @DisplayName("#1 设备自报: 入库 + WS ALARM + 站内通知(一二级→级别2)")
    void ingestHappyPath() {
        svc.ingest(alarmEvent(DEV_ID), alarmMessage(DEV_ID, "视频报警测试"));

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).insert(cap.capture());
        GbAlarmEvent e = cap.getValue();
        assertEquals(5L, e.getDeviceDbId());
        assertEquals(DEV_ID, e.getDeviceId());
        assertNull(e.getChannelDbId());
        assertNull(e.getChannelId());
        assertEquals(3, e.getAlarmMethod());
        assertEquals(2, e.getAlarmType());
        assertEquals(1, e.getAlarmPriority());
        assertEquals(0, e.getHandled());
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 0, 0), e.getAlarmTime());
        verify(eventPublisher).alarmEvent(DEV_ID, null, 2, 1, "2026-09-14T10:00:00");
        verify(noticeService).publish(eq("ALARM"), eq("设备报警"), contains(DEV_ID), eq(2), eq(DEV_ID));
    }

    @Test
    @DisplayName("#2 From 设备档案在, 但报警源为未知编码(非本设备/其通道) → 丢弃")
    void ingestFromForeignSourceRejected() {
        when(channelMapper.selectOne(any())).thenReturn(null);

        svc.ingest(alarmEvent(DEV_ID), alarmMessage("99990000001320000099", "外源报警"));

        verify(alarmMapper, never()).insert(any(GbAlarmEvent.class));
        verify(eventPublisher, never()).alarmEvent(any(), any(), any(), any(), any());
        verify(noticeService, never()).publish(any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("#3 报警源为在册通道: 入库带 channelDbId/channelId, WS 带 channelId")
    void ingestChannelOwnedAccepted() {
        GbDeviceChannelEntity channel = new GbDeviceChannelEntity();
        channel.setId(9L);
        channel.setDeviceDbId(5L);
        channel.setChannelId(CH_ID);
        when(channelMapper.selectOne(any())).thenReturn(channel);

        svc.ingest(alarmEvent(DEV_ID), alarmMessage(CH_ID, "通道视频报警"));

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).insert(cap.capture());
        assertEquals(9L, cap.getValue().getChannelDbId());
        assertEquals(CH_ID, cap.getValue().getChannelId());
        assertEquals(5L, cap.getValue().getDeviceDbId());
        verify(eventPublisher).alarmEvent(eq(DEV_ID), eq(CH_ID), eq(2), eq(1), anyString());
    }

    @Test
    @DisplayName("#3b 单通道设备同码报警(DeviceID==通道编码): 带上同码通道归属(M3-B4 阶段2)")
    void ingestSelfChannelSameCodeResolved() {
        GbDeviceChannelEntity self = new GbDeviceChannelEntity();
        self.setId(11L);
        self.setDeviceDbId(5L);
        self.setChannelId(DEV_ID);
        when(channelMapper.selectOne(any())).thenReturn(self);

        svc.ingest(alarmEvent(DEV_ID), alarmMessage(DEV_ID, "同码通道报警"));

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).insert(cap.capture());
        assertEquals(11L, cap.getValue().getChannelDbId());
        assertEquals(DEV_ID, cap.getValue().getChannelId());
        verify(eventPublisher).alarmEvent(eq(DEV_ID), eq(DEV_ID), eq(2), eq(1), anyString());
        verify(cascadeAlarmService).onAlarm(any());
    }

    @Test
    @DisplayName("#4 From 无设备档案 → 丢弃(未知来源报警不落库)")
    void ingestUnknownDeviceRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        svc.ingest(alarmEvent("99990000002000000001"), alarmMessage(DEV_ID, "无档案来源"));

        verify(alarmMapper, never()).insert(any(GbAlarmEvent.class));
        verify(eventPublisher, never()).alarmEvent(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 处理与归一化（#5~#9）

    @Test
    @DisplayName("#5 处理: 未处理报警 → handled=1/处理人/处理时间落库")
    void handleMarksHandled() {
        GbAlarmEvent exist = new GbAlarmEvent();
        exist.setId(7L);
        exist.setHandled(0);
        when(alarmMapper.selectById(7L)).thenReturn(exist);

        svc.handle(7L, "admin");

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getHandled());
        assertEquals("admin", cap.getValue().getHandleUser());
        assertNotNull(cap.getValue().getHandleTime());
    }

    @Test
    @DisplayName("#6 处理: 报警不存在 → BizException")
    void handleUnknownIdThrows() {
        when(alarmMapper.selectById(7L)).thenReturn(null);

        assertThrows(BizException.class, () -> svc.handle(7L, "admin"));
        verify(alarmMapper, never()).updateById(any(GbAlarmEvent.class));
    }

    @Test
    @DisplayName("#7 处理: 已处理报警再处理 → BizException(幂等护栏)")
    void handleAlreadyHandledThrows() {
        GbAlarmEvent exist = new GbAlarmEvent();
        exist.setId(7L);
        exist.setHandled(1);
        when(alarmMapper.selectById(7L)).thenReturn(exist);

        assertThrows(BizException.class, () -> svc.handle(7L, "admin"));
    }

    @Test
    @DisplayName("#8 描述超 512 字符 → 截断为 512")
    void descriptionTruncatedTo512() {
        String longDesc = "报".repeat(600);
        svc.ingest(alarmEvent(DEV_ID), alarmMessage(DEV_ID, longDesc));

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).insert(cap.capture());
        assertEquals(512, cap.getValue().getDescription().length());
    }

    @Test
    @DisplayName("#9 AlarmTime 空格格式兼容 + 级别缺省 4")
    void alarmTimeParseFallbackAndPriorityDefault() {
        ManScdpMessage msg = ManScdpMessage.builder()
                .rootType("Notify")
                .cmdType("Alarm")
                .sn(10L)
                .deviceId(DEV_ID)
                .alarmPriority(null)
                .alarmTime("2026-09-14 10:00:00")
                .build();
        svc.ingest(alarmEvent(DEV_ID), msg);

        ArgumentCaptor<GbAlarmEvent> cap = ArgumentCaptor.forClass(GbAlarmEvent.class);
        verify(alarmMapper).insert(cap.capture());
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 0, 0), cap.getValue().getAlarmTime());
        assertEquals(4, cap.getValue().getAlarmPriority());
    }
}
