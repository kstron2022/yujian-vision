package com.yujian.vision.onvif.model;

/**
 * WS-Discovery 探测发现的一台设备（M4-B4, docs/13 §2.2）。
 * uuid = 设备 EPR Address, 仅用于本轮探测去重, 不落库（已接入判定走 ip:port 匹配, 见服务层）。
 */
public record OnvifDiscoveredDevice(String uuid, String ip, int port, String serviceUrl, String modelHint) {
}
