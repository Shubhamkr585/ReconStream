package com.reconstream.security;

import org.springframework.http.ResponseCookie;

public class CookieUtil {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    public static ResponseCookie createAccessTokenCookie(String token, boolean secure) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(15 * 60) // 15 minutes
                .build();
    }

    public static ResponseCookie createRefreshTokenCookie(String token, boolean secure) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(7 * 24 * 60 * 60) // 7 days
                .build();
    }

    public static ResponseCookie clearAccessTokenCookie(boolean secure) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    public static ResponseCookie clearRefreshTokenCookie(boolean secure) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }
}
