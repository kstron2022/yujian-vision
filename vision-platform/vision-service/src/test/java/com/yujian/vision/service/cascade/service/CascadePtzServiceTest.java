package com.yujian.vision.service.cascade.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.control.PtzCmdBuilder;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.service.PtzControlService;
import com.yujian.vision.sip.transport.SipEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 级联 PTZ 透传服务单测（B2 阶段 3 评审补强）：Mockito 纯单测不起 Spring 上下文。
 * 覆盖透传链全部分支: 启用上级/ptz_forward 开关/PTZCmd 与 DeviceID 校验/共享通道反查/
 * 云台与预置位分发/设备侧失败不外抛（200 OK 必发保证）。
 */
@ExtendWith(MockitoExtension.class)
class CascadePtzServiceTest {

    private static final String UPPER_ID = "34020000002100000001";
    private static final String CHANNEL_ID = "34020000001320000001";
    /** buildPtz(UP, 50%) */
    private static final String PTZ_UP_50 = "A50F0108808000BD";

    @Mock
    private CascadeInviteService cascadeInvites;

    @Mock
    private PtzControlService ptzControlService;

    private CascadePtzService service;
    private GbCascadeUpperEntity upper;

    @BeforeEach
    void setUp() {
        service = new CascadePtzService(cascadeInvites, ptzControlService);
        upper = new GbCascadeUpperEntity();
        upper.setId(3L);
        upper.setUpperDeviceId(UPPER_ID);
        upper.setPtzForward(1);
    }

    private SipEvent event() {
        return SipEvent.builder().fromUser(UPPER_ID).build();
    }

    private ManScdpMessage control(String deviceId, String ptzCmd) {
        return ManScdpMessage.builder().rootType("Control").cmdType("DeviceControl")
                .deviceId(deviceId).ptzCmd(ptzCmd).build();
    }

    @Test
    @DisplayName("非启用上级: 忽略, 不反查不透传")
    void unknownUpperIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(null);

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(cascadeInvites, never()).resolveChannelDbId(any(), anyString());
        verify(ptzControlService, never()).control(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("ptz_forward=0: 未开启透传, 忽略")
    void forwardDisabledIgnored() {
        upper.setPtzForward(0);
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(ptzControlService, never()).control(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("无 PTZCmd: 忽略")
    void missingPtzCmdIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);

        service.onUpperControl(event(), control(CHANNEL_ID, null));

        verify(cascadeInvites, never()).resolveChannelDbId(any(), anyString());
    }

    @Test
    @DisplayName("缺 DeviceID: 忽略, 不反查")
    void missingDeviceIdIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);

        service.onUpperControl(event(), control(null, PTZ_UP_50));

        verify(cascadeInvites, never()).resolveChannelDbId(any(), anyString());
    }

    @Test
    @DisplayName("通道未共享: 忽略, 不透传")
    void unsharedChannelIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(null);

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(ptzControlService, never()).control(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("PTZCmd 非法(校验和不符): 忽略, 不透传")
    void badChecksumIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(5L);

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50.substring(0, 14) + "FF"));

        verify(ptzControlService, never()).control(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("正常云台透传: decode(UP,50%) -> control(5, UP, 50)")
    void ptzForwarded() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(5L);

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(ptzControlService).control(5L, "UP", 50);
        verify(ptzControlService, never()).preset(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("预置位透传: buildPreset(GOTO,16) 解码 -> preset(5, GOTO, 16)")
    void presetForwarded() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(5L);
        String hex = PtzCmdBuilder.buildPreset(PtzCmdBuilder.PresetAction.GOTO, 16);

        service.onUpperControl(event(), control(CHANNEL_ID, hex));

        verify(ptzControlService).preset(5L, "GOTO", 16);
        verify(ptzControlService, never()).control(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("设备侧失败(BizException): 不外抛, 200 OK 由 router 必发")
    void deviceFailureSwallowed() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(5L);
        org.mockito.Mockito.doThrow(new BizException(com.yujian.vision.common.api.ErrorCode.DEVICE_OFFLINE, "设备离线"))
                .when(ptzControlService).control(anyLong(), anyString(), anyInt());

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(ptzControlService).control(5L, "UP", 50);
    }

    @Test
    @DisplayName("意外异常(DB 抖动等): 不外抛, 保证 200 OK 必发")
    void unexpectedFailureSwallowed() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, CHANNEL_ID)).thenReturn(5L);
        org.mockito.Mockito.doThrow(new RuntimeException("connection closed"))
                .when(ptzControlService).control(anyLong(), anyString(), anyInt());

        service.onUpperControl(event(), control(CHANNEL_ID, PTZ_UP_50));

        verify(ptzControlService).control(5L, "UP", 50);
    }
}
