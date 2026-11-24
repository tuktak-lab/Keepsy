package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery;
import com.tuktaklab.keepsy.customer.query.CustomerListQuery;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 등록 요청에서 동의 칸을 뺀 기본 정보. 통째로 보낸다 (CONTRACTS 6장) */
    record UpdateRequest(
            @NotBlank(message = "이름을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하입니다")
                    String name,
            @NotNull(message = "휴대폰 번호를 입력해 주세요")
                    @Pattern(regexp = "010-?\\d{4}-?\\d{4}", message = "휴대폰 번호는 010으로 시작하는 11자리입니다")
                    String phone,
            Customer.Gender gender,
            @Min(value = 1900, message = "출생 연도는 1900년 이후입니다") Integer birthYear,
            Customer.PreferredLanguage preferredLanguage,
            Customer.AcquisitionChannel acquisitionChannel) {}

    @RequireFeature(FeatureKey.CUSTOMER_EDIT)
    @PatchMapping("/api/customers/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable long customerId, @Valid @RequestBody UpdateRequest r) {
        customerService.update(
                shopSession.shopId(),
                customerId,
                new Customer.BasicInfo(
                        r.name(),
                        r.phone(),
                        r.gender(),
                        r.birthYear(),
                        r.preferredLanguage(),
                        r.acquisitionChannel()));
    }

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

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers")
    PageResponse<CustomerService.ListItem> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer visitCountMin,
            @RequestParam(required = false) Integer visitCountMax,
            @RequestParam(required = false)
                    @Pattern(
                            regexp = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC",
                            message = "시술 카테고리 값이 아닙니다")
                    String category,
            @RequestParam(required = false) PredictionStatus predictionStatus,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) Boolean marketingConsent,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return customerService.list(
                shopSession.shopId(),
                q,
                new CustomerListQuery.Filter(
                        null,
                        null,
                        visitCountMin,
                        visitCountMax,
                        category,
                        predictionStatus,
                        staffId,
                        marketingConsent),
                page,
                size);
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}")
    CustomerDetailQuery.Detail detail(@PathVariable long customerId) {
        return customerService.detail(shopSession.shopId(), customerId);
    }

    record ContactResponse(String phone) {}

    @RequireFeature(FeatureKey.CONTACT_FULL)
    @GetMapping("/api/customers/{customerId}/contact")
    ContactResponse contact(@PathVariable long customerId) {
        return new ContactResponse(customerService.contact(shopSession.shopId(), customerId));
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}/consents")
    CustomerService.Consents consents(@PathVariable long customerId) {
        return customerService.consents(shopSession.shopId(), customerId);
    }
}
