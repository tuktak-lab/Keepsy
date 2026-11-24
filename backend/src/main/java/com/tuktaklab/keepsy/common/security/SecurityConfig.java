package com.tuktaklab.keepsy.common.security;

import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매장 계정 세션 로그인과 CSRF (NFR-02, A14, ARCHITECTURE 6장). 기능별 공용/PIN 검사는 권한 검사 Task(#4)가 이 위에 더한다.
 *
 * <ul>
 *   <li>로그인 상태는 서버 세션의 SecurityContext다. 로그인 · 로그아웃은 {@link ShopSession}으로 한다
 *   <li>CSRF는 SPA 방식: 서버가 {@code XSRF-TOKEN} 쿠키를 내려 주고, 프론트는 상태를 바꾸는 요청에 그 값을 {@code X-XSRF-TOKEN}
 *       헤더로 보낸다 (Next.js 개발 모드 rewrites에서도 같은 출처라 그대로 동작)
 *   <li>세션 쿠키 속성(HttpOnly · SameSite)은 application.yaml {@code server.servlet.session.cookie}.
 *       Secure는 로컬이 http라 끈다 (docs DEVELOPMENT.md 5장)
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) {
        http.csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(auth ->
                        auth
                                // 비로그인 허용 (CONTRACTS 4장 권한 열 「비로그인」)
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/shops",
                                        "/api/session",
                                        "/api/password-reset")
                                .permitAll()
                                .requestMatchers(HttpMethod.PUT, "/api/password-reset")
                                .permitAll()
                                .requestMatchers("/api/**")
                                .authenticated()
                                // 화면(정적 파일)은 /api/ 밖 (CONTRACTS 4장 경로 규칙)
                                .anyRequest()
                                .permitAll())
                .exceptionHandling(e ->
                        e.authenticationEntryPoint((request, response, ex) ->
                                        write(response, json, ErrorCode.UNAUTHORIZED))
                                .accessDeniedHandler((request, response, ex) ->
                                        write(
                                                response,
                                                json,
                                                ex instanceof CsrfException
                                                        ? ErrorCode.CSRF_INVALID
                                                        : ErrorCode.UNAUTHORIZED)))
                // 로그아웃은 DELETE /api/session(FR-ACC-02)이 ShopSession으로 한다
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /** 비밀번호 · PIN 해시 (NFR-02) */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 필터에서 난 인증 · 인가 실패도 CONTRACTS 2장 모양으로 (전역 예외 처리까지 오지 않으므로 여기서 직접 쓴다) */
    private static void write(HttpServletResponse response, JsonMapper json, ErrorCode code)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
