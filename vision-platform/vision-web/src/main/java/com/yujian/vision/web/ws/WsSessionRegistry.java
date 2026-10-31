package com.yujian.vision.web.ws;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 连接注册表：sessionId ↔ (userId, tokenId)。
 * 强制下线/改密/停用账号时按 tokenId 关闭对应全部 WS 连接。
 */
@Component
public class WsSessionRegistry {

    public static final String ATTR_USER_ID = "ws.userId";
    public static final String ATTR_TOKEN_ID = "ws.tokenId";

    private final ConcurrentHashMap<String, WebSocketSession> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> tokenToSessions = new ConcurrentHashMap<>();

    public void bind(WebSocketSession session, Long userId, String tokenId) {
        byId.put(session.getId(), session);
        session.getAttributes().put(ATTR_USER_ID, userId);
        session.getAttributes().put(ATTR_TOKEN_ID, tokenId);
        tokenToSessions.computeIfAbsent(tokenId, k -> new CopyOnWriteArraySet<>()).add(session.getId());
    }

    public void unbind(String sessionId, String tokenId) {
        byId.remove(sessionId);
        Set<String> sids = tokenToSessions.get(tokenId);
        if (sids != null) {
            sids.remove(sessionId);
        }
    }

    /** 当前全部在线连接（广播用） */
    public java.util.Collection<WebSocketSession> allSessions() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    /** 关闭该 token 的全部 WS 连接（强制下线/改密联动） */
    public void closeByToken(String tokenId) {
        Set<String> sids = tokenToSessions.remove(tokenId);
        if (sids == null) {
            return;
        }
        for (String sid : sids) {
            WebSocketSession ws = byId.remove(sid);
            try {
                if (ws != null && ws.isOpen()) {
                    ws.close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
                }
            } catch (Exception ignored) {
                // 连接可能已断开
            }
        }
    }
}
