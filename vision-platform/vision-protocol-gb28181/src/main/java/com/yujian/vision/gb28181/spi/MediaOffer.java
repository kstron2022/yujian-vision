package com.yujian.vision.gb28181.spi;

/**
 * 媒体协商要素：构建 INVITE SDP 的输入（docs/04 §4 模板）
 *
 * @param session    Play（实况）/ Playback（回放）/ Talk（对讲）
 * @param mediaIp    ZLM 设备可达 IP（SDP c= 行；跨机部署 ≠ SIP 服务器 IP）
 * @param rtpPort    ZLM 分配的收流端口
 * @param ssrc       10 位十进制 SSRC（首位 0 实况 / 1 回放）
 * @param tcpPassive true=TCP 被动（a=setup:passive）；false=UDP
 * @param startEpoch 回放起始时间（epoch 秒，SDP t= 第一段；实况传 null）
 * @param endEpoch   回放结束时间（epoch 秒；实况传 null）
 * @param channelId  通道国标编码（回放 u= 行）
 */
public record MediaOffer(
        String session,
        String mediaIp,
        int rtpPort,
        String ssrc,
        boolean tcpPassive,
        Long startEpoch,
        Long endEpoch,
        String channelId) {
}
