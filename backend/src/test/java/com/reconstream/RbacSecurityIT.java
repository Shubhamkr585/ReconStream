package com.reconstream;

import com.reconstream.security.AuthController;
import com.reconstream.security.RefreshToken;
import com.reconstream.security.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RbacSecurityIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("Unauthenticated requests to protected endpoints return 401 Unauthorized")
    void testUnauthenticatedReturns401() {
        ResponseEntity<Map> respChaos = restTemplate.postForEntity(
                "/api/v1/simulator/chaos-run", Map.of("orderCount", 5), Map.class);
        assertThat(respChaos.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> respSummary = restTemplate.getForEntity(
                "/api/v1/recon/summary", Map.class);
        assertThat(respSummary.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("HttpOnly SameSite=Strict cookies issued on login and ANALYST receives 403 on mutation endpoints")
    void testAnalystRbacAndCookieAttributes() {
        // 1. Login as Analyst
        AuthController.LoginRequest loginReq = new AuthController.LoginRequest(
                "analyst@reconstream.dev", "password123");
        ResponseEntity<AuthController.UserResponse> loginResp = restTemplate.postForEntity(
                "/api/v1/auth/login", loginReq, AuthController.UserResponse.class);

        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).isNotNull();
        assertThat(loginResp.getBody().role()).isEqualTo("ANALYST");

        List<String> setCookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        assertThat(setCookies).hasSize(2);

        // Verify HttpOnly and SameSite=Strict on cookies
        boolean hasHttpOnlyAccess = setCookies.stream()
                .anyMatch(c -> c.contains("access_token") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        boolean hasHttpOnlyRefresh = setCookies.stream()
                .anyMatch(c -> c.contains("refresh_token") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        assertThat(hasHttpOnlyAccess).isTrue();
        assertThat(hasHttpOnlyRefresh).isTrue();

        // 2. Analyst attempts read-only /api/v1/recon/summary -> 200 OK
        HttpHeaders authHeaders = new HttpHeaders();
        for (String c : setCookies) {
            authHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
        }
        HttpEntity<Void> readEntity = new HttpEntity<>(authHeaders);
        ResponseEntity<Map> summaryResp = restTemplate.exchange(
                "/api/v1/recon/summary", HttpMethod.GET, readEntity, Map.class);
        assertThat(summaryResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Analyst attempts mutation /api/v1/simulator/chaos-run -> 403 Forbidden
        HttpEntity<Map<String, Object>> chaosEntity = new HttpEntity<>(
                Map.of("orderCount", 1), authHeaders);
        ResponseEntity<Map> chaosResp = restTemplate.exchange(
                "/api/v1/simulator/chaos-run", HttpMethod.POST, chaosEntity, Map.class);
        assertThat(chaosResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 4. Analyst attempts mutation /api/v1/recon/settlements/upload -> 403 Forbidden
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource csvResource = new ByteArrayResource("test".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test.csv";
            }
        };
        body.add("file", csvResource);
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        for (String c : setCookies) {
            uploadHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
        }
        HttpEntity<MultiValueMap<String, Object>> uploadEntity = new HttpEntity<>(body, uploadHeaders);
        ResponseEntity<Map> uploadResp = restTemplate.exchange(
                "/api/v1/recon/settlements/upload", HttpMethod.POST, uploadEntity, Map.class);
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADMIN login permits chaos run, and Refresh Token rotation invalidates old tokens")
    void testAdminAndRefreshTokenRotation() {
        // 1. Login as Admin
        AuthController.LoginRequest adminLogin = new AuthController.LoginRequest(
                "admin@reconstream.dev", "password123");
        ResponseEntity<AuthController.UserResponse> adminResp = restTemplate.postForEntity(
                "/api/v1/auth/login", adminLogin, AuthController.UserResponse.class);

        assertThat(adminResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminResp.getBody()).isNotNull();
        assertThat(adminResp.getBody().role()).isEqualTo("ADMIN");

        List<String> cookies = adminResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();

        String rawRefreshTokenCookie = cookies.stream()
                .filter(c -> c.startsWith("refresh_token="))
                .findFirst()
                .orElseThrow();
        String oldRefreshTokenValue = rawRefreshTokenCookie.split(";")[0].substring("refresh_token=".length());

        // Verify refresh token row is stored and active in DB
        String hashedToken = AuthController.hashToken(oldRefreshTokenValue);
        RefreshToken initialToken = refreshTokenRepository.findByTokenHash(hashedToken).orElseThrow();
        assertThat(initialToken.isRevoked()).isFalse();

        // 2. ADMIN executes chaos run -> 200 OK
        HttpHeaders adminHeaders = new HttpHeaders();
        for (String c : cookies) {
            adminHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
        }
        HttpEntity<Map<String, Object>> chaosEntity = new HttpEntity<>(
                Map.of("orderCount", 1, "duplicateRate", 0.0, "outOfOrderRate", 0.0, "dropRate", 0.0),
                adminHeaders
        );
        ResponseEntity<Map> chaosResp = restTemplate.exchange(
                "/api/v1/simulator/chaos-run", HttpMethod.POST, chaosEntity, Map.class);
        assertThat(chaosResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Rotate refresh token via POST /api/v1/auth/refresh
        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, "refresh_token=" + oldRefreshTokenValue);
        HttpEntity<Void> refreshEntity = new HttpEntity<>(refreshHeaders);

        ResponseEntity<AuthController.UserResponse> refreshResp = restTemplate.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, refreshEntity, AuthController.UserResponse.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. Verify old refresh token is now revoked in database
        RefreshToken oldTokenAfterRotation = refreshTokenRepository.findByTokenHash(hashedToken).orElseThrow();
        assertThat(oldTokenAfterRotation.isRevoked()).isTrue();

        // 5. Attempting to use the old revoked refresh token again returns 401 Unauthorized
        ResponseEntity<Map> replayResp = restTemplate.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, refreshEntity, Map.class);
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 6. Logout revokes token and clears cookies
        List<String> newCookies = refreshResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        HttpHeaders logoutHeaders = new HttpHeaders();
        for (String c : newCookies) {
            logoutHeaders.add(HttpHeaders.COOKIE, c.split(";")[0]);
        }
        ResponseEntity<Map> logoutResp = restTemplate.exchange(
                "/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), Map.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> clearCookies = logoutResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(clearCookies).isNotNull();
        boolean clearedAccess = clearCookies.stream().anyMatch(c -> c.contains("access_token=") && c.contains("Max-Age=0"));
        boolean clearedRefresh = clearCookies.stream().anyMatch(c -> c.contains("refresh_token=") && c.contains("Max-Age=0"));
        assertThat(clearedAccess).isTrue();
        assertThat(clearedRefresh).isTrue();
    }
}
