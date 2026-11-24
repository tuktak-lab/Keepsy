package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매장 설정값 (CONTRACTS 4 · 6장 SET [제안]). shop 표라 account가 맡는다 (F21). 원장 PIN */
@RestController
@RequiredArgsConstructor
@RequireFeature(FeatureKey.OWNER_PIN)
class SettingsController {

    private final ShopSettingsService settings;
    private final ShopSession shopSession;

    /** 범위는 결정 F40 */
    record SettingsBody(
            @NotNull(message = "이탈 신호 기간 상한을 입력해 주세요")
                    @Min(value = 30, message = "이탈 신호 기간 상한은 30~365일입니다")
                    @Max(value = 365, message = "이탈 신호 기간 상한은 30~365일입니다")
                    Integer churnSignalCapDays,
            @NotNull(message = "귀속 기간을 입력해 주세요")
                    @Min(value = 1, message = "귀속 기간은 1~60일입니다")
                    @Max(value = 60, message = "귀속 기간은 1~60일입니다")
                    Integer attributionDays,
            @NotNull(message = "일 발송 한도를 입력해 주세요")
                    @Min(value = 1, message = "일 발송 한도는 1~10,000건입니다")
                    @Max(value = 10_000, message = "일 발송 한도는 1~10,000건입니다")
                    Integer dailySendLimit,
            @NotNull(message = "재발송 제한 기간을 입력해 주세요")
                    @Min(value = 0, message = "재발송 제한 기간은 0~60일입니다")
                    @Max(value = 60, message = "재발송 제한 기간은 0~60일입니다")
                    Integer resendBlockDays,
            @NotNull(message = "가상 발송 실패 비율을 입력해 주세요")
                    @Min(value = 0, message = "가상 발송 실패 비율은 0~100%입니다")
                    @Max(value = 100, message = "가상 발송 실패 비율은 0~100%입니다")
                    Integer virtualFailurePercent,
            @NotNull(message = "보관 기간을 입력해 주세요")
                    @Min(value = 1, message = "보관 기간은 1~5년입니다")
                    @Max(value = 5, message = "보관 기간은 1~5년입니다")
                    Integer retentionYears) {}

    /** 매장 설정값 (FR-SET-03 · 04 · 06) */
    @GetMapping("/api/settings")
    SettingsBody get() {
        Shop.Settings s = settings.settings(shopSession.shopId());
        return new SettingsBody(
                s.churnSignalCapDays(),
                s.attributionDays(),
                s.dailySendLimit(),
                s.resendBlockDays(),
                s.virtualFailurePercent(),
                s.retentionYears());
    }

    /** 통째로 바꾼다 (FR-SET-03 · 04 · 06, F40). 하나라도 틀리면 Bean Validation이 막아 아무것도 바뀌지 않는다 */
    @PutMapping("/api/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@Valid @RequestBody SettingsBody body) {
        settings.updateSettings(
                shopSession.shopId(),
                new Shop.Settings(
                        body.churnSignalCapDays(),
                        body.attributionDays(),
                        body.dailySendLimit(),
                        body.resendBlockDays(),
                        body.virtualFailurePercent(),
                        body.retentionYears()));
    }
}
