package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
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
 * 目录订阅状态机与增量入库单测（docs/08 §3.4, M3-B3 阶段4）：订阅发起/确认/到期续订/无应答重建
 * + NOTIFY 增量（2022 Event on/off/add/del/update 大小写不敏感 + 2016 无 Event 整目录 upsert,
 * 永不「缺失即软删」）。纯 Mockito, 不起 Spring。
 * 收口评审补充：NOTIFY 来源校验（须已确认订阅对话 + From 与报文 DeviceID 交叉一致）、
 * 单包条目上限（>1000 拒绝）、update 无 Status 保留通道现值、授予 Expires 定续订节奏。
 */
@ExtendWith(MockitoExtension.class)
class CatalogSubscriptionServiceTest {

    /** 下级平台编码（fixture 设备 deviceId, 亦为 subs 表键） */
    private static final String DEV_ID = "34020000002000000002";
    /** 平台侧编码（GbPlatformProperties.serverId stub 值, from 头前缀断言用） */
    private static final String SELF_ID = "34020000002000000001";
    private static final String DOMAIN = "3402000000";

    @Mock
    private SipTransportProvider transport;
    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private GbDeviceChannelMapper channelMapper;
    @Mock
    private GbPlatformProperties platform;
    @Mock
    private StreamEventPublisher eventPublisher;

    private CatalogSubscriptionService svc;
    private GbDeviceEntity device;

    @BeforeAll
    static void initMpMetadata() {
        // LambdaQueryWrapper 列名解析需要实体元数据（mock mapper 不触达 DB, 仅为 wrapper 构建铺路）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceChannelEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        // codec 用真实实例（build/parse 为纯函数, mock 无意义）; HA 桩活跃身份放行 tick 门禁(M3-B6 阶段2)
        com.yujian.vision.service.ha.HaRuntimeService ha =
                org.mockito.Mockito.mock(com.yujian.vision.service.ha.HaRuntimeService.class);
        org.mockito.Mockito.lenient().when(ha.sipGatesOpen()).thenReturn(true);
        svc = new CatalogSubscriptionService(transport, deviceMapper, channelMapper, platform,
                new ManScdpCodec(), eventPublisher, ha);
        ReflectionTestUtils.setField(svc, "subscribeExpires", 3600);
        // 三个 platform stub 各用例覆盖面不一, 用 lenient 避免严格模式误报未用桩
        lenient().when(platform.getStackId()).thenReturn("default");
        lenient().when(platform.getServerId()).thenReturn(SELF_ID);
        lenient().when(platform.getDomain()).thenReturn(DOMAIN);
        device = cascadeDevice();
        lenient().when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    /** 下级平台档案（type=2）: id 5 / 编码 DEV_ID / UDP / 127.0.0.1:5063 / 在线 */
    private static GbDeviceEntity cascadeDevice() {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(5L);
        d.setDeviceId(DEV_ID);
        d.setDeviceType(2);
        d.setTransport("UDP");
        d.setIp("127.0.0.1");
        d.setPort(5063);
        d.setOnline(1);
        d.setHeartbeatInterval(60);
        d.setHeartbeatTimeout(3);
        return d;
    }

    // ---------------------------------------------------------------- 订阅状态机（#1~#5, #10）

    @Test
    @DisplayName("#1 本域设备(type=1)上线不触发订阅")
    void onlineHookSkipsLocalDevice() {
        device.setDeviceType(1);

        svc.onDeviceOnline(device);

        verify(transport, never()).send(any());
    }

    @Test
    @DisplayName("#2 下级平台(type=2)上线 → SUBSCRIBE(Event: Catalog, Expires 3600, from=平台编码)")
    void onlineHookSendsSubscribe() {
        SipMessageContext ctx = subscribeOnce();

        assertEquals("SUBSCRIBE", ctx.getMethod());
        assertEquals(CatalogSubscriptionService.EVENT_CATALOG, ctx.getEvent());
        assertEquals(3600, ctx.getExpires());
        assertTrue(ctx.getFrom().startsWith(SELF_ID));
        assertEquals(DEV_ID + "@" + DOMAIN, ctx.getTo());
        assertEquals("127.0.0.1", ctx.getTargetHost());
        assertEquals(5063, ctx.getTargetPort());
    }

    @Test
    @DisplayName("#3 确认后未到期: tick 不发续订（全程仅订阅 1 次发送）")
    void noRefreshBeforeDue() {
        SipMessageContext ctx = subscribeOnce();
        confirm(ctx);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));

        svc.tick();

        verify(transport, times(1)).send(any());
    }

    @Test
    @DisplayName("#4 到期续订: 同对话 re-SUBSCRIBE（Call-ID/fromTag/toTag 不变, SN 递增）")
    void refreshWhenDue() throws Exception {
        SipMessageContext first = subscribeOnce();
        confirm(first);
        // 反射推进时间: 确认会话已到期 + 上次发送已过应答窗口
        Object sub = subOf(DEV_ID);
        setField(sub, "nextRefreshAt", LocalDateTime.now().minusSeconds(1));
        setField(sub, "sentAt", LocalDateTime.now().minusSeconds(20));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));

        svc.tick();

        ArgumentCaptor<SipMessageContext> cap = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport, times(2)).send(cap.capture());
        SipMessageContext refresh = cap.getAllValues().get(1);
        assertEquals(first.getCallId(), refresh.getCallId());
        assertEquals("peer1", refresh.getToTag());
        assertEquals(first.getFromTag(), refresh.getFromTag());
        assertNotEquals(first.getSn(), refresh.getSn());
    }

    @Test
    @DisplayName("#5 无应答超 15s: tick 丢弃会话, 再 tick 重建新订阅（新 Call-ID）")
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
    @DisplayName("#10 订阅被拒(500): 会话即弃, 下个 tick 换新 Call-ID 重建")
    void rejectRemovesSession() throws Exception {
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

    // ---------------------------------------------------------------- NOTIFY 增量入库（#6~#9）

    @Test
    @DisplayName("#6 2022 Event=add: 通道不存在 → 插入并广播 channelChange")
    void notifyAddUpserts() {
        SipMessageContext ctx = confirmedSubscription();
        when(channelMapper.selectOne(any())).thenReturn(null);

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("add", "34020000001320000016", "n1", "ON"));

        ArgumentCaptor<GbDeviceChannelEntity> cap = ArgumentCaptor.forClass(GbDeviceChannelEntity.class);
        verify(channelMapper).insert(cap.capture());
        assertEquals("34020000001320000016", cap.getValue().getChannelId());
        assertEquals("n1", cap.getValue().getName());
        assertEquals(1, cap.getValue().getStatus());
        assertEquals(5L, cap.getValue().getDeviceDbId());
        verify(eventPublisher).channelChange(DEV_ID);
    }

    @Test
    @DisplayName("#7 2022 Event=del: 软删（deletedTime 非空）, 永不触达全量列表（缺失≠删除）")
    void notifyDelSoftDeletes() {
        SipMessageContext ctx = confirmedSubscription();
        GbDeviceChannelEntity existing = existingChannel();
        when(channelMapper.selectOne(any())).thenReturn(existing);

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("del", "34020000001320000011", null, null));

        ArgumentCaptor<GbDeviceChannelEntity> cap = ArgumentCaptor.forClass(GbDeviceChannelEntity.class);
        verify(channelMapper).updateById(cap.capture());
        assertEquals(9L, cap.getValue().getId());
        assertNotNull(cap.getValue().getDeletedTime());
        verify(channelMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("#8 2022 Event=on/off: 通道在→更新状态 1/0; 通道缺→不落库（等全量补齐）")
    void notifyOnOffStatus() {
        SipMessageContext ctx = confirmedSubscription();
        GbDeviceChannelEntity onTarget = existingChannel();
        GbDeviceChannelEntity offTarget = existingChannel();
        when(channelMapper.selectOne(any())).thenReturn(onTarget);
        ArgumentCaptor<GbDeviceChannelEntity> cap = ArgumentCaptor.forClass(GbDeviceChannelEntity.class);

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("on", "34020000001320000011", null, null));
        verify(channelMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus());

        when(channelMapper.selectOne(any())).thenReturn(offTarget);
        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("off", "34020000001320000011", null, null));
        verify(channelMapper, times(2)).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());

        // 通道缺失: 纯状态事件不建档不更新
        when(channelMapper.selectOne(any())).thenReturn(null);
        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("on", "34020000001320000011", null, null));
        verify(channelMapper, times(2)).updateById(any(GbDeviceChannelEntity.class));
    }

    @Test
    @DisplayName("#9 2016 无 Event 整目录: 逐条 upsert(缺失即插入), 永不 selectList 软删")
    void notify2016NoEventUpsertOnly() {
        SipMessageContext ctx = confirmedSubscription();
        when(channelMapper.selectOne(any())).thenReturn(null);

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify(null, "34020000001320000016", "full-cam", "OFF"));

        ArgumentCaptor<GbDeviceChannelEntity> cap = ArgumentCaptor.forClass(GbDeviceChannelEntity.class);
        verify(channelMapper).insert(cap.capture());
        assertEquals("34020000001320000016", cap.getValue().getChannelId());
        assertEquals("full-cam", cap.getValue().getName());
        assertEquals(0, cap.getValue().getStatus());
        verify(channelMapper, never()).selectList(any());
    }

    // ---------------------------------------------------------------- 收口评审: 来源校验/写放大/状态保留

    @Test
    @DisplayName("#11 无订阅对话(随机 Call-ID)的 NOTIFY → 来源校验拒绝, 不落库")
    void notifyRejectedWithoutDialog() {
        confirmedSubscription();

        svc.onNotify(notifyEvent("random-call-id", DEV_ID),
                catalogNotify("add", "34020000001320000016", "n1", "ON"));

        verify(channelMapper, never()).insert(any(GbDeviceChannelEntity.class));
        verify(channelMapper, never()).updateById(any(GbDeviceChannelEntity.class));
        verify(eventPublisher, never()).channelChange(any());
    }

    @Test
    @DisplayName("#12 对话有效但 From 与报文 DeviceID 不一致 → 丢弃")
    void notifyRejectedFromMismatch() {
        SipMessageContext ctx = confirmedSubscription();

        svc.onNotify(notifyEvent(ctx.getCallId(), "99990000002000000002"),
                catalogNotify("add", "34020000001320000016", "n1", "ON"));

        verify(channelMapper, never()).insert(any(GbDeviceChannelEntity.class));
        verify(channelMapper, never()).updateById(any(GbDeviceChannelEntity.class));
        verify(eventPublisher, never()).channelChange(any());
    }

    @Test
    @DisplayName("#13 单包超 1000 条 → 整包拒绝(防写放大, 大批量应走全量同步)")
    void notifyOversizedPacketRejected() {
        SipMessageContext ctx = confirmedSubscription();
        List<Map<String, String>> items = new java.util.ArrayList<>();
        for (int i = 0; i <= 1000; i++) {
            items.add(Map.of("DeviceID", "3402000000132000" + String.format("%04d", i), "Event", "add"));
        }
        ManScdpMessage oversized = ManScdpMessage.builder()
                .rootType("Notify")
                .cmdType("Catalog")
                .sn(9L)
                .deviceId(DEV_ID)
                .sumNum(items.size())
                .items(items)
                .build();

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID), oversized);

        verify(channelMapper, never()).insert(any(GbDeviceChannelEntity.class));
        verify(channelMapper, never()).updateById(any(GbDeviceChannelEntity.class));
        verify(eventPublisher, never()).channelChange(any());
    }

    @Test
    @DisplayName("#14 Event=update 无 Status 字段 → 保留通道现值(不强置 OFF)")
    void updateWithoutStatusKeepsPrevious() {
        SipMessageContext ctx = confirmedSubscription();
        GbDeviceChannelEntity existing = existingChannel();
        existing.setStatus(1);
        when(channelMapper.selectOne(any())).thenReturn(existing);

        svc.onNotify(notifyEvent(ctx.getCallId(), DEV_ID),
                catalogNotify("update", "34020000001320000011", null, null));

        ArgumentCaptor<GbDeviceChannelEntity> cap = ArgumentCaptor.forClass(GbDeviceChannelEntity.class);
        verify(channelMapper).updateById(cap.capture());
        // 报文未带 Status: 入库实体保留原状态 1, 不被 applyItemFields 缺省 OFF 覆盖
        assertEquals(1, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("#15 确认以对端授予 Expires 为准: 授予 600s → 0.8×=480s 后续订")
    void confirmUsesGrantedExpires() throws Exception {
        SipMessageContext ctx = subscribeOnce();
        LocalDateTime before = LocalDateTime.now();
        confirmWithExpires(ctx, 600);
        LocalDateTime after = LocalDateTime.now();

        LocalDateTime next = (LocalDateTime) readField(subOf(DEV_ID), "nextRefreshAt");
        // 容差断言: [before+470, after+490] 覆盖 480s±时间取值漂移
        assertTrue(next.isAfter(before.plusSeconds(470)));
        assertTrue(next.isBefore(after.plusSeconds(490)));
    }

    // ---------------------------------------------------------------- 测试夹具与工具

    /** 经上线钩子发起一次订阅并捕获出站 SUBSCRIBE（每次测试仅此一次发送） */
    private SipMessageContext subscribeOnce() {
        svc.onDeviceOnline(device);
        ArgumentCaptor<SipMessageContext> cap = ArgumentCaptor.forClass(SipMessageContext.class);
        verify(transport).send(cap.capture());
        return cap.getValue();
    }

    /** 用 200 OK 确认订阅（toTag=peer1, 按 Call-ID 关联; 未带 Expires → 回退本端请求值 3600） */
    private void confirm(SipMessageContext ctx) {
        confirmWithExpires(ctx, 3600);
    }

    /** 建立已确认订阅对话: 上线钩子发起 → 200 OK 确认（NOTIFY 来源校验的前置条件） */
    private SipMessageContext confirmedSubscription() {
        SipMessageContext ctx = subscribeOnce();
        confirm(ctx);
        return ctx;
    }

    /** 用带授予有效期的 200 OK 确认订阅（续订节奏取 granted×0.8） */
    private void confirmWithExpires(SipMessageContext ctx, Integer grantedExpires) {
        svc.onSipResponse(SipEvent.builder()
                .type(SipEvent.Type.RESPONSE)
                .method("SUBSCRIBE")
                .statusCode(200)
                .callId(ctx.getCallId())
                .toTag("peer1")
                .expires(grantedExpires)
                .build());
    }

    /** NOTIFY 入站事件（callId 关联订阅对话, fromUser=对端国标编码, 与报文 DeviceID 交叉校验用） */
    private static SipEvent notifyEvent(String callId, String fromUser) {
        return SipEvent.builder()
                .type(SipEvent.Type.REQUEST)
                .method("NOTIFY")
                .callId(callId)
                .fromUser(fromUser)
                .build();
    }

    /** Notify/Catalog 报文解析结果: 单 Item, Event 属性可空（null=2016 整目录形态） */
    private static ManScdpMessage catalogNotify(String event, String channelId, String name, String status) {
        String item = "<Item" + (event == null ? "" : " Event=\"" + event + "\"") + ">\r\n"
                + "<DeviceID>" + channelId + "</DeviceID>\r\n"
                + (name == null ? "" : "<Name>" + name + "</Name>\r\n")
                + (status == null ? "" : "<Status>" + status + "</Status>\r\n")
                + "</Item>\r\n";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>9</SN>\r\n"
                + "<DeviceID>" + DEV_ID + "</DeviceID>\r\n"
                + "<SumNum>1</SumNum>\r\n"
                + "<DeviceList Num=\"1\">\r\n"
                + item
                + "</DeviceList>\r\n"
                + "</Notify>\r\n";
        return new ManScdpCodec().parse(xml);
    }

    /** 既有通道（id=9, 挂在设备 5 下） */
    private static GbDeviceChannelEntity existingChannel() {
        GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
        ch.setId(9L);
        ch.setDeviceDbId(5L);
        ch.setChannelId("34020000001320000011");
        ch.setName("old-cam");
        ch.setStatus(0);
        return ch;
    }

    /** 反射取内部订阅会话表（键=国标编码） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> subsMap() throws Exception {
        Field f = CatalogSubscriptionService.class.getDeclaredField("subs");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(svc);
    }

    /** 反射取指定设备的订阅会话对象 */
    private Object subOf(String deviceId) throws Exception {
        Object sub = subsMap().get(deviceId);
        assertNotNull(sub, "订阅会话应已建立: " + deviceId);
        return sub;
    }

    /** 反射写会话内部字段（nextRefreshAt/sentAt 等时间轴字段） */
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
}
