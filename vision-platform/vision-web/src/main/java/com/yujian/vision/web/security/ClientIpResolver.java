package com.yujian.vision.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 客户端真实 IP 解析（防 XFF 伪造, docs/10 §1）：
 * 仅当直连地址（remoteAddr）命中可信代理且请求携带 X-Forwarded-For 时才采信 XFF, 否则一律取 remoteAddr。
 * <p>采信方式为"从 XFF 最右端向左遍历, 跳过可信代理条目, 返回第一个非可信地址"——
 * 匹配 nginx {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for} 的<b>追加式</b>语义：
 * 客户端伪造的首段会被原样保留在链首, 取首段等于伪造仍生效; 只有从右向左回溯到第一个非可信地址才是真实来源。</p>
 * XFF 可被客户端任意伪造, 未经可信代理校验直接采信会绕过限流/登录防爆破。
 * <p>先例 {@link ClientIps} 保留用于审计/日志等非安全决策场景。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final TrustedProxyProperties props;

    /**
     * 解析客户端 IP：直连地址为可信代理且 XFF 非空 → 从 XFF 最右端向左遍历, 跳过可信代理条目,
     * 返回第一个非可信地址（trim）; XFF 为空 / 直连地址不可信 / 全部条目均为可信代理 → remoteAddr。
     * 追加式 XFF（nginx $proxy_add_x_forwarded_for）下客户端伪造的首段仅是链上最早的代理跳,
     * 不参与解析。本机/回环反代场景把 127.0.0.1、::1 配进 trusted-proxies 即可（精确串比对）。
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String[] parts = xff.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (candidate.isEmpty() || isTrustedProxy(candidate)) {
                continue;
            }
            return candidate;
        }
        return remoteAddr;
    }

    /** 直连地址是否可信代理：精确比对（含 "::1" 等 IPv6 字面量）或 IPv4 CIDR; 列表为空 → 一律不可信 */
    boolean isTrustedProxy(String remoteAddr) {
        List<String> trusted = props.getTrustedProxies();
        if (trusted == null || trusted.isEmpty() || remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.trim();
            if (!e.contains("/")) {
                // 精确串比对（"::1" 等 IPv6 字面量按字符串匹配）
                if (e.equals(remoteAddr)) {
                    return true;
                }
                continue;
            }
            int slash = e.indexOf('/');
            // IPv6 CIDR 不支持: 网络段含 ":" 时忽略并 debug 提示（精确 IPv6 字面量仍可用）
            if (e.substring(0, slash).contains(":")) {
                log.debug("trusted-proxies 不支持 IPv6 CIDR, 已忽略: {}", e);
                continue;
            }
            if (matchesIpv4Cidr(e.substring(0, slash), e.substring(slash + 1), remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IPv4 CIDR 匹配（掩码按 int 计算）。网络段/候选地址非 IPv4（含 IPv6）或前缀非法 → false。
     * 包私有静态, 便于单测。
     */
    static boolean matchesIpv4Cidr(String network, String prefixLen, String candidate) {
        Integer net = ipv4ToInt(network);
        Integer ip = ipv4ToInt(candidate);
        Integer prefix = parsePrefixLen(prefixLen);
        if (net == null || ip == null || prefix == null) {
            return false;
        }
        if (prefix == 0) {
            // 0.0.0.0/0 覆盖全网段
            return true;
        }
        int mask = 0xFFFFFFFF << (32 - prefix);
        return (net & mask) == (ip & mask);
    }

    /** 解析 CIDR 前缀长度, 非法返回 null */
    static Integer parsePrefixLen(String prefixLen) {
        try {
            int p = Integer.parseInt(prefixLen.trim());
            return p >= 0 && p <= 32 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 点分 IPv4 → int（按段移位累加）, 非法（含 IPv6/空串/段超 255）返回 null。
     * 返回值按无符号 32 位理解（首段 ≥128 时为负 int, 故用 null 而非 -1 表示非法）。
     */
    static Integer ipv4ToInt(String ip) {
        if (ip == null) {
            return null;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int value = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int octet;
            try {
                octet = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            value = (value << 8) | octet;
        }
        return value;
    }
}
