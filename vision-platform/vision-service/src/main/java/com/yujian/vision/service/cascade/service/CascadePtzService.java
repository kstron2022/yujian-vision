package com.yujian.vision.service.cascade.service;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.control.PtzCmdBuilder;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.device.service.PtzControlService;
import com.yujian.vision.sip.transport.SipEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 级联 PTZ 透传（B2 阶段 3, docs/08 §2.4）：上级 Control/DeviceControl → 解码 PTZCmd →
 * DeviceID 按共享通道三级反查 → 复用设备侧 {@link PtzControlService} 下发真实设备。
 * 本平台应答 200 OK 由 SipEventRouter 统一发送；下级设备执行结果 RESPONSE 为可选（M1 口径）。
 * ptz_forward!=1 的上级一律忽略（只回 200 不动作, 与目录共享范围外同策略）。
 */
@Slf4j
@Service
public class CascadePtzService {

    private final CascadeInviteService cascadeInvites;
    private final PtzControlService ptzControlService;

    public CascadePtzService(CascadeInviteService cascadeInvites, PtzControlService ptzControlService) {
        this.cascadeInvites = cascadeInvites;
        this.ptzControlService = ptzControlService;
    }

    /** 上级 DeviceControl 入口（SipEventRouter Control/DeviceControl 分支调用） */
    public void onUpperControl(SipEvent event, ManScdpMessage message) {
        String upperDeviceId = event.getFromUser();
        GbCascadeUpperEntity u = cascadeInvites.findByUpperDeviceId(upperDeviceId);
        if (u == null) {
            log.debug("DeviceControl 非启用上级, 忽略: {}", upperDeviceId);
            return;
        }
        if (u.getPtzForward() == null || u.getPtzForward() != 1) {
            log.info("上级未开启 PTZ 透传, 忽略: upper={} channel={}", upperDeviceId, message.getDeviceId());
            return;
        }
        String ptzHex = message.getPtzCmd();
        if (ptzHex == null || ptzHex.isBlank()) {
            log.info("DeviceControl 无 PTZCmd, 忽略: upper={} (暂只透传云台/预置位指令)", upperDeviceId);
            return;
        }
        String targetId = message.getDeviceId();
        if (targetId == null || targetId.isBlank()) {
            log.warn("DeviceControl 缺 DeviceID, 忽略: upper={} ptz={}", upperDeviceId, ptzHex);
            return;
        }
        Long channelDbId = cascadeInvites.resolveChannelDbId(u, targetId);
        if (channelDbId == null) {
            log.info("DeviceControl 通道未共享, 忽略: upper={} target={}", upperDeviceId, targetId);
            return;
        }
        PtzCmdBuilder.DecodedPtz decoded;
        try {
            decoded = PtzCmdBuilder.decode(ptzHex);
        } catch (IllegalArgumentException e) {
            log.warn("上级 PTZCmd 非法, 忽略: upper={} {} ({})", upperDeviceId, ptzHex, e.getMessage());
            return;
        }
        // 兜底保证 200 OK 由 router 必发（上级 UDP 重传昂贵）: 业务失败 warn, 其余异常 error
        try {
            if (decoded.presetAction() != null) {
                ptzControlService.preset(channelDbId, decoded.presetAction().name(), decoded.presetNum());
                log.info("级联预置位透传: upper={} channel={} {} preset={}",
                        upperDeviceId, targetId, decoded.presetAction(), decoded.presetNum());
            } else {
                ptzControlService.control(channelDbId, decoded.command().name(), decoded.speedPercent());
                log.info("级联PTZ透传: upper={} channel={} {} speed={}%",
                        upperDeviceId, targetId, decoded.command(), decoded.speedPercent());
            }
        } catch (BizException e) {
            // 下级设备不在线/通道失效等: 国标无错误应答通道, 记日志即可
            log.warn("级联PTZ透传失败: upper={} channel={} {}: {}",
                    upperDeviceId, targetId, decoded.command(), e.getMessage());
        } catch (Exception e) {
            log.error("级联PTZ透传异常: upper={} channel={}", upperDeviceId, targetId, e);
        }
    }
}
