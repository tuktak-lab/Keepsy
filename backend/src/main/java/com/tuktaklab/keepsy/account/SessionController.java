package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.SessionQueryService.SessionView;
import com.tuktaklab.keepsy.account.SessionQueryService.StaffRef;
import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 세션 API (CONTRACTS 4 · 6장 [제안]) */
@RestController
@RequiredArgsConstructor
class SessionController {

    private final ShopLoginService loginService;
    private final SessionQueryService queryService;
    private final StaffPinVerifier pinVerifier;
    private final ShopSession shopSession;
    private final Clock clock;

    record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요") String password) {}

    record PinRequest(
            @NotNull(message = "PIN을 입력해 주세요")
                    @Pattern(regexp = "\\d{4,6}", message = "PIN은 숫자 4~6자리입니다")
                    String pin) {}

    /**
     * 근무자 PIN 입력 · 근무자 전환 (FR-ACC-10). 매장 세션. 잠긴 기능 PIN 인증은 그대로 둔다 (현재 근무자와 PIN 인증 스탭은 다를 수 있다, D6)
     */
    @PutMapping("/api/session/worker")
    StaffRef switchWorker(@Valid @RequestBody PinRequest request, HttpSession session) {
        Staff staff = pinVerifier.verify(shopSession.shopId(), request.pin(), false);
        shopSession.setCurrentStaffId(session, staff.getId());
        return new StaffRef(staff.getId(), staff.getName());
    }

    record PinAuth(long id, String name, OffsetDateTime expiresAt) {}

    /**
     * 잠긴 기능 PIN 인증 (FR-ACC-08). 근무자. "잠긴 기능 사용 허용" 스탭만 통과하고 현재 근무자가 아니어도 된다 (D6). 만료는 5분이고 잠긴 기능을 쓸
     * 때마다 FeatureGuard가 연장한다 (C23)
     */
    @RequireWorker
    @PostMapping("/api/session/pin")
    PinAuth authenticatePin(@Valid @RequestBody PinRequest request, HttpSession session) {
        Staff staff = pinVerifier.verify(shopSession.shopId(), request.pin(), true);
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(FeatureGuard.PIN_TIMEOUT);
        shopSession.setPin(session, staff.getId(), expiresAt);
        return new PinAuth(staff.getId(), staff.getName(), TimeConfig.offset(expiresAt));
    }

    /** [잠금] (FR-ACC-08). 근무자. PIN 인증만 해제하고 현재 근무자는 그대로 둔다 */
    @RequireWorker
    @DeleteMapping("/api/session/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void lock(HttpSession session) {
        shopSession.setPin(session, null, null);
    }

    /** 매장 로그인 (FR-ACC-02). 비로그인. 로그인 직후에는 근무자가 없다 (FR-ACC-10) */
    @PostMapping("/api/session")
    LoggedIn login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        LoggedIn loggedIn = loginService.login(request.email(), request.password());
        shopSession.signIn(loggedIn.shopId(), httpRequest, httpResponse);
        return loggedIn;
    }

    /** 세션 조회 (FR-ACC-08 · 10). 매장 세션이면 근무자가 없어도 된다 */
    @GetMapping("/api/session")
    SessionView current(HttpSession session) {
        return queryService.current(session);
    }

    /** 로그아웃: 세션이 사라져 근무자 · PIN 인증도 함께 해제된다 (FR-ACC-02 · 10) */
    @DeleteMapping("/api/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        shopSession.signOut(request);
    }
}
