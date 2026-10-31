package com.yujian.vision.onvif.model;

/**
 * ONVIF 预置位（GetPresets 应答条目, 2026-09-25 现场补齐）。
 * token=设备侧预置位标识（海康通常为数字串, 与 GB 预置位号语义兼容）, name=展示名。
 */
public record OnvifPreset(String token, String name) {
}
