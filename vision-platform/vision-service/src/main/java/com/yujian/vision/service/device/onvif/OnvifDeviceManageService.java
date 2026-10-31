package com.yujian.vision.service.device.onvif;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.onvif.OnvifClient;
import com.yujian.vision.onvif.model.OnvifCallException;
import com.yujian.vision.onvif.model.OnvifDeviceInfo;
import com.yujian.vision.onvif.model.OnvifProfile;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ONVIF 设备管理（docs/13 §6.1）：测试连接/手填添加/参数修改。
 * 目录刷新与删除复用既有入口（catalog/refresh 按协议分流; 删除走 DeviceManageService）。
 * 添加即探活+目录同步, ONVIF 调用失败回滚新建档案（不留半成品设备）。
 * 安全加固（B1 评审）: update 与 add 同口径校验+同地址唯一+协议守卫, 防 update 绕过校验
 * 形成存储型请求改写; 回环/链路本地/组播网段拒绝; 设备侧错误文本经 OnvifErrorText 清洗。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnvifDeviceManageService {

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final AesCipher aes;
    private final OnvifCodeAllocator codeAllocator;
    private final OnvifAccessProvider onvifProvider;

    private final OnvifClient client = new OnvifClient();

    // ---- 请求/视图 DTO（docs/13 §6.1; 密码只进不出） ----

    /** deviceId \u53ef\u9009: \u7f16\u8f91\u62bd\u5c49\u573a\u666f\u5bc6\u7801\u7559\u7a7a\u65f6\u7528\u5df2\u5b58\u8d26\u5bc6\u6d4b\u8bd5\u8fde\u63a5(M4-B2) */
    public record TestConnectionRequest(String deviceId, String ip, Integer port, String username,
            String password) {
    }

    public record ProfileView(String token, String name, int width, int height, String encoding, boolean ptz) {
    }

    public record SourceView(String token, List<ProfileView> profiles) {
    }

    public record TestConnectionView(String manufacturer, String model, String firmware, String serialNumber,
            List<SourceView> sources) {
    }

    public record AddRequest(String ip, Integer port, String username, String password, String name,
            Integer probeInterval, Integer probeTimeout) {
    }

    public record UpdateRequest(String name, String ip, Integer port, String username, String password,
            Integer probeInterval, Integer probeTimeout) {
    }

    /** 测试连接：GetDeviceInformation + 目录拉取（添加抽屉一次拿全: 设备信息+通道预览） */
    public TestConnectionView testConnection(TestConnectionRequest req) {
        validateIp(req.ip());
        validatePort(req.port());
        // \u7f16\u8f91\u62bd\u5c49(M4-B2): \u5bc6\u7801\u7559\u7a7a\u65f6\u53d6\u5df2\u5b58\u5bc6\u7801\u6d4b\u8bd5\u8fde\u63a5, \u65b0\u5bc6\u7801\u9700\u5148\u4fdd\u5b58\u540e\u751f\u6548\u53e3\u5f84\u4e0d\u53d8
        String username = req.username();
        String password = req.password();
        if ((password == null || password.isBlank()) && StringUtils.hasText(req.deviceId())) {
            GbDeviceEntity existing = deviceMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GbDeviceEntity>()
                            .eq(GbDeviceEntity::getDeviceId, req.deviceId())
                            .isNull(GbDeviceEntity::getDeletedTime)
                            .last("LIMIT 1"));
            if (existing == null || !"ONVIF".equalsIgnoreCase(existing.getAccessProtocol())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "\u8bbe\u5907\u4e0d\u5b58\u5728\u6216\u975e ONVIF \u8bbe\u5907: " + req.deviceId());
            }
            if (!StringUtils.hasText(username)) {
                username = existing.getOnvifUsername();
            }
            password = existing.getOnvifPassword() == null || existing.getOnvifPassword().isBlank()
                    ? "" : aes.decrypt(existing.getOnvifPassword());
        }
        validateCredential(username, password);
        try {
            OnvifDeviceInfo info = client.deviceInfo(req.ip(), portOf(req.port()),
                    username, password);
            var bySource = client.catalog(req.ip(), portOf(req.port()), username, password);
            List<SourceView> sources = new ArrayList<>();
            bySource.forEach((token, profiles) -> sources.add(new SourceView(token,
                    sortedProfiles(profiles).stream()
                            .map(p -> new ProfileView(p.token(), p.name(), p.width(), p.height(),
                                    p.encoding(), p.ptz()))
                            .toList())));
            return new TestConnectionView(info.manufacturer(), info.model(), info.firmware(),
                    info.serialNumber(), sources);
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "ONVIF 连接失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /** 手填添加：建档 → 目录同步（含探活上线）；ONVIF 调用失败回滚新档（不留半成品设备） */
    public GbDeviceEntity add(AddRequest req) {
        validateIp(req.ip());
        validatePort(req.port());
        validateCredential(req.username(), req.password());
        validateProbeCycle(req.probeInterval(), req.probeTimeout());
        if (StringUtils.hasText(req.name()) && req.name().trim().length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备名称过长（≤128）");
        }
        if (existsAt(req.ip(), portOf(req.port()), null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该设备地址已存在 ONVIF 档案: " + req.ip());
        }
        GbDeviceEntity device = new GbDeviceEntity();
        device.setDeviceId(codeAllocator.nextDeviceCode());
        device.setName(StringUtils.hasText(req.name()) ? req.name().trim() : req.ip());
        device.setDeviceType(1);
        device.setAccessProtocol(DeviceProtocolConstants.ONVIF);
        device.setOnvifUsername(req.username());
        device.setOnvifPassword(aes.encrypt(req.password()));
        device.setIp(req.ip());
        device.setPort(portOf(req.port()));
        device.setHeartbeatInterval(req.probeInterval() != null ? req.probeInterval()
                : OnvifDeviceDefaults.PROBE_INTERVAL);
        device.setHeartbeatTimeout(req.probeTimeout() != null ? req.probeTimeout()
                : OnvifDeviceDefaults.PROBE_TIMEOUT);
        device.setOnline(0);
        device.setRegisterTime(LocalDateTime.now());
        try {
            deviceMapper.insert(device);
            onvifProvider.syncCatalog(device);
        } catch (RuntimeException e) {
            rollback(device.getId());
            throw e;
        }
        log.info("ONVIF 设备已添加: name={} ip={} port={} code={}",
                device.getName(), device.getIp(), device.getPort(), device.getDeviceId());
        return device;
    }

    /**
     * 参数修改（patch 语义; 密码/用户名留空=不修改）。与 add 同口径校验（评审: update 曾绕过
     * validate/existsAt, 任意 IP 字符串落库会改写探活请求目标; 且须协议守卫防误改 GB 行）。
     * 地址/账密变更后建议手动刷新目录。
     */
    public void update(String deviceId, UpdateRequest req) {
        GbDeviceEntity device = select(deviceId);
        if (device == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在: " + deviceId);
        }
        if (!DeviceProtocolConstants.ONVIF.equals(device.getAccessProtocol())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非 ONVIF 设备, 请走国标设备编辑: " + deviceId);
        }
        boolean addrChanged = false;
        if (StringUtils.hasText(req.name())) {
            if (req.name().trim().length() > 128) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备名称过长（≤128）");
            }
            device.setName(req.name().trim());
        }
        if (StringUtils.hasText(req.ip())) {
            validateIp(req.ip());
            if (!req.ip().equals(device.getIp())) {
                device.setIp(req.ip().trim());
                addrChanged = true;
            }
        }
        if (req.port() != null) {
            validatePort(req.port());
            if (!req.port().equals(device.getPort())) {
                device.setPort(req.port());
                addrChanged = true;
            }
        }
        if (StringUtils.hasText(req.username())) {
            device.setOnvifUsername(req.username());
        }
        if (StringUtils.hasText(req.password())) {
            device.setOnvifPassword(aes.encrypt(req.password()));
        }
        // 用户名或密码任一提供时按组合口径校验（用户名明文上限 64 / 密码明文上限 128）
        validateCredential(
                StringUtils.hasText(req.username()) ? req.username() : device.getOnvifUsername(),
                StringUtils.hasText(req.password()) ? req.password() : null);
        validateProbeCycle(req.probeInterval(), req.probeTimeout());
        if (req.probeInterval() != null) {
            device.setHeartbeatInterval(req.probeInterval());
        }
        if (req.probeTimeout() != null) {
            device.setHeartbeatTimeout(req.probeTimeout());
        }
        if (addrChanged && existsAt(device.getIp(), portOf(device.getPort()), deviceId)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该设备地址已存在 ONVIF 档案: " + device.getIp() + ":" + device.getPort());
        }
        deviceMapper.updateById(device);
        if (addrChanged) {
            log.info("ONVIF 设备地址已变更, 建议手动刷新目录: device={}", deviceId);
        }
    }

    /** 添加失败回滚: 硬删新档与新通道（刚插入、无外部引用） */
    private void rollback(Long deviceDbId) {
        try {
            deviceMapper.deleteById(deviceDbId);
            channelMapper.delete(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .eq(GbDeviceChannelEntity::getDeviceDbId, deviceDbId));
        } catch (Exception e) {
            log.error("ONVIF 添加失败回滚异常(残留新档可手动删除): deviceDbId={}", deviceDbId, e);
        }
    }

    /** 同 ip:port 只允许一个活跃 ONVIF 档案（excludeDeviceId 用于 update 改地址时排除自身） */
    private boolean existsAt(String ip, int port, String excludeDeviceId) {
        return deviceMapper.selectCount(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.ONVIF)
                .eq(GbDeviceEntity::getIp, ip)
                .eq(GbDeviceEntity::getPort, port)
                .ne(excludeDeviceId != null, GbDeviceEntity::getDeviceId, excludeDeviceId)
                .isNull(GbDeviceEntity::getDeletedTime)) > 0;
    }

    private GbDeviceEntity select(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    /**
     * IPv4 手工校验（评审: 正则放行 IPv6 会拼出非法 URL 抛未受检异常; 回环/0网/链路本地/组播
     * 不可能是相机, 拒绝防 test-connection 被用作内网元数据端点探测跳板）。无正则无转义歧义。
     */
    static void validateIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 不能为空");
        }
        String[] parts = new String[4];
        int idx = 0;
        int count = 0;
        while (count < 4) {
            int dot = ip.indexOf('.', idx);
            if (count == 3 && dot >= 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
            }
            parts[count++] = dot < 0 ? ip.substring(idx) : ip.substring(idx, dot);
            if (dot < 0) {
                break;
            }
            idx = dot + 1;
            if (idx >= ip.length()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
            }
        }
        if (ip.indexOf('.', idx) >= 0 || count != 4) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
        }
        int first = parseOctet(parts[0], ip);
        parseOctet(parts[1], ip);
        parseOctet(parts[2], ip);
        parseOctet(parts[3], ip);
        if (first == 0 || first == 127 || first >= 224 || (first == 169 && "254".equals(parts[1]))) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "设备地址不允许为回环/链路本地/组播/保留网段: " + ip);
        }
    }

    private static int parseOctet(String s, String ip) {
        if (s == null || s.isEmpty() || s.length() > 3) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
            }
        }
        int v = Integer.parseInt(s);
        if (v > 255) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备 IP 须为 IPv4 地址: " + ip);
        }
        return v;
    }

    static void validatePort(Integer port) {
        if (port != null && (port < 1 || port > 65535)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "端口非法: " + port);
        }
    }

    /** 账密校验: 非空 + 长度上限（DB 列宽内, 评审: 超长现在是 500 而非 400）+ 拒绝已加密形态入参 */
    static void validateCredential(String username, String password) {
        if (StringUtils.hasText(username)) {
            if (username.length() > 64) {
                throw new BizException(ErrorCode.BAD_REQUEST, "ONVIF 用户名过长（≤64）");
            }
            if (AesCipher.isEncrypted(username)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "用户名不应为已加密形态");
            }
        }
        if (StringUtils.hasText(password)) {
            if (password.length() > 128) {
                throw new BizException(ErrorCode.BAD_REQUEST, "ONVIF 密码过长（≤128）");
            }
            if (AesCipher.isEncrypted(password)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "密码应传明文, 不应传已加密形态");
            }
        }
    }

    /** 探活周期范围（评审: 0/负值会使每个 tick 全量探活打设备） */
    static void validateProbeCycle(Integer probeInterval, Integer probeTimeout) {
        if (probeInterval != null && (probeInterval < 10 || probeInterval > 3600)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "探活周期须为 10~3600 秒: " + probeInterval);
        }
        if (probeTimeout != null && (probeTimeout < 1 || probeTimeout > 10)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "探活判死次数须为 1~10: " + probeTimeout);
        }
    }

    private static int portOf(Integer port) {
        return port != null && port > 0 ? port : OnvifDeviceDefaults.PORT;
    }

    private static List<OnvifProfile> sortedProfiles(List<OnvifProfile> profiles) {
        List<OnvifProfile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparingLong((OnvifProfile p) -> (long) p.width() * p.height()).reversed());
        return sorted;
    }
}
