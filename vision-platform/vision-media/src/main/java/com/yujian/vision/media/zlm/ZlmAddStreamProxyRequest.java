package com.yujian.vision.media.zlm;

/**
 * addStreamProxy 请求参数（M3-B6 阶段1 跨节点回源, docs/04 §3; M5-B1 扩 rtp_type, docs/14 §5）。
 *
 * <p>url 使用签名 TS 地址（PlayUrlService.buildInternalPullUrl）: 签名通过 on_play 鉴权,
 * 容器内 DNS 主机名（如 http://zlm:80）由 vision.media.proxy-source-base 覆盖。
 * 开关策略: 面向观众点播的代理流（ONVIF/RTSP 拉流）四协议全开——历史上 hls 关
 * （"转码/落盘策略归主节点, 代理节点仅分发"是 M3-B6 回源时代的考量）, 2026-09-25 242
 * 实测缺陷: 代理流 schema 缺 hls, 而播放地址四协议齐发 → 前端切 HLS 必 404
 * (manifestLoadError)。级联回源转推流（forDispatch）观众是上级平台, hls 无用, 维持关闭。</p>
 *
 * <p>rtpType 透传 ZLM rtp_type: 拉 RTSP 源的传输方式（0=RTP_TCP 1=RTP_UDP 2=RTP_MULTICAST）,
 * M5-B1 拉流代理按设备档案 rtsp_transport 映射（tcp→0 / udp→1）; http 等非 RTSP 源 ZLM 忽略。
 * M4-B2/M4-B3 ONVIF 拉流未暴露该参数, ZLM 默认 0(TCP), 保持行为不变传 0。</p>
 */
public record ZlmAddStreamProxyRequest(
        String app,
        String stream,
        String url,
        int enableRtsp,
        int enableRtmp,
        int enableHls,
        int enableTs,
        int rtpType) {

    /** 分发场景默认开关：rtsp/rtmp/ts 开, hls 关; rtp_type=0(TCP, ZLM 默认) */
    public static ZlmAddStreamProxyRequest forDispatch(String app, String stream, String url) {
        return new ZlmAddStreamProxyRequest(app, stream, url, 1, 1, 0, 1, 0);
    }

    /** ONVIF 拉流（M4-B2/B3 沿用）: 观众点播流, 四协议全开(含 hls, 见类注释) + rtp_type=0(TCP, ZLM 默认) */
    public static ZlmAddStreamProxyRequest forOnvifPull(String app, String stream, String url) {
        return new ZlmAddStreamProxyRequest(app, stream, url, 1, 1, 1, 1, 0);
    }

    /** RTSP 拉流代理（M5-B1）: 观众点播流, 四协议全开(含 hls, 见类注释);
     *  按 rtsp_transport 映射 rtp_type（tcp→0 / udp→1, 其他值按 TCP 兜底） */
    public static ZlmAddStreamProxyRequest forRtspPull(String app, String stream, String url,
            String rtspTransport) {
        int rtpType = "udp".equalsIgnoreCase(rtspTransport) ? 1 : 0;
        return new ZlmAddStreamProxyRequest(app, stream, url, 1, 1, 1, 1, rtpType);
    }
}
