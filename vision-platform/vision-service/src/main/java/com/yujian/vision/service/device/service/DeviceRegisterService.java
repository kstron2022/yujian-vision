package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.gb28181.auth.DigestAuthenticator;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipTransportProvider;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 注册处理（docs/05 §1）：401 挑战 → Digest 校验 → 设备自动建档 → 200 OK。
 * nonce 一次性使用，存 Redis（TTL 300s）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceRegisterService {

    private static final String NONCE_KEY_PREFIX = "gb:sip:nonce:";

    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final StringRedisTemplate redis;
    private final GbPlatformProperties platform;
    private final AccessConfigService accessConfigService;
    private final CatalogSyncService catalogSyncService;
    private final StreamEventPublisher eventPublisher;
    private final AesCipher aes;
    private final NoticeService noticeService;
    // M3-B3 阶段4: type=2 下级上线自动目录订阅(注册钩子)
    private final CatalogSubscriptionService catalogSubscriptionService;
    // M3-B4: 上线即发起报警订阅(全部设备, 失败不阻塞注册)
    private final AlarmSubscriptionService alarmSubscriptionService;

    /** TCP 传输设备的 SIP 监听端口(GB28181 默认 5060)。TCP 注册的 Contact 端口是连接临时端口不可用 */
    private static final int TCP_DEVICE_SIP_PORT = 5060;

    public void handle(SipEvent event) {
        String deviceId = event.getFromUser();
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("REGISTER 缺少 From 用户, 忽略");
            return;
        }
        // V18 白名单模式: 未预建档设备直接 403 拒绝(不挑战/不泄露鉴权信息), 记日志+限频站内通知;
        // 开放模式也要求 20 位数字编码(收口评审: 自动建档路径与预建档同标准, 防任意字符串建档)
        if (selectByDeviceId(deviceId) == null
                && (!accessConfigService.isAutoRegisterAllowed() || !deviceId.matches("\\d{20}"))) {
            rejectUnregistered(event, deviceId);
            return;
        }
        // 1. 无鉴权头 → 401 挑战
        if (event.getAuthorization() == null) {
            challenge(event, false);
            return;
        }
        // 2. 有鉴权头 → nonce 有效性 + 摘要校验
        Map<String, String> params = DigestAuthenticator.parseParams(event.getAuthorization());
        String nonce = params.get("nonce");
        Boolean nonceValid = redis.hasKey(NONCE_KEY_PREFIX + nonce);
        String password = resolvePassword(deviceId);
        boolean pass = Boolean.TRUE.equals(nonceValid)
                && DigestAuthenticator.verify(event.getAuthorization(), "REGISTER", password);
        if (!pass) {
            // nonce 无效 = 已被消费(并发注册共用 nonce)/过期/重启丢失, 属正常重挑战;
            // 摘要不匹配 = 密码错误, 真正的鉴权失败, 需告警
            if (Boolean.TRUE.equals(nonceValid)) {
                log.warn("注册摘要校验失败(请核对设备注册密码): device={}", deviceId);
            } else {
                log.debug("注册 nonce 无效, 重新下发挑战: device={}", deviceId);
            }
            challenge(event, Boolean.TRUE.equals(nonceValid));
            return;
        }
        redis.delete(NONCE_KEY_PREFIX + nonce);
        // 3. 注册成功处理
        int expires = event.getExpires() != null ? event.getExpires() : 3600;
        if (expires == 0) {
            unregister(deviceId);
            respond(event, 200, Map.of(
                    "Date", rfc1123(),
                    "Expires", "0"));
            log.info("设备注销: {}", deviceId);
            return;
        }
        upsertDevice(event, deviceId, expires, password);
        respond(event, 200, Map.of("Date", rfc1123(), "Expires", String.valueOf(expires)));
        log.info("设备注册成功: {} expires={}s", deviceId, expires);
    }

    // ---------------------------------------------------------------- 内部

    /** 国标编码类型段(11~13位)→设备类型: 200=下级平台, 其余=设备（GB/T 28181 附录D, 自动建档判型用） */
    static int deriveDeviceType(String deviceId) {
        return deviceId != null && deviceId.length() >= 13 && "200".equals(deviceId.substring(10, 13)) ? 2 : 1;
    }

    /** 白名单拒绝（V18）: 403 + 日志; 站内通知 30 分钟限频防刷屏（Redis setIfAbsent, B4 告警同款） */
    private void rejectUnregistered(SipEvent event, String deviceId) {
        log.warn("白名单模式拒绝未建档设备注册: {}", deviceId);
        respond(event, 403, Map.of());
        String key = "gb:register:rejected:" + deviceId;
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, "1", java.time.Duration.ofMinutes(30));
            if (Boolean.TRUE.equals(first)) {
                noticeService.publish("REGISTER_REJECTED", "注册被拒绝",
                        "白名单模式下拒绝未建档设备注册: " + deviceId
                                + "（如需接入请先在设备管理预建档）", 2, deviceId);
            }
        } catch (Exception e) {
            log.debug("注册拒绝通知限频失败(忽略): {}", e.getMessage());
        }
    }

    private void challenge(SipEvent event, boolean stale) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(NONCE_KEY_PREFIX + nonce, "1", java.time.Duration.ofSeconds(300));
        respond(event, 401, Map.of("WWW-Authenticate", DigestAuthenticator.createChallenge(platform.getRealm(), nonce)));
    }

    private void respond(SipEvent event, int status, Map<String, String> headers) {
        try {
            transport.sendResponse(event, status, headers);
        } catch (Exception e) {
            log.error("注册应答失败: {}", e.getMessage());
        }
    }

    /** Digest 校验用的明文密码：库内为 AES 密文则解密（存量明文兼容读取, docs/02 §0） */
    private String resolvePassword(String deviceId) {
        GbDeviceEntity device = selectByDeviceId(deviceId);
        if (device != null && device.getPassword() != null && !device.getPassword().isBlank()) {
            return aes.decrypt(device.getPassword());
        }
        // 未建档设备(自动建档)用接入配置的默认密码校验（接入配置自定义 > 内置默认, docs/10）
        return accessConfigService.resolveDefaultPassword();
    }

    private void upsertDevice(SipEvent event, String deviceId, int expires, String password) {
        LocalDateTime now = LocalDateTime.now();
        GbDeviceEntity device = selectByDeviceId(deviceId);
        boolean isNew = false;
        boolean wasOffline = false;
        if (device == null) {
            isNew = true;
            device = new GbDeviceEntity();
            device.setDeviceId(deviceId);
            device.setName(event.getContactUser() != null ? event.getContactUser() : deviceId);
            // 自动建档按国标编码类型段(11~13位, GB/T 28181 附录D)判类型: 200=下级平台
            device.setDeviceType(deriveDeviceType(deviceId));
            device.setProtocolVersion("2016");
            device.setTransport(event.getTransport() != null ? event.getTransport() : "UDP");
            device.setPassword(aes.encrypt(password));
            device.setExpires(3600);
            device.setHeartbeatInterval(60);
            device.setHeartbeatTimeout(3);
            device.setMediaTcpMode(1);
            device.setRegisterTime(now);
        } else {
            wasOffline = device.getOnline() == null || device.getOnline() != 1;
        }
        if (event.getContactHost() != null) {
            device.setIp(event.getContactHost());
            if ("TCP".equals(device.getTransport())) {
                // 海康等固件 TCP 注册时 Contact 端口是 TCP 连接的临时源端口(连接断开即失效),
                // 不可作为外呼目标; 设备在其本地 SIP 监听端口(GB28181 默认 5060)收 TCP 信令
                device.setPort(TCP_DEVICE_SIP_PORT);
            } else {
                // UDP: Contact 端口缺失时回退源端口(NAT received/rport 语义)
                device.setPort(event.getContactPort() != null ? event.getContactPort() : 5060);
            }
        }
        device.setTransport(event.getTransport() != null ? event.getTransport() : "UDP");
        device.setOnline(1);
        device.setKeepaliveTime(now);
        device.setRegisterTime(now);
        device.setExpires(expires);
        if (device.getId() == null) {
            deviceMapper.insert(device);
        } else {
            deviceMapper.updateById(device);
        }
        if (isNew) {
            log.info("设备自动建档: {} ({}:{})", deviceId, device.getIp(), device.getPort());
        }
        // A5: 新建档/离线→在线 转变时推送事件并异步触发目录同步（在线期 re-REGISTER 不触发）
        if (isNew || wasOffline) {
            eventPublisher.deviceOnline(deviceId, device.getName());
            asyncCatalogSync(deviceId);
            // M3-B3 阶段4: type=2 下级上线即自动目录订阅(失败只记日志, 不阻塞注册)
            catalogSubscriptionService.onDeviceOnline(device);
            // M3-B4: 上线即发起报警订阅(全部设备, 失败只记日志不阻塞注册)
            alarmSubscriptionService.onDeviceOnline(device);
        }
    }

    /** 目录同步异步触发：单线程守护执行器, 失败只记日志（2001 冲突静默跳过） */
    private void asyncCatalogSync(String deviceId) {
        SYNC_EXECUTOR.execute(() -> {
            try {
                catalogSyncService.sync(deviceId);
            } catch (BizException e) {
                log.debug("自动目录同步跳过: device={} reason={}", deviceId, e.getMessage());
            } catch (Exception e) {
                log.warn("自动目录同步失败: device={}", deviceId, e);
            }
        });
    }

    private static final java.util.concurrent.ExecutorService SYNC_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "catalog-auto");
                t.setDaemon(true);
                return t;
            });

    private void unregister(String deviceId) {
        GbDeviceEntity device = selectByDeviceId(deviceId);
        if (device == null) {
            return;
        }
        GbDeviceEntity update = new GbDeviceEntity();
        update.setId(device.getId());
        update.setOnline(0);
        update.setOfflineTime(LocalDateTime.now());
        deviceMapper.updateById(update);
        eventPublisher.deviceOffline(deviceId, device.getName());
        // 消息中心 B1-c: 注销也是离线, 同步发站内通知（publish 内部吞异常, 不影响注册主流程）
        noticeService.publish("DEVICE_OFFLINE", "设备离线",
                NoticeService.deviceOfflineContent(deviceId, device.getName()), 1, deviceId);
        catalogSubscriptionService.onDeviceOffline(deviceId);
        // M3-B4: 离线/注销同步清报警订阅(重新上线由注册钩子/tick 重建)
        alarmSubscriptionService.onDeviceOffline(deviceId);
    }

    private String rfc1123() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                        .withZone(ZoneId.of("GMT")));
    }

    private GbDeviceEntity selectByDeviceId(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }
}
