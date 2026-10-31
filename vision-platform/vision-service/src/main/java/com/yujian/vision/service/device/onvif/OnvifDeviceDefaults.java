package com.yujian.vision.service.device.onvif;

/** ONVIF 默认值集中（docs/13 §4） */
public final class OnvifDeviceDefaults {

    /** ONVIF HTTP 端口默认值（海康等厂商出厂配置） */
    public static final int PORT = 80;

    /** 探活周期默认值（秒）, 复用 gb_device.heartbeat_interval */
    public static final int PROBE_INTERVAL = 60;

    /** 探活判死次数默认值, 复用 gb_device.heartbeat_timeout */
    public static final int PROBE_TIMEOUT = 3;

    private OnvifDeviceDefaults() {
    }
}
