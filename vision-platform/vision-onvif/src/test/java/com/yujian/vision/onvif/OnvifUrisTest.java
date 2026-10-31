package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 拉流地址归一化（docs/13 §5.1/§8: 嵌账密 + 主机重组） */
class OnvifUrisTest {

    private static final String IP = "10.0.0.201";

    @Test
    @DisplayName("设备 URI 无凭据 → 嵌入平台账密, 主机端口路径保留")
    void embedCredentials() {
        String out = OnvifUris.normalizePullUrl(
                "rtsp://" + IP + ":50554/Streaming/Channels/101", "user1", "cam-pass.9", IP);
        assertEquals("rtsp://user1:cam-pass.9@" + IP + ":50554/Streaming/Channels/101", out);
    }

    @Test
    @DisplayName("设备 URI 自带凭据 → 剥离后换平台账密(防其他账号残留)")
    void replaceExistingCredentials() {
        String out = OnvifUris.normalizePullUrl(
                "rtsp://admin:oldpw@" + IP + ":554/Streaming/Channels/102", "user1", "p@ss w:rd", IP);
        assertEquals("rtsp://user1:p%40ss%20w%3Ard@" + IP + ":554/Streaming/Channels/102", out);
    }

    @Test
    @DisplayName("URI 主机与手填 IP 不一致(NAT/多网卡) → 按手填 IP 重组, 端口路径保留")
    void rebuildHostWhenDifferent() {
        String out = OnvifUris.normalizePullUrl(
                "rtsp://192.168.1.64:554/onvif1", "u", "p", IP);
        assertEquals("rtsp://u:p@" + IP + ":554/onvif1", out);
    }

    @Test
    @DisplayName("URI 无端口 → 保留缺省(不补 554)")
    void keepPortlessUri() {
        String out = OnvifUris.normalizePullUrl(
                "rtsp://" + IP + "/stream1", "u", "p", IP);
        assertEquals("rtsp://u:p@" + IP + "/stream1", out);
    }

    @Test
    @DisplayName("密码含 @ 与查询串容错 + 大小写 scheme")
    void tolerantParsing() {
        String out = OnvifUris.normalizePullUrl(
                "RTSP://" + IP + ":8554/live?tcp=1", "u1", "p@ss", IP);
        assertEquals("rtsp://u1:p%40ss@" + IP + ":8554/live?tcp=1", out);
    }

    @Test
    @DisplayName("非 RTSP scheme / 空地址 / 缺主机 → 拒绝")
    void rejects() {
        assertThrows(IllegalArgumentException.class,
                () -> OnvifUris.normalizePullUrl("http://" + IP + ":50080/snap", "u", "p", IP));
        assertThrows(IllegalArgumentException.class,
                () -> OnvifUris.normalizePullUrl("  ", "u", "p", IP));
        assertThrows(IllegalArgumentException.class,
                () -> OnvifUris.normalizePullUrl("rtsp://:554/x", "u", "p", IP));
        assertTrue(true);
    }
}
