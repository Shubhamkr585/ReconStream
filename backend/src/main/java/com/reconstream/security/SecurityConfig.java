package com.reconstream.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter;

    public SecurityConfig(JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter) {
        this.jwtCookieAuthenticationFilter = jwtCookieAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + authException.getMessage() + "\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // Public auth endpoints
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                // Webhook listener relies on HMAC signature verification
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                // Actuator endpoints
                .requestMatchers("/actuator/**").permitAll()
                // Payments ingestion for internal simulator & k6 load tests
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/**").permitAll()
                // RBAC: ADMIN only
                .requestMatchers(HttpMethod.POST, "/api/v1/simulator/chaos-run").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/recon/discrepancies/**").hasRole("ADMIN")
                // RBAC: ADMIN or AUDITOR
                .requestMatchers(HttpMethod.POST, "/api/v1/recon/settlements/upload").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/ai/triage").hasAnyRole("ADMIN", "AUDITOR")
                // RBAC: Read-only for ADMIN, AUDITOR, ANALYST
                .requestMatchers("/api/v1/recon/**", "/api/v1/ledger/**", "/api/v1/simulator/**").hasAnyRole("ADMIN", "AUDITOR", "ANALYST")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
