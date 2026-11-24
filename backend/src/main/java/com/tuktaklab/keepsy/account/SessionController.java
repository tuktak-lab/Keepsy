package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.SessionQueryService.SessionView;
import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 세션 API (CONTRACTS 4 · 6장 [제안]) */
@RestController
@RequiredArgsConstructor
class SessionController {

    private final ShopLoginService loginService;
    private final SessionQueryService queryService;
    private final ShopSession shopSession;

    record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요") String password) {}

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
