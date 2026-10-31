package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.ha.HaRuntimeService;
import com.yujian.vision.service.media.service.MediaSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HA 内部管理口（M3-B6 阶段2, 鉴权形制克隆自 AlertWebhookController）：keepalived notify 脚本与
 * 演练脚本经此拉起/停用本进程 SIP 栈（promote/demote）并查询运行态。
 *
 * <p>网关层 permitAll（SecurityConfig）+ 控制器内 X-Vision-Ha-Token 恒定时间比对
 * （MessageDigest.isEqual 防时序侧信道），不符一律 401。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ha/internal")
public class HaInternalController {

    /** notify 脚本与演练脚本必须携带的令牌请求头 */
    public static final String TOKEN_HEADER = "X-Vision-Ha-Token";

    private final HaRuntimeService ha;
    /** 备台委托建流的落点（M3-B7）: 只有活跃实例（持有 SIP 栈）能真正建流 */
    private final MediaSessionService sessions;
    private final byte[] expectedToken;

    public HaInternalController(HaRuntimeService ha, MediaSessionService sessions,
            @Value("${vision.ha.token}") String token) {
        this.ha = ha;
        this.sessions = sessions;
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    private boolean tokenValid(String token) {
        return token != null && !token.isBlank()
                && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }

    /** 接管: 起全部配置栈 + 写租约, 本进程升为活跃身份 */
    @PostMapping("/sip-stack/start")
    public ResponseEntity<R<Map<String, Object>>> start(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!tokenValid(token)) {
            log.warn("HA 内部口鉴权失败: sip-stack/start");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
        }
        return ResponseEntity.ok(R.ok(ha.promote()));
    }

    /** 回切: 停栈 + 删自持租约, 本进程降为备用身份 */
    @PostMapping("/sip-stack/stop")
    public ResponseEntity<R<Map<String, Object>>> stop(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!tokenValid(token)) {
            log.warn("HA 内部口鉴权失败: sip-stack/stop");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
        }
        return ResponseEntity.ok(R.ok(ha.demote()));
    }

    /**
     * 备台委托建流（M3-B7）: 备台收到 ZLM on_stream_not_found 但无 SIP 栈, 请活跃实例代为建流。
     *
     * <p>与另两个口不同, 本口是"业务动作"而非栈控制: 建流必须在活跃实例上跑（它才有 SIP 栈与
     * 节点调度入口）。载体为 {@code {streamId, serverId, streamProfile}}——serverId 是发起请求的
     * 节点, 用于把流 pin 到播放器真正在等的那个节点（见 MediaNodeScheduler.select(preferred,..)）。</p>
     *
     * <p>失败返回码非 0 且不抛: 调用方（HaPeerClient）只记日志, 播放器会按 ZLM maxStreamWaitMS
     * 超时并重试, 届时再触发一轮 hook 自愈。</p>
     */
    @PostMapping("/on-demand")
    public ResponseEntity<R<Map<String, Object>>> onDemand(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!tokenValid(token)) {
            log.warn("HA 内部口鉴权失败: on-demand");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
        }
        String streamId = str(body, "streamId");
        String serverId = str(body, "serverId");
        int profile = intOf(body, "streamProfile", 1);
        try {
            MediaSessionService.PlayResult r = sessions.playOnDemand(streamId, serverId, profile);
            log.info("HA 委托建流完成: stream={} node={} status={} server={}",
                    streamId, serverId, r.status(), r.serverId());
            return ResponseEntity.ok(R.ok(Map.of("streamId", streamId,
                    "status", String.valueOf(r.status()), "serverId", String.valueOf(r.serverId()))));
        } catch (Exception e) {
            log.warn("HA 委托建流失败: stream={} node={} err={}", streamId, serverId, e.getMessage());
            return ResponseEntity.ok(R.fail(ErrorCode.ZLM_API_FAILED, e.getMessage()));
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static int intOf(Map<String, Object> map, String key, int dft) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dft : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    /** 运行态观测: 角色/租约持有者/在跑栈数/对端 */
    @GetMapping("/status")
    public ResponseEntity<R<Map<String, Object>>> status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!tokenValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
        }
        return ResponseEntity.ok(R.ok(ha.status()));
    }
}
