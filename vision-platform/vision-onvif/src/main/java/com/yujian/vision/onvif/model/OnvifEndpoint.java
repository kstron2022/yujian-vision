package com.yujian.vision.onvif.model;

/** ONVIF 设备端点（docs/13 §4：ip/port 对应 gb_device.ip/port 在 ONVIF 行的语义） */
public record OnvifEndpoint(String ip, int port) {

    public static final int DEFAULT_PORT = 80;

    public String deviceServiceUrl() {
        return "http://" + ip + ":" + port + "/onvif/device_service";
    }
}
