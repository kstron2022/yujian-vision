package com.yujian.vision.service.alarm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 报警订阅状态机单测（M3-B4）：订阅发起（Event: Alarm, 全设备）/授予 Expires 续订节奏/
 * 无应答重建/被拒清理。结构与断言风格 mirror CatalogSubscriptionServiceTest。
 * 纯 Mockito, 不起 Spring。
 */
@ExtendWith(MockitoExtension.class)
class AlarmSubscriptionServiceTest {

    /** 设备编码（fixture 设备 deviceId, 亦为 subs 表键） */
    private static final String DEV_ID = "34020000001320000001";
    /** 平台侧编码（GbPlatformProperties stub 值, from 头前缀断言用） */
    private static final String SELF_ID = "34020000002000000001";
    private static final String DOMAIN = "3402000000";

    @Mock
    private SipTransportProvider transport;
    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private GbPlatformProperties platform;

    private AlarmSubscriptionService svc;
    private GbDeviceEntity device;

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 列名解析需要实体元数据（mock mapper 不触达 DB, 仅为 wrapper 构建铺路）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        // codec 用真实实例（buildAlarmQuery 为纯函数, mock 无意义）; HA 桩活跃身份放行 tick 门禁(M3-B6 阶段2)
        com.yujian.vision.service.ha.HaRuntimeService ha =
                org.mockito.Mockito.mock(com.yujian.vision.service.ha.HaRuntimeService.class);
        org.mockito.Mockito.lenient().when(ha.sipGatesOpen()).thenReturn(true);
        svc = new AlarmSubscriptionService(transport, deviceMapper, platform, new ManScdpCodec(), ha);
        ReflectionTestUtils.setField(svc, "subscribeExpires", 3600);
        // 三个 platform stub 各用例覆盖面不一, 用 lenient 避免严格模式误报未用桩
        lenient().when(platform.getStackId()).thenReturn("default");
        lenient().when(platform.getServerId()).thenReturn(SELF_ID);
        lenient().when(platform.getDomain()).thenReturn(DOMAIN);
        device = device();
        // 不再打 deviceMapper.selectOne 桩: 报警订阅发起路径不查设备表（与会话无关）
    }

    // ---------------------------------------------------------------- fixtures

    /** 普通设备（type=1）: id 5 / 编码 DEV_ID / UDP / 127.0.0.1:5060 / 在线 */
    private static GbDeviceEntity device() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(5L);
        d.setDeviceId(DEV_ID);
        d.setDeviceType(1);
        d.setTransport("UDP");
        d.setIp("127.0.0.1");
        d.setPort(5060);
        d.setOnline(1);
        return d;
    }

    /** 经上线钩子发起一次订阅并捕获出站 SUBSCRIBE（每次测试仅此一次发送） */
    private SipMessageContext subscribeOnce() {
        svc.onDeviceOnline(device);
        ArgumentCaptor<SipMessageContext> cap = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(cap.capture());
        return cap.getValue();
    }

    /** 用 200 OK 确认订阅（toTag=peer1, 按 Call-ID 关联; 未带 Expires → 回退本端请求值 3600） */
    private void confirm(SipMessageContext ctx, Integer grantedExpires) {
        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE)
                .method("SUBSCRIBE")
                .statusCode(200)
                .callId(ctx.getCallId())
                .toTag("peer1")
                .expires(grantedExpires)
                .build());
    }

    /** 反射取内部订阅会话表（键=国标编码） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> subsMap() throws Exception {
        Field f = AlarmSubscriptionService.class.getDeclaredField("subs");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(svc);
    }

    /** 反射取指定设备的订阅会话对象 */
    private Object subOf(String deviceId) throws Exception {
        Object sub = subsMap().get(deviceId);
        assertNotNull(sub, "订阅会话应已建立: " + deviceId);
        return sub;
    }

    /** 反射写会话内部字段（sentAt 等时间轴字段） */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 反射读会话内部字段（nextRefreshAt 断言用） */
    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    // ---------------------------------------------------------------- 测试

    @Test
    @DisplayName("#1 type=1 设备上线 → SUBSCRIBE(Event: Alarm, Expires 3600, body=Query/Alarm)")
    void subscribeSentForOnlineDevice() {
        SipMessageContext ctx = subscribeOnce();

        assertEquals("SUBSCRIBE", ctx.getMethod());
        assertEquals(AlarmSubscriptionService.EVENT_ALARM, ctx.getEvent());
        assertEquals(3600, ctx.getExpires());
        assertTrue(ctx.getBody().contains("<CmdType>Alarm</CmdType>"));
        assertTrue(ctx.getFrom().startsWith(SELF_ID));
        assertEquals(DEV_ID + "@" + DOMAIN, ctx.getTo());
        assertEquals("127.0.0.1", ctx.getTargetHost());
        assertEquals(5060, ctx.getTargetPort());
    }

    @Test
    @DisplayName("#2 下级平台(type=2)上线同样订阅（报警订阅不限设备类型）")
    void subscribeSentForCascadeDeviceToo() {
        device.setDeviceType(2);

        subscribeOnce();

        verify(transport).send(any());
    }

    @Test
    @DisplayName("#3 确认以对端授予 Expires 为准: 授予 600s → 0.8×=480s 后续订")
    void confirmUsesGrantedExpires() throws Exception {
        SipMessageContext ctx = subscribeOnce();
        LocalDateTime before = LocalDateTime.now();
        confirm(ctx, 600);
        LocalDateTime after = LocalDateTime.now();

        LocalDateTime next = (LocalDateTime) readField(subOf(DEV_ID), "nextRefreshAt");
        // 容差断言: [before+470, after+490] 覆盖 480s±时间取值漂移
        assertTrue(next.isAfter(before.plusSeconds(470)));
        assertTrue(next.isBefore(after.plusSeconds(490)));
    }

    @Test
    @DisplayName("#4 无应答超 15s: tick 丢弃会话, 再 tick 重建新订阅（新 Call-ID）")
    void noResponseRebuild() throws Exception {
        SipMessageContext first = subscribeOnce();
        Object sub = subOf(DEV_ID);
        setField(sub, "sentAt", LocalDateTime.now().minusSeconds(20));   // 仍为未确认 → 判 stale
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));

        svc.tick();
        assertTrue(subsMap().isEmpty());

        svc.tick();

        ArgumentCaptor<SipMessageContext> cap = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(cap.capture());
        assertNotEquals(first.getCallId(), cap.getAllValues().get(1).getCallId());
    }

    @Test
    @DisplayName("#5 订阅被拒(500): 会话即弃, 下个 tick 换新 Call-ID 重建")
    void rejectDropsSession() throws Exception {
        SipMessageContext first = subscribeOnce();
        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE)
                .method("SUBSCRIBE")
                .statusCode(500)
                .callId(first.getCallId())
                .build());
        assertNull(subsMap().get(DEV_ID));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));

        svc.tick();

        ArgumentCaptor<SipMessageContext> cap = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(cap.capture());
        assertNotEquals(first.getCallId(), cap.getAllValues().get(1).getCallId());
    }

    @Test
    @DisplayName("#6 离线钩子: 清会话; tick 不为离线设备重发（selectList 只回在线设备）")
    void offlineHookClearsSession() throws Exception {
        SipMessageContext ctx = subscribeOnce();
        assertNotNull(ctx);
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        svc.onDeviceOffline(DEV_ID);
        svc.tick();

        verify(transport, times(1)).send(any());
    }

    @Test
    @DisplayName("#7 ONVIF 设备跳过 GB 报警订阅: tick 只为 GB 设备发 SUBSCRIBE(M4-B2)")
    void tickSkipsOnvifDevices() {
        GbDeviceEntity onvif = device();
        onvif.setId(6L);
        onvif.setDeviceId("34020000001320000006");
        onvif.setAccessProtocol("ONVIF");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device, onvif));

        svc.tick();

        // 仅 GB 设备一条订阅; ONVIF 相机侧无 GB 订阅语义, 不再循环重建
        verify(transport, times(1)).send(any());
    }
}
