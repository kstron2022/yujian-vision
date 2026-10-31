package com.yujian.vision.service.media.service;

import java.util.List;

/** 点播 SDP 模板（docs/04 §4）：TCP 被动 + UDP 变体 */
public final class SdpTemplates {

    private SdpTemplates() {
    }

    /** 实况 INVITE SDP（TCP 被动：平台/ZLM 为 TCP Server，设备主动连接） */
    public static String playTcp(String platformId, String publicIp, int port, String ssrc) {
        return String.join("\r\n",
                "v=0",
                "o=" + platformId + " 0 0 IN IP4 " + publicIp,
                "s=Play",
                "c=IN IP4 " + publicIp,
                "t=0 0",
                "m=video " + port + " TCP/RTP/AVP 96 97 98",
                "a=recvonly",
                "a=rtpmap:96 PS/90000",
                "a=rtpmap:97 H264/90000",
                "a=rtpmap:98 H265/90000",
                "a=setup:passive",
                "a=connection:new",
                "y=" + ssrc,
                "") + "\r\n";
    }

    /** UDP 变体（无 setup/connection 行, m= 行不带 TCP） */
    public static String playUdp(String platformId, String publicIp, int port, String ssrc) {
        return String.join("\r\n",
                "v=0",
                "o=" + platformId + " 0 0 IN IP4 " + publicIp,
                "s=Play",
                "c=IN IP4 " + publicIp,
                "t=0 0",
                "m=video " + port + " RTP/AVP 96 97 98",
                "a=recvonly",
                "a=rtpmap:96 PS/90000",
                "a=rtpmap:97 H264/90000",
                "a=rtpmap:98 H265/90000",
                "y=" + ssrc,
                "") + "\r\n";
    }

    /** 回放 INVITE SDP（TCP 被动）: s=Playback, t=行=起止 epoch 秒(GB/T 28181-2016; 厂商时区解释差异列入 B6 兼容性) */
    public static String playbackTcp(String platformId, String publicIp, int port, String ssrc,
            long startEpoch, long endEpoch) {
        return String.join("\r\n",
                "v=0",
                "o=" + platformId + " 0 0 IN IP4 " + publicIp,
                "s=Playback",
                "c=IN IP4 " + publicIp,
                "t=" + startEpoch + " " + endEpoch,
                "m=video " + port + " TCP/RTP/AVP 96 97 98",
                "a=recvonly",
                "a=rtpmap:96 PS/90000",
                "a=rtpmap:97 H264/90000",
                "a=rtpmap:98 H265/90000",
                "a=setup:passive",
                "a=connection:new",
                "y=" + ssrc,
                "") + "\r\n";
    }

    /** 回放 UDP 变体（无 setup/connection 行, m= 行不带 TCP; UDP 收流超时降级路径复用） */
    public static String playbackUdp(String platformId, String publicIp, int port, String ssrc,
            long startEpoch, long endEpoch) {
        return String.join("\r\n",
                "v=0",
                "o=" + platformId + " 0 0 IN IP4 " + publicIp,
                "s=Playback",
                "c=IN IP4 " + publicIp,
                "t=" + startEpoch + " " + endEpoch,
                "m=video " + port + " RTP/AVP 96 97 98",
                "a=recvonly",
                "a=rtpmap:96 PS/90000",
                "a=rtpmap:97 H264/90000",
                "a=rtpmap:98 H265/90000",
                "y=" + ssrc,
                "") + "\r\n";
    }

    /**
     * 语音对讲 INVITE SDP（M3-B5, GB/T 28181-2022 Talk 流程, docs/03 §5.x）:
     * s=Talk, m=audio 平台侧端口 RTP/AVP 8（PCMA/G.711A 8kHz）, a=sendrecv（平台推音频为主,
     * 兼容设备回传; 仅 UDP —— 对讲低码率实时, TCP 变体不做, 设备侧实测差异挂账真机窗口）。
     * 端口=openRtpServer 分配的设备腿收流端口（y= 同点播约定 10 位十进制, 首位 0）。
     */
    public static String talkUdp(String platformId, String publicIp, int port, String ssrc) {
        return String.join("\r\n",
                "v=0",
                "o=" + platformId + " 0 0 IN IP4 " + publicIp,
                "s=Talk",
                "c=IN IP4 " + publicIp,
                "t=0 0",
                "m=audio " + port + " RTP/AVP 8",
                "a=sendrecv",
                "a=rtpmap:8 PCMA/8000",
                "y=" + ssrc,
                "") + "\r\n";
    }

    /**
     * 级联 INVITE 的 200 OK SDP 应答（M3-B2, docs/08 §2.3 严格回显协商）：媒体参数全部回显上级
     * 收流参数（c=地址/m=端口/y=SSRC/payload 与 rtpmap 原样带回）, a=recvonly → a=sendonly,
     * TCP 时 a=setup 按「对端 active→我 passive / 对端 passive→我 active」反向应答。
     *
     * @param payloadTypes   上级 m= 行的 payload 列表（如 "96 98"）
     * @param rtpmapLines    上级 SDP 的 rtpmap 行原样列表
     * @param answerSetup    TCP 模式的应答 setup 值（passive/active）, UDP 传 null
     */
    public static String cascadeAnswer(String localDeviceId, String remoteIp, int remotePort, String ssrc,
            List<String> payloadTypes, List<String> rtpmapLines, String answerSetup) {
        String mediaLine = "m=video " + remotePort
                + (answerSetup != null ? " TCP/RTP/AVP " : " RTP/AVP ")
                + String.join(" ", payloadTypes);
        List<String> lines = new java.util.ArrayList<>(java.util.List.of(
                "v=0",
                "o=" + localDeviceId + " 0 0 IN IP4 " + remoteIp,
                "s=Play",
                "c=IN IP4 " + remoteIp,
                "t=0 0",
                mediaLine,
                "a=sendonly"));
        if (answerSetup != null) {
            lines.add("a=setup:" + answerSetup);
            lines.add("a=connection:new");
        }
        lines.addAll(rtpmapLines);
        lines.add("y=" + ssrc);
        lines.add("");
        return String.join("\r\n", lines) + "\r\n";
    }
}
