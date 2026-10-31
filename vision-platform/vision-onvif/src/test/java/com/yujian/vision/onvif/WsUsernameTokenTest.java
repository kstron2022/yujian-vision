package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.onvif.soap.WsUsernameToken;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WS-UsernameToken 摘要硬编码向量（docs/13 §7：认证是 M4 联调第一坑，向量回退=实现回退）。
 * 期望值由独立实现计算（Python: base64(sha1(b64decode(nonce)+created+password))）。
 */
class WsUsernameTokenTest {

    @Test
    @DisplayName("PasswordDigest 硬编码向量: rawNonce+created+password 的 SHA1")
    void digestKnownVector() {
        String digest = WsUsernameToken.digest("LKqI6G/AikKCQrN0zqZFlg==",
                "2026-09-18T03:04:05.123Z", "userpassword");
        assertEquals("dH7uIBUQv5maeEGArPQ1yQGXV6M=", digest);
    }

    @Test
    @DisplayName("generate: 每次独立 nonce, created 为 UTC 毫秒格式")
    void generateShape() {
        WsUsernameToken.Token t = WsUsernameToken.generate("u1", "p1", Instant.parse("2026-09-18T03:04:05.123Z"));
        assertEquals("2026-09-18T03:04:05.123Z", t.created());
        assertEquals("u1", t.username());
        assertTrue(t.nonceB64().length() >= 20, "16 字节 nonce 的 base64 长度");
        assertFalse(t.passwordDigest().isEmpty());
    }

    @Test
    @DisplayName("headerXml: 结构完整且含类型常量, 用户名转义")
    void headerXmlShape() {
        WsUsernameToken.Token t = new WsUsernameToken.Token("us<r>&", "DIGEST", "NONCE", "CREATED");
        String xml = WsUsernameToken.headerXml(t);
        assertTrue(xml.startsWith("<wsse:Security"));
        assertTrue(xml.contains(">us&lt;r&gt;&amp;<"));
        assertTrue(xml.contains("Password Type="));
        assertTrue(xml.contains(">DIGEST<"));
        assertTrue(xml.contains(">NONCE<"));
        assertTrue(xml.contains(">CREATED<"));
    }
}
