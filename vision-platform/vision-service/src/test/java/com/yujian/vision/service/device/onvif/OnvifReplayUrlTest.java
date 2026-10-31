package com.yujian.vision.service.device.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** M4-B3 回放地址构造纯函数单测（评审 B3-P2-2 回归守护: 裸 URI 不得产出 "?&" 畸形 query） */
class OnvifReplayUrlTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 18, 8, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 18, 9, 0, 0);

    @Test
    @DisplayName("withWindow: 裸 URI(无 query) 用 ? 首参, 不产出 ?&")
    void bareUri() {
        String u = OnvifAccessProvider.withWindow("rtsp://10.0.0.9:554/Streaming/tracks/101", START, END);
        assertFalse(u.contains("?&"), u);
        assertTrue(u.contains("tracks/101?starttime="), u);
        assertTrue(u.contains("&endtime=20260918T010000Z"), u);
    }

    @Test
    @DisplayName("withWindow: 已有时间参数被替换为请求窗口")
    void replaceExisting() {
        String u = OnvifAccessProvider.withWindow(
                "rtsp://10.0.0.9:554/Streaming/tracks/101?starttime=20260101T000000Z&endtime=20260102T000000Z",
                START, END);
        assertFalse(u.contains("20260101"), u);
        assertTrue(u.contains("starttime=20260918T000000Z"), u);
        assertEquals(1, u.split("starttime=", -1).length - 1);
    }

    @Test
    @DisplayName("withWindow: 有 query 无时间参数则追加; 大小写形态也被替换")
    void appendAndCase() {
        String append = OnvifAccessProvider.withWindow(
                "rtsp://10.0.0.9:554/replay?profile=Profile_1", START, END);
        assertTrue(append.contains("?profile=Profile_1&starttime="), append);
        String legacy = OnvifAccessProvider.withWindow(
                "rtsp://10.0.0.9:554/replay?StartTime=20260101T000000Z", START, END);
        assertEquals(1, legacy.split("(?i)starttime=", -1).length - 1);
        assertTrue(legacy.contains("starttime=20260918T000000Z"), legacy);
    }

    @Test
    @DisplayName("deriveTracksUrl: Channels→tracks 推导; 非 Channels 形态回 null")
    void deriveTracks() {
        String u = OnvifAccessProvider.deriveTracksUrl(
                "rtsp://10.0.0.9:50554/Streaming/Channels/101?transportmode=unicast", START, END);
        assertTrue(u.startsWith("rtsp://10.0.0.9:50554/Streaming/tracks/101?"), u);
        assertFalse(u.contains("transportmode"), u);
        assertNull(OnvifAccessProvider.deriveTracksUrl("rtsp://10.0.0.9:554/onvif1", START, END));
        assertNull(OnvifAccessProvider.deriveTracksUrl("rtsp://10.0.0.9:554/onvif-replay", START, END));
    }
}
