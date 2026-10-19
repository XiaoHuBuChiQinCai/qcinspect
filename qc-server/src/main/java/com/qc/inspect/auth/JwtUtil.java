package com.qc.inspect.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtUtil(@Value("${qc.jwt.secret}") String secret,
                   @Value("${qc.jwt.expire-minutes:480}") long expireMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("qc.jwt.secret 至少需要 32 字节，当前 " + bytes.length
                    + " 字节。生产环境请用环境变量 JWT_SECRET 覆盖。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMinutes = expireMinutes;
    }

    public String issue(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("realName", user.realName())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /** 解析失败返回 null，交给过滤器决定是否放行 */
    public LoginUser parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new LoginUser(
                    Long.valueOf(c.getSubject()),
                    c.get("username", String.class),
                    c.get("realName", String.class),
                    c.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long expireMinutes() {
        return expireMinutes;
    }
}
