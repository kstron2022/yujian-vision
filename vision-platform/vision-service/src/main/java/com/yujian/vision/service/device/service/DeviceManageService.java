package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 设备管理（docs/03 §3, M1-9 A4）：分页/详情/预建档/参数修改/删除。
 * M3-B3 阶段1（docs/08 §3）：create 预建档支持下级平台（deviceType=2）先配密码再注册；
 * 注册链路（DeviceRegisterService.upsertDevice）对已有档案不覆盖类型/名称/密码。
 */
@Service
@RequiredArgsConstructor
public class DeviceManageService {

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final AesCipher aes;
    // M3-B3 阶段4: 删除设备时清目录订阅
    private final CatalogSubscriptionService catalogSubscriptionService;
    // M3-B4: 删除设备时同步清报警订阅
    private final AlarmSubscriptionService alarmSubscriptionService;
    // M5-B1: 拉流代理删除联动（拆常驻代理; 非 RTSP 设备 no-op）
    private final com.yujian.vision.service.device.rtsp.RtspDeviceManageService rtspDeviceManageService;

    /**
     * 设备预建档（M3-B3 阶段1）：新增下级平台（deviceType=2）或设备，先配置接入密码再注册。
     * 默认值与注册自动建档对齐（协议 2016/UDP/TCP 被动收流/心跳 60s×3 周期）。
     */
    public GbDeviceEntity create(String deviceId, String name, String password,
            Integer deviceType, String protocolVersion, Integer mediaTcpMode) {
        if (deviceId == null || !deviceId.matches("\\d{20}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "国标编码须为 20 位数字: " + deviceId);
        }
        if (deviceType == null || deviceType != 1 && deviceType != 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备类型仅支持 1(设备)/2(下级平台)");
        }
        if (!StringUtils.hasText(password)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "接入密码不能为空（生产必须预配置强密码, docs/10）");
        }
        if (select(deviceId) != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备编码已存在: " + deviceId);
        }
        LocalDateTime now = LocalDateTime.now();
        GbDeviceEntity device = new GbDeviceEntity();
        device.setDeviceId(deviceId);
        device.setName(StringUtils.hasText(name) ? name : deviceId);
        device.setDeviceType(deviceType);
        device.setProtocolVersion(StringUtils.hasText(protocolVersion) ? protocolVersion : "2016");
        device.setTransport("UDP");
        device.setPassword(aes.encrypt(password));
        device.setExpires(3600);
        device.setHeartbeatInterval(60);
        device.setHeartbeatTimeout(3);
        device.setMediaTcpMode(mediaTcpMode != null ? mediaTcpMode : 1);
        device.setOnline(0);
        device.setRegisterTime(now);
        deviceMapper.insert(device);
        return device;
    }

    public IPage<GbDeviceEntity> page(Integer online, String keyword, int page, int count) {
        return page(online, keyword, null, page, count);
    }

    /**
     * 设备分页（M5-B1 加协议筛选, docs/14 §5）：accessProtocol 空/未知值=全部
     * （前端下拉"全部"不传参）; 合法值 GB/ONVIF/RTSP 精确过滤。
     */
    public IPage<GbDeviceEntity> page(Integer online, String keyword, String accessProtocol,
            int page, int count) {
        boolean byProtocol = "GB".equalsIgnoreCase(accessProtocol)
                || "ONVIF".equalsIgnoreCase(accessProtocol)
                || "RTSP".equalsIgnoreCase(accessProtocol);
        IPage<GbDeviceEntity> p = deviceMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(online != null, GbDeviceEntity::getOnline, online)
                        .eq(byProtocol, GbDeviceEntity::getAccessProtocol,
                                byProtocol ? accessProtocol.toUpperCase() : null)
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(GbDeviceEntity::getDeviceId, keyword)
                                .or().like(GbDeviceEntity::getName, keyword))
                        .isNull(GbDeviceEntity::getDeletedTime)
                        .orderByDesc(GbDeviceEntity::getId));
        fillChannelCounts(p.getRecords());
        return p;
    }

    /**
     * 批量填充本页设备的通道数（一次 group by, 软删通道不计）。
     * 多通道设备 ≈ NVR/编码器、单通道 ≈ IPC 的辅助识别信号（协议层无可靠字段, 启发式）。
     */
    private void fillChannelCounts(java.util.List<GbDeviceEntity> devices) {
        if (devices == null || devices.isEmpty()) {
            return;
        }
        java.util.List<Long> ids = devices.stream().map(GbDeviceEntity::getId).toList();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<GbDeviceChannelEntity> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.select("device_db_id", "count(*) as cnt")
                .in("device_db_id", ids)
                .isNull("deleted_time")
                .groupBy("device_db_id");
        java.util.Map<Long, Long> byDevice = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : channelMapper.selectMaps(qw)) {
            Object devId = row.get("device_db_id");
            Object cnt = row.get("cnt");
            if (devId instanceof Number n && cnt instanceof Number c) {
                byDevice.put(n.longValue(), c.longValue());
            }
        }
        for (GbDeviceEntity d : devices) {
            d.setChannelCount(byDevice.getOrDefault(d.getId(), 0L).intValue());
        }
    }

    public GbDeviceEntity detail(String deviceId) {
        GbDeviceEntity d = select(deviceId);
        if (d == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在: " + deviceId);
        }
        return d;
    }

    /**
     * 修改设备参数（名称/类型/密码/心跳/收流方式/协议版本, docs/03 §3）。
     * name 传 null 不改; deviceType 传 null 不改（1=设备 2=下级平台, M3-B3 级联标识）。
     */
    public void update(String deviceId, String name, Integer deviceType, String password,
            Integer heartbeatInterval, Integer heartbeatTimeout, Integer mediaTcpMode,
            String protocolVersion) {
        GbDeviceEntity d = detail(deviceId);
        if (StringUtils.hasText(name)) {
            d.setName(name);
        }
        if (deviceType != null) {
            if (deviceType != 1 && deviceType != 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备类型仅支持 1(设备)/2(下级平台)");
            }
            // 改型守卫(收口评审): 类型决定权限口径(cascade:play vs device:play)与订阅归属,
            // 已有通道时禁止改型防权限旁路(2→1 绕过 cascade:play); 需先删通道或重建档案
            if (!deviceType.equals(d.getDeviceType())) {
                if (channelMapper.selectCount(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, d.getId())
                        .isNull(GbDeviceChannelEntity::getDeletedTime)) > 0) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "设备已存在通道, 禁止修改设备类型（先删除通道或重建档案）");
                }
                d.setDeviceType(deviceType);
                // 改型后旧目录订阅作废(无通道场景的防御性清理, 有订阅续订自然终止)
                catalogSubscriptionService.onDeviceOffline(deviceId);
            }
        }
        if (StringUtils.hasText(password)) {
            d.setPassword(aes.encrypt(password));
        }
        if (heartbeatInterval != null) {
            d.setHeartbeatInterval(heartbeatInterval);
        }
        if (heartbeatTimeout != null) {
            d.setHeartbeatTimeout(heartbeatTimeout);
        }
        if (mediaTcpMode != null) {
            d.setMediaTcpMode(mediaTcpMode);
        }
        if (StringUtils.hasText(protocolVersion)) {
            d.setProtocolVersion(protocolVersion);
        }
        deviceMapper.updateById(d);
    }

    /** 设备接入密码明文（对接信息复制用, 仅 device:update; 与 gb_device.password 同源解密） */
    public String resolvePlainPassword(String deviceId) {
        GbDeviceEntity d = detail(deviceId);
        String stored = d.getPassword();
        return stored == null || stored.isBlank() ? "" : aes.decrypt(stored);
    }

    /** 删除设备（软删设备+通道, docs/03 §3; 设备重新注册会重建档案） */
    public void delete(String deviceId) {
        GbDeviceEntity d = detail(deviceId);
        // M5-B1: 拉流代理删除联动拆常驻代理+清常驻索引（软删前, 通道尚可查 streamId）;
        // 非 RTSP 设备为 no-op。按需源代理随活跃会话清理链路自然拆除。
        rtspDeviceManageService.deleteProxyOnRemove(deviceId);
        LocalDateTime now = LocalDateTime.now();
        // online 同步置 0: 删除即离线。软删行若留着 online=1, 会被离线判定/探活任务当成活设备
        // （deletedTime 无 @TableLogic, 各查询必须自带软删过滤; 这里从源头把幽灵行踢出在线集合）。
        deviceMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getId, d.getId())
                        .set(GbDeviceEntity::getDeletedTime, now)
                        .set(GbDeviceEntity::getOnline, 0));
        channelMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, d.getId())
                        .set(GbDeviceChannelEntity::getDeletedTime, now));
        // M3-B3 阶段4: 删除下级设备同步清目录订阅
        catalogSubscriptionService.onDeviceDeleted(d.getDeviceId());
        // M3-B4: 删除设备同步清报警订阅
        alarmSubscriptionService.onDeviceDeleted(d.getDeviceId());
    }

    private GbDeviceEntity select(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }
}
