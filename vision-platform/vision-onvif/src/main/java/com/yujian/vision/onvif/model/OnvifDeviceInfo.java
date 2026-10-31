package com.yujian.vision.onvif.model;

/** GetDeviceInformation 应答（添加/测试连接回显 + 探活） */
public record OnvifDeviceInfo(String manufacturer, String model, String firmware, String serialNumber,
        String hardwareId) {
}
