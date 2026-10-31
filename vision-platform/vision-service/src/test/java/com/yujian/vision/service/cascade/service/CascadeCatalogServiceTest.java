package com.yujian.vision.service.cascade.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.sip.transport.SipEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

/**
 * Cascade catalog report unit tests (B2 phase 4): pure Mockito.
 * Cases: unknown/disabled upper ignored, empty catalog single empty packet,
 * multi-packet paging with virtual-id replacement, notify push path,
 * missing upper -> BizException. Packet sending runs async on cascade-catalog
 * thread, so verifications use Mockito timeout.
 */
@ExtendWith(MockitoExtension.class)
class CascadeCatalogServiceTest {

    private static final String UPPER_ID = "34020000002100000001";
    private static final String LOCAL_ID = "34020000001320000002";

    @Mock private GbCascadeUpperMapper upperMapper;
    @Mock private GbCascadeSharedChannelMapper sharedMapper;
    @Mock private GbDeviceChannelMapper channelMapper;
    @Mock private CascadeSipSender sender;
    @Mock private ManScdpCodec codec;

    private CascadeCatalogService service;
    private GbCascadeUpperEntity upper;

    @BeforeEach
    void setUp() {
        service = new CascadeCatalogService(upperMapper, sharedMapper, channelMapper, sender, codec);
        ReflectionTestUtils.setField(service, "catalogPageSize", 2);
        ReflectionTestUtils.setField(service, "catalogSendIntervalMs", 0L);
        upper = new GbCascadeUpperEntity();
        upper.setId(4L);
        upper.setName("sim-upper");
        upper.setUpperDeviceId(UPPER_ID);
        upper.setLocalDeviceId(LOCAL_ID);
        upper.setStatus(1);
        upper.setCatalogMode(1);
    }

    private SipEvent event() {
        return SipEvent.builder().fromUser(UPPER_ID).build();
    }

    private ManScdpMessage query(String deviceId, Long sn) {
        return ManScdpMessage.builder().rootType("Query").cmdType("Catalog")
                .deviceId(deviceId).sn(sn).build();
    }

    private GbCascadeSharedChannelEntity sharedCh(long chDbId, String virtualId) {
        GbCascadeSharedChannelEntity s = new GbCascadeSharedChannelEntity();
        s.setUpperId(4L);
        s.setChannelDbId(chDbId);
        s.setEnabled(1);
        s.setVirtualDeviceId(virtualId);
        return s;
    }

    private GbDeviceChannelEntity channel(long id) {
        GbDeviceChannelEntity c = new GbDeviceChannelEntity();
        c.setId(id);
        c.setChannelId("3402000000132000000" + id);
        c.setName("cam-" + id);
        c.setStatus(1);
        return c;
    }

    @Test
    void unknownUpperIgnored() {
        when(upperMapper.selectList(any())).thenReturn(List.of());
        service.onUpperQuery(event(), query(UPPER_ID, 7L));
        verify(sender, never()).sendMessage(any(), anyString(), anyLong());
    }

    @Test
    void disabledUpperIgnored() {
        upper.setStatus(0);
        when(upperMapper.selectList(any())).thenReturn(List.of(upper));
        service.onUpperQuery(event(), query(UPPER_ID, 7L));
        verify(sender, never()).sendMessage(any(), anyString(), anyLong());
    }

    @Test
    void emptyCatalogOnePacket() {
        when(upperMapper.selectList(any())).thenReturn(List.of(upper));
        when(sharedMapper.selectList(any())).thenReturn(List.of());
        when(codec.buildCatalogResponse(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyList()))
                .thenReturn("resp");
        service.onUpperQuery(event(), query(UPPER_ID, 7L));
        verify(codec, timeout(2000)).buildCatalogResponse(
                eq(UPPER_ID), eq(7L), eq(0), anyList());
        verify(sender, timeout(2000).times(1)).sendMessage(any(), anyString(), anyLong());
    }

    @Test
    void multiPacketPaged() {
        when(upperMapper.selectList(any())).thenReturn(List.of(upper));
        when(sharedMapper.selectList(any())).thenReturn(List.of(
                sharedCh(5L, null), sharedCh(6L, null), sharedCh(7L, "V123")));
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(5L), channel(6L), channel(7L)));
        when(codec.buildCatalogResponse(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyList()))
                .thenReturn("resp");
        service.onUpperQuery(event(), query(UPPER_ID, 7L));
        // packet 1: channels 5+6 (original ids), packet 2: channel 7 -> virtual V123
        verify(codec, timeout(2000)).buildCatalogResponse(
                eq(UPPER_ID), eq(7L), eq(3), argThat(list -> list.size() == 2));
        verify(codec, timeout(2000)).buildCatalogResponse(
                eq(UPPER_ID), eq(7L), eq(3), argThat(list -> list.size() == 1));
        verify(sender, timeout(2000).times(2)).sendMessage(eq(upper), anyString(), anyLong());
    }

    @Test
    void pushCatalogNotify() {
        when(upperMapper.selectById(4L)).thenReturn(upper);
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(5L, null)));
        when(channelMapper.selectList(any())).thenReturn(List.of(channel(5L)));
        when(codec.buildCatalogNotify(any(), anyLong(), anyList())).thenReturn("notify");
        service.pushCatalog(4L);
        verify(codec, timeout(2000)).buildCatalogNotify(
                eq(LOCAL_ID), anyLong(), argThat(items -> items.size() == 1));
        verify(sender, timeout(2000).times(1)).sendMessage(any(), anyString(), anyLong());
    }

    @Test
    void ptzTypeReportedWithNormalization() {
        // PTZType 下发（LiveGBS 云台类型列）+ 平台扩展值 5 归一为 0（评审 P3-8）
        when(upperMapper.selectList(any())).thenReturn(List.of(upper));
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(5L, null)));
        GbDeviceChannelEntity c = channel(5L);
        c.setPtzType(5);
        when(channelMapper.selectList(any())).thenReturn(List.of(c));
        when(codec.buildCatalogResponse(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyList()))
                .thenReturn("resp");
        service.onUpperQuery(event(), query(UPPER_ID, 7L));
        verify(codec, timeout(2000)).buildCatalogResponse(
                eq(UPPER_ID), eq(7L), eq(1),
                argThat(items -> items.size() == 1 && "0".equals(items.get(0).get("PTZType"))));
    }

    @Test
    void pushCatalogUpperMissing() {
        when(upperMapper.selectById(8L)).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.pushCatalog(8L))
                .isInstanceOf(BizException.class);
    }
}
