package com.yujian.vision.onvif.model;

/**
 * GetProfiles 应答条目。sourceToken 关联所属 VideoSource；
 * width/height/encoding 用于主/子码流档位排序与 H.265 判据。
 */
public record OnvifProfile(String token, String name, String sourceToken,
        int width, int height, String encoding, boolean ptz) {
}
