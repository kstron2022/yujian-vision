package com.yujian.vision.service.media.service;

import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 播放 URL 组装与签名（docs/04 §5, docs/03 §1.2）。
 * sign = md5(streamId + expire + secret)
 *
 * <p>B5 多协议：新增 rtc（ZLM 内置 WebRTC 播放交换 API, 浏览器侧完成 SDP exchange）
 * 与 vision.play.public-base 外部 base 覆盖（HTTPS 化伴生项——反代域名/证书就绪后
 * 配置为 https://xxx 即全量出 https/wss 地址, 平台代码零改动）。</p>
 */
@Service
public class PlayUrlService {

    /** 播放地址外部 base（空=按节点 public_ip:http_port 拼 http/ws; 配置如 https://media.example.com） */
    private final String publicBase;

    private final GbMediaServerMapper serverMapper;
    private final AesCipher aes;
    /** 跨节点回源源站 base 覆盖（容器编排内主机名, 空=按源节点 hostIp:httpPort） */
    private final String proxyBase;

    public PlayUrlService(GbMediaServerMapper serverMapper, AesCipher aes,
            @Value("${vision.play.public-base:}") String publicBase,
            @Value("${vision.media.proxy-source-base:}") String proxyBase) {
        this.serverMapper = serverMapper;
        this.aes = aes;
        this.publicBase = publicBase == null ? "" : publicBase.trim();
        this.proxyBase = proxyBase == null ? "" : proxyBase.trim();
    }

    public Map<String, String> buildUrls(String serverId, String streamId, long ttlSeconds) {
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, serverId).last("LIMIT 1"));
        if (node == null) {
            return Map.of();
        }
        String base = publicBase.isEmpty()
                ? "http://" + node.getPublicIp() + ":" + node.getHttpPort()
                : publicBase;
        String wsBase = publicBase.isEmpty()
                ? "ws://" + node.getPublicIp() + ":" + node.getHttpPort()
                : toWsBase(base);
        long expire = System.currentTimeMillis() / 1000 + ttlSeconds;
        String sign = md5(streamId + expire + aes.decrypt(node.getSignSecret()));
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("flv", base + "/rtp/" + streamId + ".live.flv?sign=" + sign + "&expire=" + expire);
        urls.put("wsFlv", wsBase + "/rtp/" + streamId + ".live.flv?sign=" + sign + "&expire=" + expire);
        urls.put("hls", base + "/rtp/" + streamId + "/hls.m3u8?sign=" + sign + "&expire=" + expire);
        urls.put("ts", base + "/rtp/" + streamId + ".live.ts?sign=" + sign + "&expire=" + expire);
        // WebRTC 播放: ZLM 内置 SDP 交换 API（POST offer → answer）, sign/expire 随 on_play 走同一防盗链校验
        urls.put("rtc", base + "/index/api/webrtc?app=rtp&stream=" + streamId
                + "&type=play&sign=" + sign + "&expire=" + expire);
        return urls;
    }

    /**
     * 对讲音频源 WebRTC 推流地址（M3-B5）：app=rtp + type=push, 不带 sign——on_publish 以
     * "推入流 = 活跃对讲会话的音频源流"裁决（HookService.onPublish 组合判定）。public-base 覆盖逻辑同 buildUrls。
     */
    public String rtcPushUrl(GbMediaServerEntity node, String audioStreamId) {
        String base = publicBase.isEmpty()
                ? "http://" + node.getPublicIp() + ":" + node.getHttpPort()
                : publicBase;
        return base + "/index/api/webrtc?app=rtp&stream=" + audioStreamId + "&type=push";
    }

    /**
     * 跨节点回源拉流 URL（M3-B6 阶段1）：目标节点 addStreamProxy 从源节点拉流的地址。
     * 用签名 TS 地址（on_play 鉴权同一套签名校验, 过期对齐播放 TTL）;
     * 源 base 默认 http://{hostIp}:{httpPort}（多主机部署）,
     * 容器编排内可用 vision.media.proxy-source-base 覆盖为主机名（如 http://zlm:80, 容器间 DNS 可达）。
     */
    public String buildInternalPullUrl(GbMediaServerEntity srcNode, String streamId, long ttlSeconds) {
        String base = proxyBase.isEmpty()
                ? "http://" + srcNode.getHostIp() + ":" + srcNode.getHttpPort()
                : proxyBase;
        long expire = System.currentTimeMillis() / 1000 + ttlSeconds;
        String sign = md5(streamId + expire + aes.decrypt(srcNode.getSignSecret()));
        return base + "/rtp/" + streamId + ".live.ts?sign=" + sign + "&expire=" + expire;
    }

    /**
     * 平台自拉流地址（M3-B7 抓图）：getSnap 的 url 入参——ZLM 内部播放器从本节点拉流抽帧。
     * <b>必须用节点自身 hostIp:httpPort</b>, 不可用 proxy-source-base（容器编排下它是服务名
     * http://zlm:80, 供 ZLM 互拉回源; 抓图是平台进程→指定节点 ZLM 的动作, 按真实地址拼）。
     * 带签名 http-flv, on_play 同一套签名校验可通过。
     */
    public String buildSelfPullUrl(GbMediaServerEntity node, String streamId, long ttlSeconds) {
        long expire = System.currentTimeMillis() / 1000 + ttlSeconds;
        String sign = md5(streamId + expire + aes.decrypt(node.getSignSecret()));
        return "http://" + node.getHostIp() + ":" + node.getHttpPort()
                + "/rtp/" + streamId + ".live.flv?sign=" + sign + "&expire=" + expire;
    }

    /** http(s) base → ws(s) base（http→ws, https→wss; 其余原样, 调用方已保证 scheme 正确） */
    private static String toWsBase(String base) {
        String lower = base.toLowerCase();
        if (lower.startsWith("https://")) {
            return "wss://" + base.substring(8);
        }
        if (lower.startsWith("http://")) {
            return "ws://" + base.substring(7);
        }
        return base;
    }

    /** 校验播放签名（on_play Hook 用） */
    public boolean verify(String streamId, String sign, String expire, String secret) {
        if (streamId == null || sign == null || expire == null || secret == null) {
            return false;
        }
        long expireAt;
        try {
            expireAt = Long.parseLong(expire);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expireAt < System.currentTimeMillis() / 1000) {
            return false;
        }
        return md5(streamId + expire + secret).equals(sign);
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
