package com.yujian.vision.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

/**
 * JWT 签发/校验单测（评审补充：阶段A 此前无单测覆盖）
 */
class JwtServiceTest {

    private static final String SECRET_32B = "0123456789abcdef0123456789abcdef";

    private JwtService service(String secret, int expireHours) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret);
        p.setExpireHours(expireHours);
        return new JwtService(p);
    }

    @Test
    void issueThenParseRoundTrip() {
        JwtService svc = service(SECRET_32B, 24);
        String token = svc.issue(7L, "admin", "管理员", "jti-123");
        Claims claims = svc.parse(token);
        assertEquals("admin", claims.getSubject());
        assertEquals("jti-123", claims.getId());
        assertEquals(7L, ((Number) claims.get("uid")).longValue());
        assertEquals("管理员", claims.get("nick", String.class));
    }

    @Test
    void tamperedTokenRejected() {
        JwtService svc = service(SECRET_32B, 24);
        String token = svc.issue(1L, "admin", "", "t-1");
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThrows(JwtException.class, () -> svc.parse(tampered));
    }

    @Test
    void wrongKeySignatureRejected() {
        String token = service(SECRET_32B, 24).issue(1L, "admin", "", "t-2");
        JwtService other = service("ffffffffffffffffffffffffffffffff", 24);
        assertThrows(JwtException.class, () -> other.parse(token));
    }

    @Test
    void expiredTokenRejected() {
        JwtService svc = service(SECRET_32B, -1);
        String token = svc.issue(1L, "admin", "", "t-3");
        assertThrows(JwtException.class, () -> svc.parse(token));
    }

    @Test
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> service("short-secret", 24));
    }
}
