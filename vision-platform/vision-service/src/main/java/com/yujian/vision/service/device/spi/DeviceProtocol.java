package com.yujian.vision.service.device.spi;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceEntity;

/**
 * 接入协议（M4-B1, docs/13 §3.2）：gb_device.access_protocol 的类型化形态。
 */
public enum DeviceProtocol {

    GB, ONVIF, RTSP;

    /** 实体 access_protocol 列 → 枚举（空/未知按 GB, 兼容存量行） */
    public static DeviceProtocol of(String accessProtocol) {
        if ("ONVIF".equalsIgnoreCase(accessProtocol)) {
            return ONVIF;
        }
        if ("RTSP".equalsIgnoreCase(accessProtocol)) {
            return RTSP;
        }
        return GB;
    }

    public static DeviceProtocol of(GbDeviceEntity device) {
        return of(device == null ? null : device.getAccessProtocol());
    }

    /** 非国标通道的未交付能力守卫：非 GB 一律给明确业务错误而非 GB 链路噪声（M5 起 RTSP 同样拦截） */
    public static void ensureGb(GbDeviceEntity device, String feature, String milestone) {
        if (of(device) != GB) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    (of(device) == ONVIF ? "ONVIF" : "拉流代理") + "通道" + feature + "将在 " + milestone + " 提供");
        }
    }
}
