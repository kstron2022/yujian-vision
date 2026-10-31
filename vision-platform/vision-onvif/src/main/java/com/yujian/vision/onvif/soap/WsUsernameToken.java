package com.yujian.vision.onvif.soap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * ONVIF WS-UsernameToken 认证头（docs/13 §2.1）：
 * PasswordDigest = Base64( SHA1( nonce原始字节 + created + password ) )。
 * nonce 以 Base64 进报文、以原始字节进摘要——形态差异是联调高发坑，单测硬编码向量防回退。
 */
public final class WsUsernameToken {

    /** xsd:datetime UTC 带毫秒（ONVIF 设备普遍接受的 created 精度） */
    public static final DateTimeFormatter CREATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private WsUsernameToken() {
    }

    /** 一次调用的认证三元组（报文头用） */
    public record Token(String username, String passwordDigest, String nonceB64, String created) {
    }

    /** 生成一次调用的认证三元组（每次调用独立 nonce/created，不重放） */
    public static Token generate(String username, String password, Instant now) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);
        String created = CREATED_FMT.format(now);
        return new Token(username, digest(nonceB64, created, password), nonceB64, created);
    }

    /** 摘要计算（包内可见便于单测硬编码向量）：rawNonce + created + password 的 UTF-8 拼接 SHA1 */
    public static String digest(String nonceB64, String created, String password) {
        try {
            byte[] raw = Base64.getDecoder().decode(nonceB64);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(raw);
            md.update(created.getBytes(StandardCharsets.UTF_8));
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-1", e);
        }
    }

    /** wsse:Security 头 XML（s: 前缀与 {@code OnvifMessages} 的信封一致） */
    public static String headerXml(Token t) {
        return "<wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\""
                + NS_WSSE
                + "\" xmlns:wsu=\"" + NS_WSU + "\">"
                + "<wsse:UsernameToken>"
                + "<wsse:Username>" + xmlEscape(t.username()) + "</wsse:Username>"
                + "<wsse:Password Type=\"" + NS_WSS_PASSWORD_DIGEST + "\">" + t.passwordDigest() + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"" + NS_WSS_BASE64 + "\">" + t.nonceB64() + "</wsse:Nonce>"
                + "<wsu:Created>" + t.created() + "</wsu:Created>"
                + "</wsse:UsernameToken>"
                + "</wsse:Security>";
    }

    public static final String NS_WSSE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    public static final String NS_WSU =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    public static final String NS_WSS_PASSWORD_DIGEST =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";
    public static final String NS_WSS_BASE64 =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

    /** 最小 XML 文本转义（用户名等入报文的值） */
    public static String xmlEscape(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
