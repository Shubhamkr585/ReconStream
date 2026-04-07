package com.reconstream.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository,
                          RefreshTokenRepository refreshTokenRepository,
                          JwtTokenService jwtTokenService,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    public static String hashToken(String token) {
        if (token == null) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        log.info("Login attempt for email: {}", request != null ? request.email() : "null request");
        User user = userRepository.findByEmail(request != null ? request.email() : null)
                .orElse(null);

        if (user == null) {
            log.warn("User not found for email: {}", request != null ? request.email() : "null");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Invalid email or password"));
        }

        boolean matches = passwordEncoder.matches(request.password(), user.getPasswordHash());
        log.info("Password matches for {}: {}", request.email(), matches);

        if (!matches) {
            log.warn("Password mismatch for email: {}", request.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Invalid email or password"));
        }

        String accessToken = jwtTokenService.generateAccessToken(user);
        String refreshToken = jwtTokenService.generateRefreshToken(user);

        // Store SHA-256 hash of refresh token in DB for revocation tracking
        RefreshToken tokenEntity = new RefreshToken(
                UUID.randomUUID(),
                user.getId(),
                hashToken(refreshToken),
                Instant.now().plus(jwtTokenService.getRefreshTokenValidityDays(), ChronoUnit.DAYS)
        );
        refreshTokenRepository.save(tokenEntity);

        ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(accessToken, false);
        ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(refreshToken, false);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(new UserResponse(user.getId(), user.getEmail(), user.getRole().name()));
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = extractCookie(request, CookieUtil.REFRESH_TOKEN_COOKIE);
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Refresh token cookie missing"));
        }

        String hashed = hashToken(refreshToken);
        RefreshToken tokenEntity = refreshTokenRepository.findByTokenHash(hashed).orElse(null);
        if (tokenEntity == null || tokenEntity.isRevoked() || tokenEntity.getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, CookieUtil.clearAccessTokenCookie(false).toString())
                    .header(HttpHeaders.SET_COOKIE, CookieUtil.clearRefreshTokenCookie(false).toString())
                    .body(Map.of("error", "Unauthorized", "message", "Refresh token invalid, expired, or revoked"));
        }

        User user = userRepository.findById(tokenEntity.getUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "User not found"));
        }

        // Token rotation: Revoke old refresh token, issue new one
        tokenEntity.setRevoked(true);
        refreshTokenRepository.save(tokenEntity);

        String newAccessToken = jwtTokenService.generateAccessToken(user);
        String newRefreshToken = jwtTokenService.generateRefreshToken(user);

        RefreshToken newTokenEntity = new RefreshToken(
                UUID.randomUUID(),
                user.getId(),
                hashToken(newRefreshToken),
                Instant.now().plus(jwtTokenService.getRefreshTokenValidityDays(), ChronoUnit.DAYS)
        );
        refreshTokenRepository.save(newTokenEntity);

        ResponseCookie accessCookie = CookieUtil.createAccessTokenCookie(newAccessToken, false);
        ResponseCookie refreshCookie = CookieUtil.createRefreshTokenCookie(newRefreshToken, false);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(new UserResponse(user.getId(), user.getEmail(), user.getRole().name()));
    }

    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String refreshToken = extractCookie(request, CookieUtil.REFRESH_TOKEN_COOKIE);
        if (refreshToken != null) {
            String hashed = hashToken(refreshToken);
            refreshTokenRepository.findByTokenHash(hashed).ifPresent(rt -> {
                rt.setRevoked(true);
                refreshTokenRepository.save(rt);
            });
        }

        ResponseCookie clearAccess = CookieUtil.clearAccessTokenCookie(false);
        ResponseCookie clearRefresh = CookieUtil.clearRefreshTokenCookie(false);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearAccess.toString())
                .header(HttpHeaders.SET_COOKIE, clearRefresh.toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Not authenticated"));
        }

        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "User not found"));
        }

        return ResponseEntity.ok(new UserResponse(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private String extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public record LoginRequest(String email, String password) {}
    public record UserResponse(UUID id, String email, String role) {}
}
