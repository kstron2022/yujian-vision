package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SDP 模板契约（docs/04 §4 + docs/07 §2 回放） */
class SdpTemplatesTest {

    private static final String PLATFORM_ID = "34020000002000000001";
    private static final String IP = "192.168.1.10";

    @Test
    @DisplayName("TCP 被动: setup=passive + connection:new + TCP/RTP/AVP + y=SSRC")
    void tcpPassiveTemplate() {
        String sdp = SdpTemplates.playTcp(PLATFORM_ID, IP, 30300, "0100000001");
        assertTrue(sdp.contains("c=IN IP4 192.168.1.10"));
        assertTrue(sdp.contains("m=video 30300 TCP/RTP/AVP 96 97 98"));
        assertTrue(sdp.contains("a=setup:passive"));
        assertTrue(sdp.contains("a=connection:new"));
        assertTrue(sdp.contains("y=0100000001"));
        assertTrue(sdp.contains("s=Play"));
        assertTrue(sdp.endsWith("\r\n"));
    }

    @Test
    @DisplayName("UDP: 无 setup/connection, m= 不带 TCP")
    void udpTemplate() {
        String sdp = SdpTemplates.playUdp(PLATFORM_ID, IP, 30300, "0100000001");
        assertFalse(sdp.contains("TCP"));
        assertFalse(sdp.contains("a=setup"));
        assertFalse(sdp.contains("a=connection"));
        assertTrue(sdp.contains("m=video 30300 RTP/AVP 96 97 98"));
        assertTrue(sdp.contains("y=0100000001"));
    }

    @Test
    @DisplayName("c= 行必须是媒体服务器地址(SDP 面向设备)")
    void connectionIsMediaServerIp() {
        String sdp = SdpTemplates.playUdp("p", "10.0.0.5", 1, "0100000001");
        assertFalse(sdp.contains("127.0.0.1"));
        assertTrue(sdp.contains("10.0.0.5"));
    }

    @Test
    @DisplayName("回放 TCP: s=Playback + t=行 epoch 秒 + setup=passive")
    void playbackTcpTemplate() {
        String sdp = SdpTemplates.playbackTcp(PLATFORM_ID, IP, 30300, "0123456789", 1700000000L, 1700003600L);
        assertTrue(sdp.contains("s=Playback"));
        assertTrue(sdp.contains("t=1700000000 1700003600"));
        assertTrue(sdp.contains("m=video 30300 TCP/RTP/AVP 96 97 98"));
        assertTrue(sdp.contains("a=setup:passive"));
        assertTrue(sdp.contains("a=connection:new"));
        assertTrue(sdp.contains("y=0123456789"));
        assertTrue(sdp.endsWith("\r\n"));
        assertFalse(sdp.contains("s=Play\r\n"));
    }

    @Test
    @DisplayName("回放 UDP: s=Playback + t=行, 但无 TCP/setup/connection")
    void playbackUdpTemplate() {
        String sdp = SdpTemplates.playbackUdp(PLATFORM_ID, IP, 30300, "1123456789", 1700000000L, 1700003600L);
        assertTrue(sdp.contains("s=Playback"));
        assertTrue(sdp.contains("t=1700000000 1700003600"));
        assertTrue(sdp.contains("m=video 30300 RTP/AVP 96 97 98"));
        assertFalse(sdp.contains("TCP"));
        assertFalse(sdp.contains("a=setup"));
        assertFalse(sdp.contains("a=connection"));
    }

    @Test
    @DisplayName("回放与实况模板(TCP)仅 s= 与 t= 两行不同, 其余逐字节一致")
    void playbackDiffersFromLiveOnlyInSandT() {
        String live = SdpTemplates.playTcp(PLATFORM_ID, IP, 30300, "0123456789")
                .replace("s=Play", "s=Playback").replace("t=0 0", "t=1700000000 1700003600");
        String playback = SdpTemplates.playbackTcp(PLATFORM_ID, IP, 30300, "0123456789", 1700000000L, 1700003600L);
        assertEquals(live, playback);
    }

    @Test
    @DisplayName("级联应答 UDP: 严格回显上级收流参数 + a=sendonly, 无 setup/TCP")
    void cascadeAnswerUdp() {
        String sdp = SdpTemplates.cascadeAnswer(PLATFORM_ID, "10.1.1.5", 40000, "1100000001",
                java.util.List.of("96", "98"),
                java.util.List.of("a=rtpmap:96 PS/90000", "a=rtpmap:98 H265/90000"), null);
        assertTrue(sdp.contains("c=IN IP4 10.1.1.5"));
        assertTrue(sdp.contains("m=video 40000 RTP/AVP 96 98"));
        assertTrue(sdp.contains("y=1100000001"));
        assertTrue(sdp.contains("a=sendonly"));
        assertTrue(sdp.contains("a=rtpmap:96 PS/90000"));
        assertTrue(sdp.contains("a=rtpmap:98 H265/90000"));
        assertFalse(sdp.contains("TCP"));
        assertFalse(sdp.contains("a=setup"));
        assertFalse(sdp.contains("a=recvonly"));
        assertTrue(sdp.endsWith("\r\n"));
    }

    @Test
    @DisplayName("级联应答 TCP: 对端 active→我应 setup=passive + TCP/RTP/AVP")
    void cascadeAnswerTcp() {
        String sdp = SdpTemplates.cascadeAnswer(PLATFORM_ID, "10.1.1.5", 40000, "1100000001",
                java.util.List.of("96"), java.util.List.of("a=rtpmap:96 PS/90000"), "passive");
        assertTrue(sdp.contains("m=video 40000 TCP/RTP/AVP 96"));
        assertTrue(sdp.contains("a=setup:passive"));
        assertTrue(sdp.contains("a=connection:new"));
        assertTrue(sdp.contains("a=sendonly"));
    }
}
