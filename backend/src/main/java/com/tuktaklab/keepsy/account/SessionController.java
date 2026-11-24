package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 매장 세션 API (CONTRACTS 4 · 6장 [제안]) */
@RestController
@RequiredArgsConstructor
class SessionController {

    private final ShopLoginService loginService;
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
}
