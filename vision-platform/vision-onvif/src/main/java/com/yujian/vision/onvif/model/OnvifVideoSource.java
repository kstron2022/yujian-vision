package com.yujian.vision.onvif.model;

import java.util.List;

/** GetVideoSources 应答条目：1 个 VideoSource = 1 路平台通道（docs/13 §4 映射模型） */
public record OnvifVideoSource(String token, List<OnvifProfile> profiles) {
}
