package com.yujian.vision.gb28181.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP Digest 摘要认证（RFC 2617，docs/05 §1 注册时序）。
 * 纯算法工具：nonce 的签发与一次性校验由服务层（Redis）负责。
 */
public final class DigestAuthenticator {

    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^\",\\s]+))");

    private DigestAuthenticator() {
    }

    /**
     * 构建 401 挑战的 WWW-Authenticate 头值。
     * 不声明 qop（多数国标设备不支持 qop 模式），若设备带了 qop 应答也兼容校验。
     */
    public static String createChallenge(String realm, String nonce) {
        return "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", opaque=\"" + md5Hex(nonce).substring(0, 16)
                + "\", stale=FALSE";
    }

    /**
     * 校验 REGISTER Authorization 头中的 response 摘要
     *
     * @param authHeader Authorization 头原文
     * @param method     请求方法（REGISTER）
     * @param password   设备接入密码（明文参与摘要计算）
     * @return true = 校验通过
     */
    public static boolean verify(String authHeader, String method, String password) {
        Map<String, String> params = parseParams(authHeader);
        String username = params.get("username");
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        String uri = params.get("uri");
        String response = params.get("response");
        if (username == null || realm == null || nonce == null || uri == null || response == null) {
            return false;
        }
        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        String qop = params.get("qop");
        String expected;
        if (qop != null && !qop.isBlank()) {
            String nc = params.getOrDefault("nc", "");
            String cnonce = params.getOrDefault("cnonce", "");
            expected = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            expected = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }
        return expected.equalsIgnoreCase(response);
    }

    /**
     * UAC 侧：按对端 401 挑战计算 Digest response 并构建 Authorization 头
     * （本平台作为级联下级向上级注册, docs/08 §2.1）。摘要公式与 {@link #verify} 对称。
     *
     * @param challenge 对端 401 应答的 WWW-Authenticate 头原文
     * @param method    请求方法（REGISTER）
     * @param uri       请求 URI（如 sip:{上级编码}@{上级域}）
     * @param username  本平台在对端侧的国标编码
     * @param password  级联密码（明文参与摘要计算）
     * @return Authorization 头值（含 "Digest" 前缀, 调用方直接放进 SipMessageContext.authorization）
     */
    public static String buildAuthorization(String challenge, String method, String uri,
                                            String username, String password) {
        Map<String, String> params = parseParams(challenge);
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        if (realm == null || nonce == null) {
            throw new IllegalArgumentException("401 挑战缺少 realm/nonce: " + challenge);
        }
        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        StringBuilder header = new StringBuilder("Digest username=\"").append(username)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", response=\"");
        String qop = params.get("qop");
        if (qop != null && !qop.isBlank()) {
            // 对端声明 qop 时取 auth（RFC 2617）；nc/cnonce 本地生成, 与 verify 的校验式对称
            String nc = "00000001";
            String cnonce = md5Hex(username + ":" + nonce).substring(0, 16);
            String response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            header.append(response)
                    .append("\", qop=auth, nc=").append(nc)
                    .append(", cnonce=\"").append(cnonce).append("\"");
        } else {
            header.append(md5Hex(ha1 + ":" + nonce + ":" + ha2)).append("\"");
        }
        String opaque = params.get("opaque");
        if (opaque != null) {
            header.append(", opaque=\"").append(opaque).append("\"");
        }
        return header.toString();
    }

    /** 解析 Digest 参数（兼容带引号与不带引号两种形态） */
    public static Map<String, String> parseParams(String authHeader) {
        Map<String, String> params = new HashMap<>();
        if (authHeader == null) {
            return params;
        }
        int idx = authHeader.indexOf("Digest");
        String body = idx >= 0 ? authHeader.substring(idx + "Digest".length()) : authHeader;
        Matcher matcher = PARAM.matcher(body);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (value != null) {
                params.put(key, value);
            }
        }
        return params;
    }

    public static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
