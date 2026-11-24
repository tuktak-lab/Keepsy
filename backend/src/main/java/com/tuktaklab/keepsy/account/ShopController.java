package com.tuktaklab.keepsy.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 가입 API (CONTRACTS 4 · 6장 [제안]). 비로그인 */
@RestController
@RequiredArgsConstructor
class ShopController {

    private final ShopSignupService signupService;

    record SignUpRequest(
            @NotBlank(message = "이메일을 입력해 주세요")
                    @Email(message = "이메일 형식이 아닙니다")
                    @Size(max = 255, message = "이메일은 255자 이하입니다")
                    String email,
            @NotNull(message = "비밀번호를 입력해 주세요") @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다")
                    String password,
            @NotBlank(message = "매장명을 입력해 주세요") @Size(max = 100, message = "매장명은 100자 이하입니다")
                    String shopName,
            @NotBlank(message = "원장 이름을 입력해 주세요") @Size(max = 50, message = "원장 이름은 50자 이하입니다")
                    String ownerName,
            @NotNull(message = "원장 PIN을 입력해 주세요")
                    @Pattern(regexp = "\\d{4,6}", message = "PIN은 숫자 4~6자리입니다")
                    String ownerPin) {}

    record SignUpResponse(long shopId) {}

    @PostMapping("/api/shops")
    @ResponseStatus(HttpStatus.CREATED)
    SignUpResponse signUp(@Valid @RequestBody SignUpRequest request) {
        long shopId =
                signupService.signUp(
                        request.email(),
                        request.password(),
                        request.shopName(),
                        request.ownerName(),
                        request.ownerPin());
        return new SignUpResponse(shopId);
    }
}
