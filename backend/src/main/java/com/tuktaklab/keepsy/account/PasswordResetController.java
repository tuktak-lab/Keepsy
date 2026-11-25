package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.PasswordResetService.MailItem;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 비밀번호 재설정 · 가상 메일함, 모두 비로그인 (CONTRACTS 4 · 6장 ACC, F49 · F59) */
@RestController
@RequiredArgsConstructor
class PasswordResetController {

    private final PasswordResetService service;

    record RequestBodyEmail(
            @NotBlank(message = "이메일을 입력해 주세요")
                    @Email(message = "이메일 형식이 아닙니다")
                    @Size(max = 255, message = "이메일은 255자 이하입니다")
                    String email) {}

    record ResetRequest(
            @NotBlank(message = "재설정 링크의 토큰이 없습니다") String token,
            @NotNull(message = "새 비밀번호를 입력해 주세요")
                    @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다")
                    String newPassword) {}

    /** 재설정 메일 요청. 가입 여부와 상관없이 204 (FR-ACC-06, F49) */
    @PostMapping("/api/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void request(@Valid @RequestBody RequestBodyEmail req) {
        service.request(req.email());
    }

    /** 새 비밀번호 설정. 토큰은 한 번만 (FR-ACC-06, F14) */
    @PutMapping("/api/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody ResetRequest req) {
        service.reset(req.token(), req.newPassword());
    }

    /** 가상 메일함: 그 이메일 주소로 온 메일만, 로그인 없이 (F59). 시연용이라 이메일만 알면 재설정 링크가 보이는 것을 받아들였다 */
    @GetMapping("/api/virtual-mailbox")
    PageResponse<MailItem> mailbox(
            @RequestParam
                    @NotBlank(message = "이메일을 입력해 주세요")
                    @Email(message = "이메일 형식이 아닙니다")
                    @Size(max = 255, message = "이메일은 255자 이하입니다")
                    String email,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.mailbox(email, page, size);
    }
}
