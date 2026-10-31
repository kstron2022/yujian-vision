package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.service.system.service.NoticeService;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * DeviceRegisterService 单元测试（纯 Mockito, 不起 Spring 容器）。
 *
 * <p>测试范围（M3-B3）：
 * <ul>
 *   <li>V18 接入模式白名单/开放分支：未建档设备在白名单模式下 403 直接拒绝
 *       （不下发 401 挑战、不泄露鉴权信息）并发限频站内通知（30 分钟 Redis setIfAbsent）；
 *       开放模式与已建档设备走 401 Digest 挑战；</li>
 *   <li>国标编码类型段判型 {@code deriveDeviceType}：11~13 位类型段 200=下级平台(2), 其余=设备(1)。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceRegisterServiceTest {

    @Mock
    private SipTransportProvider transport;
    @Mock
    private GbDeviceMapper deviceMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private AccessConfigService accessConfigService;
    @Mock
    private CatalogSyncService catalogSyncService;
    @Mock
    private StreamEventPublisher eventPublisher;
    @Mock
    private AesCipher aes;
    @Mock
    private NoticeService noticeService;
    @Mock
    private CatalogSubscriptionService catalogSubscriptionService;
    @Mock
    private AlarmSubscriptionService alarmSubscriptionService;
    @Mock
    private ValueOperations<String, String> valueOps;

    private GbPlatformProperties platform;
    private DeviceRegisterService service;

    @BeforeEach
    void setUp() {
        platform = new GbPlatformProperties();
        // 构造器参数顺序 = 被测类字段声明顺序：
        // transport/deviceMapper/redis/platform/accessConfigService/catalogSyncService/eventPublisher/aes/noticeService/catalogSubscriptionService
        service = new DeviceRegisterService(transport, deviceMapper, redis, platform,
                accessConfigService, catalogSyncService, eventPublisher, aes, noticeService,
                catalogSubscriptionService, alarmSubscriptionService);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("deriveDeviceType: 类型段 200 判为下级平台(2), 其余/短码/null 判为设备(1)")
    void deriveDeviceTypeMapsTypeSegment() {
        // 第 11~13 位（下标 10~12）= "200" → 下级平台
        assertEquals(2, DeviceRegisterService.deriveDeviceType("34020000002000000009"));
        // 类型段 "132" ≠ "200" → 设备
        assertEquals(1, DeviceRegisterService.deriveDeviceType("34020000001320000009"));
        // 长度不足 13 位 → 设备
        assertEquals(1, DeviceRegisterService.deriveDeviceType("short"));
        // null → 设备
        assertEquals(1, DeviceRegisterService.deriveDeviceType(null));
    }

    @Test
    @DisplayName("白名单模式(V18): 未建档设备 REGISTER 直接 403 拒绝并发限频站内通知, 不下发 401 挑战")
    void whitelistModeRejectsUnregisteredDeviceWith403() {
        SipEvent event = SipEvent.builder().fromUser("34020000002000000009").build();
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(accessConfigService.isAutoRegisterAllowed()).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.handle(event);

        // 403 拒绝（无附加头）
        verify(transport).sendResponse(event, 403, Map.of());
        // 站内通知内容含设备编码, 便于定位被拒设备
        verify(noticeService).publish(anyString(), anyString(),
                contains("34020000002000000009"), anyInt(), anyString());
        // 白名单拒绝不泄露鉴权信息: 不下发 401 挑战
        verify(transport, never()).sendResponse(eq(event), eq(401), anyMap());
    }

    @Test
    @DisplayName("开放模式: 未建档设备无鉴权头 → 401 Digest 挑战, 不走 403 拒绝")
    void openModeChallengesUnknownDeviceWith401() {
        SipEvent event = SipEvent.builder().fromUser("34020000002000000009").build();
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(accessConfigService.isAutoRegisterAllowed()).thenReturn(true);

        service.handle(event);

        // 401 挑战（WWW-Authenticate 头由 DigestAuthenticator 生成, 这里只验证状态码与有头）
        verify(transport).sendResponse(eq(event), eq(401), anyMap());
        // nonce 写入 Redis, TTL 300s（一次性使用）
        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
        // 开放模式不拒绝
        verify(transport, never()).sendResponse(eq(event), eq(403), anyMap());
        verify(noticeService, never()).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("开放模式: 非法编码(bad-id, 非 20 位数字)未建档 → 403 拒绝, 不建档不挑战")
    void openModeRejectsMalformedDeviceId() {
        SipEvent event = SipEvent.builder().fromUser("bad-id").build();
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(accessConfigService.isAutoRegisterAllowed()).thenReturn(true);

        service.handle(event);

        // 编码不合法视同未预建档: 开放模式同样 403（自动建档与预建档同标准）
        verify(transport).sendResponse(event, 403, Map.of());
        verify(deviceMapper, never()).insert(any(GbDeviceEntity.class));
        verify(transport, never()).sendResponse(eq(event), eq(401), anyMap());
        verify(noticeService, never()).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("已建档设备无鉴权头 → 401 挑战, 跳过白名单判断")
    void registeredDeviceWithoutAuthorizationGets401Challenge() {
        SipEvent event = SipEvent.builder().fromUser("34020000001320000010").build();
        GbDeviceEntity device = new GbDeviceEntity();
        device.setDeviceId("34020000001320000010");
        when(deviceMapper.selectOne(any())).thenReturn(device);

        service.handle(event);

        verify(transport).sendResponse(eq(event), eq(401), anyMap());
        // 已建档设备不触发白名单判断与拒绝通知
        verify(accessConfigService, never()).isAutoRegisterAllowed();
        verify(transport, never()).sendResponse(eq(event), eq(403), anyMap());
        verify(noticeService, never()).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("白名单拒绝通知 30 分钟限频: setIfAbsent 返回 false 时不发站内通知")
    void rejectNoticeSuppressedWhenRateLimited() {
        SipEvent event = SipEvent.builder().fromUser("34020000002000000009").build();
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(accessConfigService.isAutoRegisterAllowed()).thenReturn(false);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        service.handle(event);

        // 403 仍正常应答, 仅通知被限频吞掉
        verify(transport).sendResponse(event, 403, Map.of());
        verify(noticeService, never()).publish(anyString(), anyString(), anyString(), anyInt(), anyString());
    }
}
