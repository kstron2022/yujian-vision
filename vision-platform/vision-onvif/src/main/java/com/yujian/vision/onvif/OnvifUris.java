package com.yujian.vision.onvif;

/**
 * ONVIF 流地址归一化（M4-B2, docs/13 §5.1/§8）：GetStreamUri 返回的地址不能直接喂 ZLM——
 * ①可能不带凭据或带其他账号的凭据, ZLM 拉流需在 URL 内嵌账密（RTSP Digest 由 ZLM 自做）;
 * ②URI 主机可能不是可达地址（多网卡/NAT/回环形态）, 手填场景下手填 IP 刚被 SOAP 探活打通,
 * 是已知可达地址 —— 主机不一致时按手填 IP 重组, 端口/路径/查询保留（相机 RTSP 自定义端口场景）。
 * 纯函数, 单测覆盖。
 */
public final class OnvifUris {

    private OnvifUris() {
    }

    /**
     * 设备返回的流地址 → 平台侧可拉流的 RTSP 地址。
     *
     * @param deviceUri  GetStreamUri 返回的原始地址（可能带/不带 userinfo）
     * @param username   ONVIF 用户名（原样嵌入, 特殊字符百分号编码）
     * @param password   ONVIF 密码（同上）
     * @param fallbackIp 手填设备 IP（主机不一致时替换目标; 空则保留 URI 主机）
     * @throws IllegalArgumentException 地址为空/非 RTSP/缺主机（调用方转业务错误）
     */
    public static String normalizePullUrl(String deviceUri, String username, String password,
            String fallbackIp) {
        if (deviceUri == null || deviceUri.isBlank()) {
            throw new IllegalArgumentException("设备未返回流地址");
        }
        String u = deviceUri.trim();
        if (!u.regionMatches(true, 0, "rtsp://", 0, 7)) {
            throw new IllegalArgumentException("仅支持 RTSP 流地址: " + brief(u));
        }
        String rest = u.substring("rtsp://".length());
        String pathQuery = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            pathQuery = stripControlChars(rest.substring(slash));
            rest = rest.substring(0, slash);
        }
        // rest = [userinfo@]host[:port]; lastIndexOf('@') 容错密码里带 @ 的形态
        String authority = rest;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        String host = authority;
        int port = -1;
        // 单冒号才视作端口分隔(IPv6 多冒号保守整体保留; 手填场景为 IPv4)
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignore) {
                // 非数字尾巴视作 host 的一部分, 保守保留原样
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("流地址缺主机: " + brief(u));
        }
        if (fallbackIp != null && !fallbackIp.isBlank() && !host.equalsIgnoreCase(fallbackIp.trim())) {
            host = fallbackIp.trim();
        }
        return "rtsp://" + encodeUserinfo(username) + ":" + encodeUserinfo(password)
                + "@" + host + (port > 0 ? ":" + port : "") + pathQuery;
    }

    /** userinfo 百分号编码（RFC 3986 unreserved 之外全编码; 空密码允许） */
    static String encodeUserinfo(String v) {
        String s = v == null ? "" : v;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    /** 剔除控制字符（评审 B3-P3-6: 设备 URI 内嵌 CRLF 防进 ZLM 出站 RTSP 请求行） */
    private static String stripControlChars(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 日志/异常里的地址摘述（掐掉 userinfo 防密码落日志, 保留主机端口结构; 先掩码后截断,
     *  超长 userinfo 时不因 '@' 被截断而漏掩码, 评审 B3-P3-6） */
    private static String brief(String uri) {
        String s = uri;
        int at = s.lastIndexOf('@');
        int scheme = s.indexOf("://");
        if (at > scheme && scheme >= 0) {
            s = s.substring(0, scheme + 3) + "***" + s.substring(at);
        }
        return s.length() > 96 ? s.substring(0, 96) + "..." : s;
    }
}
