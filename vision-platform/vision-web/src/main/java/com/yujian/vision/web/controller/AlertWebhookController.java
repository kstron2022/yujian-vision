package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.monitor.AlertNoticeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertmanager 告警 webhook 入口（docs/09 M2 #8）：POST /api/alerts/webhook。
 *
 * <p>网关层 permitAll（SecurityConfig, 与 /actuator/prometheus 同一监控旁路段）, 鉴权收在控制器内：
 * 请求头 {@code X-Vision-Alert-Token} 与配置 {@code vision.security.alert-webhook-token} 做恒定时间
 * 比对（{@link MessageDigest#isEqual}, 参考 HookService.secretValid, 防时序侧信道）, 不匹配一律 401。</p>
 *
 * <p>body 以 String 接收（不进 Jackson 反序列化）——畸形报文由 AlertNoticeService 自吞并告警日志,
 * 不会以 400 形式反馈给 Alertmanager（其只关心 2xx, 重试无意义）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/alerts")
public class AlertWebhookController {

    /** Alertmanager 侧 webhook_config.http_config.headers 需携带同名请求头 */
    public static final String TOKEN_HEADER = "X-Vision-Alert-Token";
    /** 请求体上限: Alertmanager 报文实际仅几 KB, 限制防内网侧大 body 内存压力（评审 B4-#7） */
    static final long MAX_BODY_BYTES = 262_144;

    private final AlertNoticeService alerts;
    private final byte[] expectedToken;

    public AlertWebhookController(AlertNoticeService alerts,
            @Value("${vision.security.alert-webhook-token}") String token) {
        this.alerts = alerts;
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/webhook")
    public ResponseEntity<R<Void>> handle(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody(required = false) String body) {
        String len = request.getHeader("Content-Length");
        long bytes = -1;
        if (len != null) {
            try {
                bytes = Long.parseLong(len.trim());
            } catch (NumberFormatException ignored) {
                // 非法长度头按未知处理, 交给后续读取
            }
        }
        if (bytes > MAX_BODY_BYTES) {
            log.warn("告警 webhook 报文超限拒绝: {} bytes > {}", bytes, MAX_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(R.fail(ErrorCode.BAD_REQUEST));
        }
        boolean ok = token != null && !token.isBlank()
                && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            log.warn("告警 webhook 鉴权失败: hasToken={} bodyBytes={}", token != null,
                    body == null ? 0 : body.length());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
        }
        alerts.handle(body);
        return ResponseEntity.ok(R.ok());
    }
}
