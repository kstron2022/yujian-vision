package com.yujian.vision.web.ws;

import com.yujian.vision.web.security.LoginUser;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WS 握手拦截器：HTTP 层 JwtAuthenticationFilter 已用 ?token= 完成认证，
 * 此处把身份（userId/tokenId）写入 WS session attributes 供注册表绑定。
 * （评审修复 F-WS1：此前无人在 afterConnectionEstablished 之前写入身份属性，
 *  导致每个连接都会因属性缺失抛异常断开。）
 */
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            attributes.put(WsSessionRegistry.ATTR_USER_ID, user.userId());
            attributes.put(WsSessionRegistry.ATTR_TOKEN_ID, user.tokenId());
            return true;
        }
        // 兜底：正常情况下未认证握手在过滤链已被 401
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
            @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
        // no-op
    }
}
