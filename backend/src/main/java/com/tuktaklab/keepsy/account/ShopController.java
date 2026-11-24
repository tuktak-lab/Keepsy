package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.ShopInfoService.ShopInfo;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 가입(비로그인) · 매장 정보 조회(근무자) API (CONTRACTS 4 · 6장 [제안]) */
@RestController
@RequiredArgsConstructor
class ShopController {

    /** 날짜 없는 시각 (CONTRACTS 3장 「시:분」, F20) */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ShopInfoService infoService;
    private final ShopSession shopSession;

    record OpeningHoursResponse(DayOfWeek dayOfWeek, String open, String close) {}

    record ShopResponse(
            String name,
            String phone,
            String senderNumber,
            String unsubscribeNumber,
            List<OpeningHoursResponse> openingHours) {}

    /** 매장 정보 · 영업시간 (FR-SET-01). 근무자. 매장 설정 화면과 예약 화면이 함께 쓴다 (F20) */
    @RequireWorker
    @GetMapping("/api/shop")
    ShopResponse shop() {
        ShopInfo info = infoService.get(shopSession.shopId());
        return new ShopResponse(
                info.name(),
                info.phone(),
                info.senderNumber(),
                info.unsubscribeNumber(),
                info.openingHours().stream()
                        .map(h ->
                                new OpeningHoursResponse(
                                        h.dayOfWeek(), hhmm(h.open()), hhmm(h.close())))
                        .toList());
    }

    private static String hhmm(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
    }

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
