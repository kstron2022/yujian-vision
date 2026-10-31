package com.yujian.vision.service.device.onvif;

/** gb_device.access_protocol 列的字符串常量（entity 层不依赖 spi 枚举, 列值口径唯一） */
public final class DeviceProtocolConstants {

    public static final String GB = "GB";

    public static final String ONVIF = "ONVIF";

    /** RTSP 拉流代理（M5-B1, docs/14 §4）: 静态流地址接入, 无 SIP 语义 */
    public static final String RTSP = "RTSP";

    private DeviceProtocolConstants() {
    }
}
