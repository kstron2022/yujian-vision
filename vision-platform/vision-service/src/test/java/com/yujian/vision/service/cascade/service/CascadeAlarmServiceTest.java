package com.yujian.vision.service.cascade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.service.alarm.entity.GbAlarmEvent;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cascade alarm subscription + fan-out unit tests (M3-B4 stage 2). Pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
class CascadeAlarmServiceTest {

    private static final String UPPER_ID = "34020000002100000001";
    private static final String LOCAL_ID = "34020000001320000002";
    private static final String CH_ID = "34020000001320000011";

    @Mock private GbCascadeUpperMapper upperMapper;
    @Mock private GbCascadeSharedChannelMapper sharedMapper;
    @Mock private SipTransportProvider transport;
    private final ManScdpCodec codec = new ManScdpCodec();

    private CascadeAlarmService service;
    private GbCascadeUpperEntity upper;

    @BeforeEach
    void setUp() {
        service = new CascadeAlarmService(upperMapper, sharedMapper, transport,
                new GbPlatformProperties(), codec, new CascadeOutboundCodec("UTF-8"));
        ReflectionTestUtils.setField(service, "defaultExpires", 3600);
        upper = new GbCascadeUpperEntity();
        upper.setId(4L);
        upper.setUpperDeviceId(UPPER_ID);
        upper.setLocalDeviceId(LOCAL_ID);
        upper.setStatus(1);
        upper.setCatalogMode(1);
    }

    // ---------------------------------------------------------------- fixtures

    private SipEvent subscribeEvent(String callId, Integer expires, String toTag) {
        return SipEvent.builder()
                .method("SUBSCRIBE")
                .stackId("main")
                .fromUser(UPPER_ID)
                .fromTag("upper-tag-1")
                .toUser(LOCAL_ID)
                .toTag(toTag)
                .callId(callId)
                .event("Alarm")
                .expires(expires)
                .contactHost("10.0.0.9")
                .contactPort(5090)
                .build();
    }

    private SipEvent subscribeEventEvent(String callId, Integer expires) {
        return SipEvent.builder()
                .method("SUBSCRIBE")
                .fromUser(UPPER_ID)
                .callId(callId)
                .event("Catalog")
                .expires(expires)
                .build();
    }

    private GbAlarmEvent alarm(Long channelDbId) {
        GbAlarmEvent a = new GbAlarmEvent();
        a.setDeviceId("34020000001320000001");
        a.setChannelDbId(channelDbId);
        a.setChannelId(CH_ID);
        a.setAlarmMethod(3);
        a.setAlarmType(2);
        a.setAlarmPriority(1);
        a.setAlarmTime(LocalDateTime.of(2026, 9, 14, 10, 0, 0));
        a.setDescription("sim-inject-alarm");
        return a;
    }

    private GbCascadeSharedChannelEntity sharedCh(long chDbId, String virtualId) {
        GbCascadeSharedChannelEntity s = new GbCascadeSharedChannelEntity();
        s.setUpperId(4L);
        s.setChannelDbId(chDbId);
        s.setEnabled(1);
        s.setVirtualDeviceId(virtualId);
        return s;
    }

    private void stubUpperFound() {
        when(upperMapper.selectList(any())).thenReturn(List.of(upper));
        lenient().when(upperMapper.selectById(4L)).thenReturn(upper);
    }

    @SuppressWarnings("unchecked")
    private String grantedTag(int n) {
        ArgumentCaptor<Map<String, String>> hdr = ArgumentCaptor.forClass(Map.class);
        verify(transport, times(n)).sendResponse(any(), anyInt(), hdr.capture());
        return hdr.getValue().get("ToTag");
    }

    // ---------------------------------------------------------------- subscribe

    @Test
    void subscribeNonUpperNotHandled() {
        when(upperMapper.selectList(any())).thenReturn(List.of());
        boolean handled = service.onUpperSubscribe(subscribeEvent("sub-1", 1800, null));
        assertThat(handled).isFalse();
        verify(transport, never()).sendResponse(any(), anyInt(), any());
    }

    @Test
    void subscribeWrongEventPackageRejected() {
        boolean handled = service.onUpperSubscribe(subscribeEventEvent("sub-2", 1800));
        assertThat(handled).isFalse();
        verify(transport, never()).sendResponse(any(), anyInt(), any());
    }

    @Test
    void subscribeCreatesDialogWithExplicitTag() {
        stubUpperFound();
        boolean handled = service.onUpperSubscribe(subscribeEvent("sub-3", 1800, null));
        assertThat(handled).isTrue();
        String tag = grantedTag(1);
        assertThat(tag).isNotBlank();
        assertThat(service.subCount()).isEqualTo(1);
    }

    @Test
    void subscribeRespectsRequestExpires() {
        stubUpperFound();
        boolean handled = service.onUpperSubscribe(subscribeEvent("sub-4", 60, null));
        assertThat(handled).isTrue();
        assertThat(grantedTag(1)).isNotBlank();
        verify(transport).sendResponse(any(), eq(200),
                argThat(h -> "60".equals(h.get("Expires"))));
    }

    @Test
    void unsubscribeRemovesSession() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-5", 1800, null));
        String tag = grantedTag(1);
        boolean handled = service.onUpperSubscribe(subscribeEvent("sub-5", 0, tag));
        assertThat(handled).isTrue();
        assertThat(service.subCount()).isEqualTo(0);
    }

    @Test
    void resubscribeKeepsLocalTag() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-6", 1800, null));
        String tag = grantedTag(1);
        service.onUpperSubscribe(subscribeEvent("sub-6", 1800, tag));
        String tag2 = grantedTag(2);
        assertThat(tag2).isEqualTo(tag);
        assertThat(service.subCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fan-out

    @Test
    void onAlarmSharedChannelNotifies() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-7", 1800, null));
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(5L, null)));
        service.onAlarm(alarm(5L));
        verify(transport, timeout(2000).times(1)).send(argThat((SipMessageContext ctx) ->
                ctx != null && "NOTIFY".equals(ctx.getMethod())
                        && CH_ID.equals(extractDeviceId(ctx.getBody()))));
    }

    @Test
    void onAlarmVirtualIdReplacement() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-8", 1800, null));
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(5L, "V00000000000000001")));
        service.onAlarm(alarm(5L));
        verify(transport, timeout(2000).times(1)).send(argThat((SipMessageContext ctx) ->
                ctx != null && "V00000000000000001".equals(extractDeviceId(ctx.getBody()))));
    }

    @Test
    void onAlarmNotSharedSkipped() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-9", 1800, null));
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(6L, null)));
        service.onAlarm(alarm(5L));
        verify(transport, never()).send(any());
    }

    @Test
    void onAlarmCatalogMode2AllChannels() {
        upper.setCatalogMode(2);
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-10", 1800, null));
        when(sharedMapper.selectList(any())).thenReturn(List.of());
        service.onAlarm(alarm(5L));
        verify(transport, timeout(2000).times(1)).send(argThat((SipMessageContext ctx) ->
                ctx != null && CH_ID.equals(extractDeviceId(ctx.getBody()))));
    }

    @Test
    void onAlarmDisabledUpperSkipsNotify() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-11", 1800, null));
        upper.setStatus(0); // upper re-read via selectById inside notifyUpper
        service.onAlarm(alarm(5L));
        verify(transport, never()).send(any());
    }

    @Test
    void onAlarmDeviceLevelSkipped() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-12", 1800, null));
        service.onAlarm(alarm(null));
        verify(transport, never()).send(any());
    }

    @Test
    void onUpperGoneClearsSession() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-13", 1800, null));
        service.onUpperGone(UPPER_ID);
        service.onAlarm(alarm(5L));
        verify(transport, never()).send(any());
    }

    @Test
    void onAlarmPerUpperFailureIsolation() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-14", 1800, null));
        when(sharedMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        service.onAlarm(alarm(5L));
        // isolation: shared query throws -> swallowed, caller unaffected, no send
        verify(transport, never()).send(any());
    }

    @Test
    void expiryTickRemovesExpiredSession() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-15", 1800, null));
        assertThat(service.subCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> subs = (java.util.Map<String, ?>)
                ReflectionTestUtils.getField(service, "subs");
        Object sub = java.util.Objects.requireNonNull(subs, "subs").get(UPPER_ID);
        org.assertj.core.api.Assertions.assertThat(sub).isNotNull();
        ReflectionTestUtils.setField(sub, "expireAt", LocalDateTime.now().minusSeconds(1));
        service.tick();
        assertThat(service.subCount()).isEqualTo(0);
    }

    @Test
    void onAlarmSendThrowsIsolatedAndRetriedNextAlarm() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-16", 1800, null));
        when(sharedMapper.selectList(any())).thenReturn(List.of(sharedCh(5L, null)));
        org.mockito.Mockito.doThrow(new RuntimeException("net down"))
                .when(transport).send(any());

        // first alarm: send throws, must not propagate to caller (ingest bypass contract)
        service.onAlarm(alarm(5L));

        // session survives the failure: next alarm retries
        org.mockito.Mockito.doNothing().when(transport).send(any());
        service.onAlarm(alarm(5L));
        verify(transport, times(2)).send(any());
        assertThat(service.subCount()).isEqualTo(1);
    }

    @Test
    void onAlarmExpiredSessionSkippedBeforeTick() {
        stubUpperFound();
        service.onUpperSubscribe(subscribeEvent("sub-17", 1800, null));
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> subs = (java.util.Map<String, ?>)
                ReflectionTestUtils.getField(service, "subs");
        Object sub = java.util.Objects.requireNonNull(subs, "subs").get(UPPER_ID);
        ReflectionTestUtils.setField(sub, "expireAt", LocalDateTime.now().minusSeconds(1));

        service.onAlarm(alarm(5L));

        // notifyUpper's own expireAt guard fires even though tick() has not run yet
        verify(transport, never()).send(any());
    }

    // ---------------------------------------------------------------- helpers

    private static String extractDeviceId(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<DeviceID>([^<]+)</DeviceID>").matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
