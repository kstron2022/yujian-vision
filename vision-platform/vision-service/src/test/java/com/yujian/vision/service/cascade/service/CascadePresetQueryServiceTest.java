package com.yujian.vision.service.cascade.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.service.PresetQueryService;
import com.yujian.vision.service.device.service.PresetQueryService.PresetItem;
import com.yujian.vision.sip.transport.SipEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 上级预置位转查单测（2026-09-26 LiveGBS 补齐）：纯 Mockito, 异步应答用 timeout 验证 */
@ExtendWith(MockitoExtension.class)
class CascadePresetQueryServiceTest {

    private static final String UPPER_ID = "34020000002100000001";
    private static final String LOCAL_ID = "34020000001320000002";
    private static final String TARGET_ID = "34020000001320000011";

    @Mock private CascadeInviteService cascadeInvites;
    @Mock private PresetQueryService presetQueryService;
    @Mock private CascadeSipSender sender;
    @Mock private ManScdpCodec codec;

    private CascadePresetQueryService service;
    private GbCascadeUpperEntity upper;

    @BeforeEach
    void setUp() {
        service = new CascadePresetQueryService(cascadeInvites, presetQueryService, sender, codec);
        upper = new GbCascadeUpperEntity();
        upper.setId(4L);
        upper.setName("sim-upper");
        upper.setUpperDeviceId(UPPER_ID);
        upper.setLocalDeviceId(LOCAL_ID);
        upper.setStatus(1);
    }

    private SipEvent event() {
        return SipEvent.builder().fromUser(UPPER_ID).build();
    }

    private ManScdpMessage query() {
        return ManScdpMessage.builder().rootType("Query").cmdType("PresetQuery")
                .deviceId(TARGET_ID).sn(7L).build();
    }

    @Test
    void unknownUpperIgnored() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(null);
        service.onUpperPresetQuery(event(), query());
        verify(sender, timeout(2000).times(0)).sendMessage(any(), anyString(), anyLong());
    }

    @Test
    void channelNotSharedAnswersEmptyList() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, TARGET_ID)).thenReturn(null);
        when(codec.buildPresetResponse(eq(TARGET_ID), eq(7L), anyList())).thenReturn("resp");
        service.onUpperPresetQuery(event(), query());
        verify(codec, timeout(2000)).buildPresetResponse(
                eq(TARGET_ID), eq(7L), argThat(rows -> rows.isEmpty()));
        verify(sender, timeout(2000).times(1)).sendMessage(eq(upper), anyString(), anyLong());
    }

    @Test
    void forwardsToPresetQueryService() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, TARGET_ID)).thenReturn(5L);
        when(presetQueryService.query(5L)).thenReturn(List.of(new PresetItem("1", "大门")));
        when(codec.buildPresetResponse(eq(TARGET_ID), eq(7L), anyList())).thenReturn("resp");
        service.onUpperPresetQuery(event(), query());
        verify(presetQueryService, timeout(2000)).query(5L);
        verify(codec, timeout(2000)).buildPresetResponse(
                eq(TARGET_ID), eq(7L),
                argThat(rows -> "1".equals(rows.get(0).get("PresetID"))
                        && "大门".equals(rows.get(0).get("PresetName"))));
        verify(sender, timeout(2000).times(1)).sendMessage(eq(upper), anyString(), anyLong());
    }

    @Test
    void queryFailureAnswersEmptyList() {
        when(cascadeInvites.findByUpperDeviceId(UPPER_ID)).thenReturn(upper);
        when(cascadeInvites.resolveChannelDbId(upper, TARGET_ID)).thenReturn(5L);
        when(presetQueryService.query(5L)).thenThrow(new RuntimeException("设备离线"));
        when(codec.buildPresetResponse(eq(TARGET_ID), eq(7L), anyList())).thenReturn("resp");
        service.onUpperPresetQuery(event(), query());
        verify(codec, timeout(2000)).buildPresetResponse(
                eq(TARGET_ID), eq(7L), argThat(rows -> rows.isEmpty()));
        verify(sender, timeout(2000).times(1)).sendMessage(eq(upper), anyString(), anyLong());
    }
}
