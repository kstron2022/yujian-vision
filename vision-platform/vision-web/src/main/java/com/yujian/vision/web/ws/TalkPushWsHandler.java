package com.yujian.vision.web.ws;

import com.yujian.vision.service.media.service.TalkSessionService;
import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * 对讲音频推送 WS 端点（M3-B5 平台直推, docs/03 §5.x）:
 * ws(s)://{host}/ws/talk?talkId={id}&token={jwt}。二进制消息 = G.711A 裸字节(8kHz),
 * 服务端 RTP 打包(pt=8)后直发设备应答地址。HTTP 层认证同 /ws/api（AuthHandshakeInterceptor）。
 */
@Slf4j
@Component
public class TalkPushWsHandler extends BinaryWebSocketHandler {

    private final TalkSessionService talkSessions;

    public TalkPushWsHandler(TalkSessionService talkSessions) {
        this.talkSessions = talkSessions;
    }

    private static Long talkIdOf(WebSocketSession session) {
        try {
            // getUri() 可空: 只取一次存局部变量, 二次调用会被判为潜在 NPE（非 @NonNull 契约方法）
            var uri = session.getUri();
            String q = uri == null ? null : uri.getQuery();
            if (q == null) {
                return null;
            }
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "talkId".equals(kv.substring(0, eq))) {
                    return Long.parseLong(kv.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            log.warn("talkId 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long talkId = talkIdOf(session);
        if (talkId == null) {
            log.warn("对讲 WS 缺少 talkId, 关闭");
            throw new IllegalStateException("talkId required");
        }
        Long userId = (Long) session.getAttributes().get(WsSessionRegistry.ATTR_USER_ID);
        session.getAttributes().put("talkId", talkId);
        log.info("对讲 WS 已连接: talkId={} userId={}", talkId, userId);
    }

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session, @NonNull BinaryMessage message) {
        Object attr = session.getAttributes().get("talkId");
        if (attr == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        // 空帧/过短帧(头信息探测)直接忽略; onAudioFrame 内部按 STREAMING 态过滤
        if (bytes.length == 0) {
            return;
        }
        talkSessions.onAudioFrame((Long) attr, bytes, 0, bytes.length);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        Object attr = session.getAttributes().get("talkId");
        if (attr != null) {
            log.info("对讲 WS 已断开: talkId={} status={}", attr, status);
            talkSessions.onWsClosed((Long) attr);
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable ex) {
        log.debug("对讲 WS 传输异常(关闭即触发 onWsClosed): {}", ex.getMessage());
    }
}
