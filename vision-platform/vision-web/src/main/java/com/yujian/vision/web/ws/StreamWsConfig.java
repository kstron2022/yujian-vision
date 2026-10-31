package com.yujian.vision.web.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WS 端点：ws://{host}:8080/ws/api?token=<jwt>（docs/03 §10）。
 * HTTP 层由 JwtAuthenticationFilter 用 ?token= 认证（未认证 401 拒绝握手）；
 * 握手成功后绑定 userId/tokenId ↔ 连接，强制下线时按 tokenId 关闭连接。
 */
@Configuration
@EnableWebSocket
public class StreamWsConfig implements WebSocketConfigurer {

    private final WsSessionRegistry registry;
    private final TalkPushWsHandler talkPushWsHandler;

    public StreamWsConfig(WsSessionRegistry registry, TalkPushWsHandler talkPushWsHandler) {
        this.registry = registry;
        this.talkPushWsHandler = talkPushWsHandler;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry0) {
        registry0.addHandler(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(@NonNull WebSocketSession session) {
                Long userId = (Long) session.getAttributes().get(WsSessionRegistry.ATTR_USER_ID);
                String tokenId = (String) session.getAttributes().get(WsSessionRegistry.ATTR_TOKEN_ID);
                if (userId == null || tokenId == null) {
                    throw new IllegalStateException("WS 握手缺少认证属性");
                }
                registry.bind(session, userId, tokenId);
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
                String tokenId = (String) session.getAttributes().get(WsSessionRegistry.ATTR_TOKEN_ID);
                registry.unbind(session.getId(), tokenId);
            }
        }, "/ws/api")
                .addInterceptors(new AuthHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        // M3-B5: 对讲音频推送（二进制 G.711A → 平台 RTP 直发设备）
        registry0.addHandler(talkPushWsHandler, "/ws/talk")
                .addInterceptors(new AuthHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }
}
