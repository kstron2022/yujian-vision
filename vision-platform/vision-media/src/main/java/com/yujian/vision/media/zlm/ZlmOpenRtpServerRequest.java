package com.yujian.vision.media.zlm;

/**
 * openRtpServer 请求参数（docs/04 §3）
 *
 * @param port       0=ZLM 自动分配（平台只记账，docs/06 §5）
 * @param enableTcp  是否启用 TCP 收流
 * @param streamMode TCP 模式细分：passive（默认，平台为 TCP Server）/ active
 * @param ssrc       10 位十进制 SSRC（指定后 ZLM 校验，防串流第一道闸）
 * @param streamId   流 ID（实况 gb_play_{channelId}，回放 gb_playback_*）
 */
public record ZlmOpenRtpServerRequest(
        int port,
        boolean enableTcp,
        String streamMode,
        String ssrc,
        String streamId) {

    public static ZlmOpenRtpServerRequest tcpPassive(String streamId, String ssrc) {
        return new ZlmOpenRtpServerRequest(0, true, "passive", ssrc, streamId);
    }

    public static ZlmOpenRtpServerRequest udp(String streamId, String ssrc) {
        return new ZlmOpenRtpServerRequest(0, false, null, ssrc, streamId);
    }
}
