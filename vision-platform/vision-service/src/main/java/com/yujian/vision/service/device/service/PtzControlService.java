package com.yujian.vision.service.device.service;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.gb28181.control.PtzCmdBuilder;
import com.yujian.vision.gb28181.control.PtzCmdBuilder.PresetAction;
import com.yujian.vision.gb28181.control.PtzCmdBuilder.PtzCommand;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 云台控制（docs/03 §5, docs/05 §6）：PTZCmd 编码 + DeviceControl MESSAGE 下发。
 * M1: 以 SIP 200 OK 传输应答为成功依据; 设备执行结果 RESPONSE 为可选（M2 兼容性专项）。
 * 权限: ptz:control 由 HTTP 入口 PtzController 类级 @PreAuthorize 把关; 本类另有级联透传调用方
 * CascadePtzService（上级 SIP 信令触发, 无安全上下文）, 故不在本类加服务级注解。
 */
@Slf4j
@Service
public class PtzControlService {

    private final ManScdpCodec codec;
    private final SipTransportProvider transport;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;
    private final DeviceAccessRegistry accessRegistry;

    public PtzControlService(ManScdpCodec codec, SipTransportProvider transport,
            GbDeviceMapper deviceMapper, GbDeviceChannelMapper channelMapper,
            GbPlatformProperties platform, DeviceAccessRegistry accessRegistry) {
        this.codec = codec;
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.platform = platform;
        this.accessRegistry = accessRegistry;
    }

    /** 云台控制: speed 0-100, STOP 忽略速度 */
    public void control(Long channelDbId, String command, Integer speed) {
        PtzCommand cmd;
        try {
            cmd = PtzCommand.valueOf(command.trim().toUpperCase());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知云台指令: " + command);
        }
        int spd = speed == null ? 50 : speed;
        if (spd < 0 || spd > 100) {
            throw new BizException(ErrorCode.BAD_REQUEST, "速度必须为 0-100: " + spd);
        }
        Target t = resolveTarget(channelDbId);
        // M4-B2 分流(docs/13 §5): ONVIF → ContinuousMove/Stop; GB → SIP MESSAGE
        if (DeviceProtocol.of(t.dev()) == DeviceProtocol.ONVIF) {
            accessRegistry.of(DeviceProtocol.ONVIF).controlPtz(t.dev(), t.ch(),
                    cmd.name(), spd);
            log.info("ONVIF 云台已下发: channel={} cmd={} speed={}",
                    t.ch().getChannelId(), cmd.name(), spd);
            return;
        }
        send(t, PtzCmdBuilder.buildPtz(cmd, spd), command);
    }

    /** 预置位: action = set/goto/delete, presetNum 1-255 */
    public void preset(Long channelDbId, String action, Integer presetNum) {
        PresetAction pa;
        try {
            pa = PresetAction.valueOf(action.trim().toUpperCase());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知预置位动作: " + action);
        }
        if (presetNum == null || presetNum < 1 || presetNum > 255) {
            throw new BizException(ErrorCode.BAD_REQUEST, "预置位号必须为 1-255: " + presetNum);
        }
        Target t = resolveTarget(channelDbId);
        if (DeviceProtocol.of(t.dev()) == DeviceProtocol.ONVIF) {
            accessRegistry.of(DeviceProtocol.ONVIF).controlPreset(t.dev(), t.ch(),
                    pa.name(), presetNum);
            log.info("ONVIF 预置位已下发: channel={} action={} preset={}",
                    t.ch().getChannelId(), pa.name(), presetNum);
            return;
        }
        send(t, PtzCmdBuilder.buildPreset(pa, presetNum), action + " preset " + presetNum);
    }

    /** 云台入口共同前置校验: 通道存在/通道级开关/设备在线 → (ch, dev)。协议分流在两个入口完成。 */
    private Target resolveTarget(Long channelDbId) {
        if (channelDbId == null || channelDbId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "channelDbId 不能为空");
        }
        GbDeviceChannelEntity ch = channelMapper.selectById(channelDbId);
        if (ch == null || ch.getDeletedTime() != null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "通道不存在: " + channelDbId);
        }
        if (ch.getPtzEnabled() != null && ch.getPtzEnabled() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "云台控制已被通道级开关禁用");
        }
        if (ch.getPtzType() == null || ch.getPtzType() == 0) {
            log.warn("通道未上报云台能力(ptzType=0/空), 仍按请求下发: channel={}", ch.getChannelId());
        }
        GbDeviceEntity dev = deviceMapper.selectById(ch.getDeviceDbId());
        if (dev == null || dev.getOnline() == null || dev.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线: " + ch.getChannelId());
        }
        return new Target(ch, dev);
    }

    /** 云台目标: 通道+设备 */
    private record Target(GbDeviceChannelEntity ch, GbDeviceEntity dev) {
    }


    private void send(Target t, String ptzCmdHex, String desc) {
        GbDeviceChannelEntity ch = t.ch();
        GbDeviceEntity dev = t.dev();
        // M4-B2: 协议分流已在 control/preset 入口完成, 本方法只服务 GB 链路
        long sn = SipIds.nextSn();
        // 外呼栈: 按设备注册传输协议(UDP/TCP)选择, 未启用该协议栈时回退平台默认栈
        String stackId = transport.stackIdForTransport(dev.getTransport());
        if (stackId == null) {
            stackId = platform.getStackId();
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(stackId)
                .method("MESSAGE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(ch.getChannelId() + "@" + platform.getDomain())
                .sn(sn)
                .body(codec.buildDeviceControlPtz(ch.getChannelId(), sn, ptzCmdHex))
                .targetHost(dev.getIp())
                .targetPort(dev.getPort() != null ? dev.getPort() : 5060)
                .build();
        transport.send(ctx);
        log.info("云台控制已下发: channel={} {} cmd={}", ch.getChannelId(), desc, ptzCmdHex);
    }
}
