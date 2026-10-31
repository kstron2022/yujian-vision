package com.yujian.vision.web.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析：优先 X-Forwarded-For 首段（Nginx 终结后场景, docs/10 §1）
 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String of(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
