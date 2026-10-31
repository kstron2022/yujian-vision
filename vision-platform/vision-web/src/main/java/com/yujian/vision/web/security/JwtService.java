package com.yujian.vision.web.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 签发/校验（HS256, jjwt 0.12）
 * <p>claims: sub=username, jti=token 会话 id, uid=用户id, nick=昵称。
 * 吊销以 Redis 会话为准（OnlineSessionService），JWT 本身只做签名与硬过期校验。</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMs;

    public JwtService(JwtProperties props) {
        byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("vision.security.jwt.secret 长度需 >= 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMs = props.getExpireHours() * 3600_000L;
    }

    public String issue(Long userId, String username, String nickname, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .id(jti)
                .claim("uid", userId)
                .claim("nick", nickname)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(key)
                .compact();
    }

    /** 校验签名+硬过期, 失败抛 JwtException */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
