package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 고객 API (CONTRACTS 4 · 6장 「고객 (CUS)」 [제안]) */
@RestController
@RequiredArgsConstructor
class CustomerController {

    private final CustomerService customerService;
    private final ShopSession shopSession;

    record RegisterRequest(
            @NotBlank(message = "이름을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하입니다")
                    String name,
            @NotNull(message = "휴대폰 번호를 입력해 주세요")
                    @Pattern(regexp = "010-?\\d{4}-?\\d{4}", message = "휴대폰 번호는 010으로 시작하는 11자리입니다")
                    String phone,
            @NotNull(message = "동의 항목을 보내 주세요") Set<Consent.Item> consents,
            @NotNull(message = "동의 방법을 골라 주세요") Consent.Method consentMethod,
            Customer.Gender gender,
            @Min(value = 1900, message = "출생 연도는 1900년 이후입니다") Integer birthYear,
            Customer.PreferredLanguage preferredLanguage,
            Customer.AcquisitionChannel acquisitionChannel) {}

    record RegisterResponse(long customerId) {}

    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PostMapping("/api/customers")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest r, HttpSession session) {
        long customerId =
                customerService.register(
                        shopSession.shopId(),
                        shopSession.currentStaffId(session),
                        new NewCustomer(
                                r.name(),
                                r.phone(),
                                r.consents(),
                                r.consentMethod(),
                                r.gender(),
                                r.birthYear(),
                                r.preferredLanguage(),
                                r.acquisitionChannel()));
        return new RegisterResponse(customerId);
    }
}
