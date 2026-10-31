package com.yujian.vision.web.controller;

import com.yujian.vision.media.zlm.ZlmCodecs;
import com.yujian.vision.service.media.service.HookService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ZLM Hook 统一入口（docs/04 §2）：POST /api/hooks/{hookName}?secret=。
 * 同步快速响应（on_publish/on_play 裁决）, 其余异步处理。
 */
@Slf4j
@RestController
@RequestMapping("/api/hooks")
@RequiredArgsConstructor
public class MediaHookController {

    private final HookService hookService;

    @PostMapping("/{name}")
    public Map<String, Object> handle(@PathVariable String name,
            @RequestParam(required = false) String secret,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!hookService.secretValid(secret)) {
            return Map.of("code", 403, "msg", "invalid secret");
        }
        String stream = str(body, "stream");
        switch (name) {
            case "on_publish" -> {
                boolean allow = hookService.onPublish(str(body, "app"), stream);
                return Map.of("code", allow ? 0 : -1);
            }
            case "on_play" -> {
                // F2: params 原样下传(真实 ZLM 为查询串字符串), 由 HookService 统一解析;
                // B6 F1: 媒体流在哪个节点就按哪个节点的密钥验签(每节点密钥独立)
                boolean allow = hookService.onPlay(str(body, "app"), stream,
                        body != null ? body.get("params") : null, str(body, "mediaServerId"));
                return Map.of("code", allow ? 0 : -1);
            }
            case "on_stream_changed" -> hookService.streamChanged(stream,
                    Boolean.parseBoolean(String.valueOf(body.get("regist"))),
                    ZlmCodecs.extractVideoCodec(body == null ? null : body.get("tracks")));
            case "on_record_mp4" -> hookService.recordMp4(body);
            case "on_stream_none_reader" -> hookService.noneReader(stream);
            // M3-B7: 播放器拉的流不存在 → 平台在 ZLM 等待窗口(maxStreamWaitMS)内按需建流。
            // 必须快速返回(不等建流, 建流在 HookService 独立池中异步跑): ZLM 响应码不参与裁决
            case "on_stream_not_found" -> hookService.streamNotFound(str(body, "app"), stream,
                    body != null ? body.get("params") : null, str(body, "mediaServerId"));
            case "on_rtp_server_timeout" -> hookService.rtpServerTimeout(str(body, "stream_id"));
            // B2: 转推发送端停止(上级断链等异常, ZLM 已自停发送) → 级联侧回收在途转推
            case "on_send_rtp_stopped" -> hookService.sendRtpStopped(str(body, "app"), stream,
                    str(body, "ssrc"));
            case "on_server_keepalive" -> hookService.serverKeepalive(str(body, "mediaServerId"));
            case "on_server_started" -> hookService.serverStarted(str(body, "mediaServerId"));
            default -> log.debug("未处理 Hook: {}", name);
        }
        return Map.of("code", 0);
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v != null ? String.valueOf(v) : null;
    }
}
