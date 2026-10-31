package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.sip.transport.SipEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 心跳处理（docs/05 §2）：MESSAGE Notify/Keepalive → 刷新 keepalive_time 维持在线
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatService {

    private final GbDeviceMapper deviceMapper;

    public void handle(SipEvent event, ManScdpMessage message) {
        String deviceId = message.getDeviceId() != null ? message.getDeviceId() : event.getFromUser();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        GbDeviceEntity device = selectByDeviceId(deviceId);
        if (device == null) {
            log.warn("心跳来自未知设备: {}", deviceId);
            return;
        }
        GbDeviceEntity update = new GbDeviceEntity();
        update.setId(device.getId());
        update.setKeepaliveTime(LocalDateTime.now());
        update.setOnline(1);
        deviceMapper.updateById(update);
        log.debug("心跳: {}", deviceId);
    }

    private GbDeviceEntity selectByDeviceId(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }
}
