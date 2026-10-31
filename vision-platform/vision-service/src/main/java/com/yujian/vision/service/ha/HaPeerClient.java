package com.yujian.vision.service.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 备台 → 活跃实例的内部调用通道（M3-B6 阶段2 是"备台被转发", 本类是其反向: 服务端发起）。
 *
 * <p>触发场景（M3-B7 按需点播）: 242 形态下 ZLM hook 经 VIP 常态落备台 B, 而备台无 SIP 栈
 * ——播放器拉一个不存在的流时, 只能请活跃实例 A 代为建流。载荷走 /api/ha/internal/*
 * （X-Vision-Ha-Token 恒定时间比对, 见 HaInternalController）。</p>
 *
 * <p><b>为何不会形成转发回环</b>: 回环需要"对端再把请求转回来", 而 HaForwardFilter 的
 * forwardPrefixes 不含 /api/ha（内部口只在进程内被 HaPeerClient 直达, 从不经备台转发路径）;
 * 且 {@link #isLocal()} 保证本方非备台时根本不发请求。</p>
 *
 * <p>未启用 HA 或本方即活跃身份时 {@link #isLocal()} 为真, 调用方本地执行——单机部署零变化。</p>
 */
@Slf4j
@Component
public class HaPeerClient {

    private final HaProperties props;
    private final HaRuntimeService ha;
    private final ObjectMapper om;
    private final HttpClient client;

    public HaPeerClient(HaProperties props, HaRuntimeService ha, ObjectMapper om) {
        this.props = props;
        this.ha = ha;
        this.om = om;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** 本方是否应直接处理（未启用 HA 或本方为活跃身份）; false = 须委托对端 */
    public boolean isLocal() {
        return !props.isEnabled() || !ha.isStandby();
    }

    /**
     * 请活跃实例代为建流。返回是否投递成功——失败只告警不抛: ZLM 侧播放器请求按 maxStreamWaitMS
     * 超时, 前端重试会再触发一次 on_stream_not_found, 无需在此重试放大故障。
     */
    public boolean requestOnDemand(String streamId, String serverId, int streamProfile) {
        if (isLocal()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("streamId", streamId);
        body.put("serverId", serverId);
        body.put("streamProfile", streamProfile);
        String peer = props.getPeerBaseUrl();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(peer + "/api/ha/internal/on-demand"))
                    .timeout(Duration.ofMillis(props.getForwardTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-Vision-Ha-Token", props.getToken())
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String text = resp.body() == null ? "" : resp.body();
            JsonNode root = text.isBlank() ? null : om.readTree(text);
            boolean ok = resp.statusCode() == 200 && root != null && root.path("code").asInt(-1) == 0;
            if (ok) {
                log.info("备台委托活跃实例按需建流: stream={} node={} peer={}", streamId, serverId, peer);
            } else {
                log.error("备台委托建流失败: stream={} peer={} HTTP {} body={}", streamId, peer,
                        resp.statusCode(), text.length() > 300 ? text.substring(0, 300) : text);
            }
            return ok;
        } catch (Exception e) {
            log.error("备台委托建流异常(对端不可达?): stream={} peer={} err={}", streamId, peer, e.getMessage());
            return false;
        }
    }
}
