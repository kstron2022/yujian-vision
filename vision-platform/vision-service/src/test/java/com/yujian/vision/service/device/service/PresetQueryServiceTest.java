package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.service.PresetQueryService.PresetItem;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 预置位查询单元测试（docs/09 M1-8 挂账, M2-B3）：纯 Mockito, 不起 Spring 上下文/数据库/SIP 栈。
 * 覆盖: 通道/设备/云台开关前置校验、单包应答聚合（先 put 再发, 首个匹配 SN 即 complete）、
 * 空 PresetList → 空列表、迟到应答丢弃。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresetQueryServiceTest {

    @Mock
    private SipTransportProvider transport;

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private GbDeviceChannelMapper channelMapper;

    @Mock
    private DeviceAccessRegistry accessRegistry;

    private PresetQueryService service;

    @BeforeEach
    void setUp() {
        service = new PresetQueryService(new ManScdpCodec(), transport, deviceMapper,
                channelMapper, new GbPlatformProperties(), accessRegistry);
    }

    private GbDeviceChannelEntity channel(Integer ptzEnabled) {
        GbDeviceChannelEntity c = new GbDeviceChannelEntity();
        c.setId(11L);
        c.setDeviceDbId(22L);
        c.setChannelId("34020000001320000001");
        c.setPtzEnabled(ptzEnabled);
        return c;
    }

    private GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(22L);
        d.setDeviceId("34020000001110000001");
        d.setOnline(1);
        d.setTransport("UDP");
        d.setIp("192.168.1.64");
        d.setPort(5060);
        return d;
    }

    private void stubOnline(Integer ptzEnabled) {
        when(channelMapper.selectOne(any())).thenReturn(channel(ptzEnabled));
        when(deviceMapper.selectById(22L)).thenReturn(device());
        when(transport.stackIdForTransport("UDP")).thenReturn("main-udp");
    }

    /** send 即回: 用下发上下文里的 SN 构造 PresetQuery 应答, 模拟设备快速应答 */
    private void replyOnSend(ManScdpMessage response) {
        doAnswer(inv -> {
            SipMessageContext ctx = inv.getArgument(0);
            service.onResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("MESSAGE").build(),
                    ManScdpMessage.builder()
                            .rootType("RESPONSE")
                            .cmdType("PresetQuery")
                            .sn(ctx.getSn())
                            .deviceId("34020000001320000001")
                            .items(response.getItems())
                            .build());
            return null;
        }).when(transport).send(any());
    }

    @Test
    @DisplayName("通道不存在 → BAD_REQUEST")
    void channelMissing() {
        when(channelMapper.selectOne(any())).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.query(11L));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verifyNoSend();
    }

    @Test
    @DisplayName("设备离线 → DEVICE_OFFLINE")
    void deviceOffline() {
        when(channelMapper.selectOne(any())).thenReturn(channel(null));
        GbDeviceEntity off = device();
        off.setOnline(0);
        when(deviceMapper.selectById(22L)).thenReturn(off);
        BizException e = assertThrows(BizException.class, () -> service.query(11L));
        assertEquals(ErrorCode.DEVICE_OFFLINE, e.getErrorCode());
        verifyNoSend();
    }

    @Test
    @DisplayName("通道级云台开关 ptzEnabled=0 → FORBIDDEN, 不外发信令")
    void ptzDisabledRejected() {
        when(channelMapper.selectOne(any())).thenReturn(channel(0));
        when(deviceMapper.selectById(22L)).thenReturn(device());
        BizException e = assertThrows(BizException.class, () -> service.query(11L));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        assertEquals("云台控制已被通道级开关禁用", e.getMessage());
        verifyNoSend();
    }

    @Test
    @DisplayName("正常查询: 发 PresetQuery（栈按传输协议选择）, 应答映射为 PresetItem 列表")
    void queryHappyPath() {
        stubOnline(null);
        ManScdpMessage resp = ManScdpMessage.builder()
                .items(List.of(
                        Map.of("PresetID", "1", "PresetName", "gate"),
                        Map.of("PresetID", "2", "PresetName", "lobby")))
                .build();
        replyOnSend(resp);

        List<PresetItem> out = service.query(11L);

        assertEquals(2, out.size());
        assertEquals("1", out.get(0).presetId());
        assertEquals("gate", out.get(0).presetName());
        assertEquals("2", out.get(1).presetId());

        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(captor.capture());
        SipMessageContext ctx = captor.getValue();
        assertEquals("main-udp", ctx.getStackId());
        assertEquals("MESSAGE", ctx.getMethod());
        assertEquals("34020000001320000001@3402000000", ctx.getTo());
        assertTrue(ctx.getBody().contains("<CmdType>PresetQuery</CmdType>"));
        assertTrue(ctx.getBody().contains("<SN>" + ctx.getSn() + "</SN>"));
        assertEquals("Application/MANSCDP+xml", ctx.getContentType());
    }

    @Test
    @DisplayName("无协议专属栈时回退平台默认栈")
    void stackFallbackToPlatformDefault() {
        stubOnline(null);
        when(transport.stackIdForTransport("UDP")).thenReturn(null);
        replyOnSend(ManScdpMessage.builder().items(List.of()).build());

        service.query(11L);

        ArgumentCaptor<SipMessageContext> captor = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(captor.capture());
        assertEquals("main", captor.getValue().getStackId());
    }

    @Test
    @DisplayName("空 PresetList 应答 → 空列表（设备无预置位属正常）")
    void emptyPresetListReturnsEmptyList() {
        stubOnline(1);
        replyOnSend(ManScdpMessage.builder().items(null).build());

        List<PresetItem> out = service.query(11L);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("Item 缺 PresetID → 该条丢弃; 应答完成后迟到应答（SN 已出清）不抛异常")
    void lateOrIncompleteItemsIgnored() {
        stubOnline(null);
        ManScdpMessage resp = ManScdpMessage.builder()
                .items(List.of(Map.of("PresetName", "no-id"), Map.of("PresetID", "7", "PresetName", "ok")))
                .build();
        replyOnSend(resp);

        List<PresetItem> out = service.query(11L);

        assertEquals(1, out.size());
        assertEquals("7", out.get(0).presetId());

        ManScdpMessage late = ManScdpMessage.builder().sn(999999L).items(List.of()).build();
        service.onResponse(SipEvent.builder().build(), late);
    }

    /** 校验异常路径未外发任何 SIP 信令 */
    private void verifyNoSend() {
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.never()).send(any());
    }
}
