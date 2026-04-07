package com.reconstream.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final SecretKey key;
    private final long accessTokenValidityMinutes;
    private final long refreshTokenValidityDays;

    public JwtTokenService(
            @Value("${reconstream.security.jwt.secret:reconstream-jwt-super-secret-key-that-is-at-least-256-bits-long}") String secret,
            @Value("${reconstream.security.jwt.access-token-validity-minutes:15}") long accessTokenValidityMinutes,
            @Value("${reconstream.security.jwt.refresh-token-validity-days:7}") long refreshTokenValidityDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMinutes = accessTokenValidityMinutes;
        this.refreshTokenValidityDays = refreshTokenValidityDays;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenValidityMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenValidityDays, ChronoUnit.DAYS);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("type", "REFRESH")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Claims parseAndValidateToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String getEmail(Claims claims) {
        return claims.get("email", String.class);
    }

    public Role getRole(Claims claims) {
        String roleStr = claims.get("role", String.class);
        return roleStr != null ? Role.valueOf(roleStr) : null;
    }

    public long getRefreshTokenValidityDays() {
        return refreshTokenValidityDays;
    }
}
