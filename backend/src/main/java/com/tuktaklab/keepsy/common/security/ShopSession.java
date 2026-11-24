package com.tuktaklab.keepsy.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * 서버 세션에 두는 값 (ARCHITECTURE 6장 「세션에 두는 값」, NFR-04). 값을 채우는 규칙은 각 기능 이슈가 맡는다: 로그인 FR-ACC-02, 근무자
 * FR-ACC-10, PIN 인증 FR-ACC-08.
 *
 * <ul>
 *   <li>매장: 로그인한 SecurityContext의 principal ({@link #signIn})
 *   <li>현재 근무자 · PIN 인증 스탭 · PIN 만료 시각: 세션 속성 (로그인을 다시 하지 않고 바뀌므로)
 * </ul>
 */
@Component
public class ShopSession {

    static final String CURRENT_STAFF_ID = "keepsy.currentStaffId";
    static final String PIN_STAFF_ID = "keepsy.pinStaffId";
    static final String PIN_EXPIRES_AT = "keepsy.pinExpiresAt";

    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    /**
     * 매장 로그인 성공 뒤 부른다. 세션 고정 공격을 막으려 세션 ID를 바꾸고, 같은 브라우저에서 전에 로그인했던 매장의 근무자 · PIN 인증은 지운다 (로그인 직후에는
     * 근무자가 없다, FR-ACC-10)
     */
    public void signIn(long shopId, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.removeAttribute(CURRENT_STAFF_ID);
        session.removeAttribute(PIN_STAFF_ID);
        session.removeAttribute(PIN_EXPIRES_AT);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(shopId, null, List.of()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    /**
     * 로그인한 매장 ID. 경로 · 쿼리 · 본문으로 매장 ID를 받지 않고 항상 이것을 쓴다 (CONTRACTS 3장, NFR-03). 로그인하지 않았으면 null (보호
     * API는 SecurityConfig가 먼저 401로 막는다)
     */
    public Long shopId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }

    /** 로그아웃: 근무자 · PIN 인증도 함께 사라진다 (FR-ACC-02·10) */
    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public Long currentStaffId(HttpSession session) {
        return (Long) session.getAttribute(CURRENT_STAFF_ID);
    }

    public void setCurrentStaffId(HttpSession session, Long staffId) {
        session.setAttribute(CURRENT_STAFF_ID, staffId);
    }

    public Long pinStaffId(HttpSession session) {
        return (Long) session.getAttribute(PIN_STAFF_ID);
    }

    public LocalDateTime pinExpiresAt(HttpSession session) {
        return (LocalDateTime) session.getAttribute(PIN_EXPIRES_AT);
    }

    /** PIN 인증 시작 · 연장. staffId가 null이면 해제 ([잠금], FR-ACC-08) */
    public void setPin(HttpSession session, Long staffId, LocalDateTime expiresAt) {
        session.setAttribute(PIN_STAFF_ID, staffId);
        session.setAttribute(PIN_EXPIRES_AT, staffId == null ? null : expiresAt);
    }
}
