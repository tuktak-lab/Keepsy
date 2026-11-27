package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery;
import com.tuktaklab.keepsy.customer.query.CustomerListQuery;
import com.tuktaklab.keepsy.customer.query.RetentionExpiredQuery;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 고객 API (CONTRACTS 4 · 6장 「고객 (CUS)」 [제안]) */
@RestController
@RequiredArgsConstructor
class CustomerController {

    /** CONTRACTS 3장 「시술 카테고리」 */
    private static final String CATEGORY = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC";

    private final CustomerService customerService;
    private final ShopSession shopSession;
    private final AccessLogger accessLogger;
    private final FeatureGuard featureGuard;
    private final CustomerImportService importService;

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

    /** 등록 요청에서 동의 칸을 뺀 기본 정보. 통째로 보내되 번호 · 선호 언어는 없으면 그대로 둔다 (CONTRACTS 6장, F78) */
    record UpdateRequest(
            @NotBlank(message = "이름을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하입니다")
                    String name,
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

    record TagRequest(
            @NotNull(message = "시술 카테고리를 골라 주세요")
                    @Pattern(regexp = CATEGORY, message = "시술 카테고리 값이 아닙니다")
                    String category,
            @NotBlank(message = "태그를 입력해 주세요") @Size(max = 50, message = "태그는 50자 이하입니다")
                    String tag) {}

    /** 태그 목록과 메모를 통째로 보낸다 (CONTRACTS 6장) */
    record PreferencesRequest(
            @NotNull(message = "태그 목록을 보내 주세요") @Size(max = 50, message = "태그는 50개까지입니다")
                    List<@NotNull @Valid TagRequest> tags,
            @Size(max = 1000, message = "취향 메모는 1,000자 이하입니다") String memo) {}

    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PutMapping("/api/customers/{customerId}/preferences")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePreferences(
            @PathVariable long customerId, @Valid @RequestBody PreferencesRequest r) {
        customerService.changePreferences(
                shopSession.shopId(),
                customerId,
                r.tags().stream()
                        .map(t -> new CustomerService.TagInput(t.category(), t.tag()))
                        .toList(),
                r.memo());
    }

    record ShopTagsResponse(List<String> tags) {}

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/preference-tags")
    ShopTagsResponse shopTags(
            @RequestParam @Pattern(regexp = CATEGORY, message = "시술 카테고리 값이 아닙니다")
                    String category) {
        return new ShopTagsResponse(customerService.shopTags(shopSession.shopId(), category));
    }

    /** 두 칸을 통째로 보낸다. 비우면 지운다 (CONTRACTS 6장) */
    record CautionsRequest(
            @Size(max = 1000, message = "시술 주의사항은 1,000자 이하입니다") String serviceCaution,
            @Size(max = 1000, message = "건강 주의사항은 1,000자 이하입니다") String healthCaution) {}

    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PutMapping("/api/customers/{customerId}/cautions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeCautions(@PathVariable long customerId, @Valid @RequestBody CautionsRequest r) {
        customerService.changeCautions(
                shopSession.shopId(), customerId, r.serviceCaution(), r.healthCaution());
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

    /**
     * CSV 고객 일괄 등록 (CONTRACTS 6장, F50). multipart {@code file} + {@code consentConfirmed}. 빠지면 서비스가
     * invalid
     */
    @RequireFeature(FeatureKey.CUSTOMER_WRITE)
    @PostMapping("/api/customers/import")
    CustomerImportService.Result importCsv(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) Boolean consentConfirmed,
            HttpSession session)
            throws IOException {
        return importService.importCsv(
                shopSession.shopId(),
                shopSession.currentStaffId(session),
                file == null ? null : file.getBytes(),
                consentConfirmed);
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers")
    PageResponse<CustomerService.ListItem> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer visitCountMin,
            @RequestParam(required = false) Integer visitCountMax,
            @RequestParam(required = false) @Pattern(regexp = CATEGORY, message = "시술 카테고리 값이 아닙니다")
                    String category,
            @RequestParam(required = false) PredictionStatus predictionStatus,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) Boolean marketingConsent,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<CustomerService.ListItem> list =
                customerService.list(
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
        // 돌려준 페이지의 고객만 조회로 남긴다. 검색어 · 이름 · 번호는 남기지 않는다 (NFR-06, CONTRACTS 1장)
        accessLogger.recordAll(
                FeatureKey.CUSTOMER_VIEW,
                AccessLogger.CUSTOMER,
                list.items().stream().map(CustomerService.ListItem::customerId).toList(),
                Action.VIEW);
        return list;
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}")
    CustomerDetailQuery.Detail detail(@PathVariable long customerId) {
        CustomerDetailQuery.Detail detail =
                customerService.detail(shopSession.shopId(), customerId);
        accessLogger.record(
                FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return detail;
    }

    record ContactResponse(String phone) {}

    @RequireFeature(FeatureKey.CONTACT_FULL)
    @GetMapping("/api/customers/{customerId}/contact")
    ContactResponse contact(@PathVariable long customerId) {
        String phone = customerService.contact(shopSession.shopId(), customerId);
        accessLogger.record(
                FeatureKey.CONTACT_FULL, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return new ContactResponse(phone);
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}/consents")
    CustomerService.Consents consents(@PathVariable long customerId) {
        CustomerService.Consents consents =
                customerService.consents(shopSession.shopId(), customerId);
        accessLogger.record(
                FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return consents;
    }

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/customers/{customerId}/history")
    PageResponse<CustomerService.HistoryVisit> history(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<CustomerService.HistoryVisit> history =
                customerService.history(shopSession.shopId(), customerId, page, size);
        accessLogger.record(
                FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return history;
    }

    /** 요청 하나에 항목 하나 (CONTRACTS 6장) */
    record ConsentRequest(
            @NotNull(message = "동의 항목을 골라 주세요") Consent.Item item,
            @NotNull(message = "동의 여부를 보내 주세요") Boolean agreed,
            @NotNull(message = "동의 방법을 골라 주세요") Consent.Method method,
            Consent.WithdrawalPath withdrawalPath) {}

    record IdResponse(long id) {}

    @RequireFeature(FeatureKey.CONSENT)
    @PostMapping("/api/customers/{customerId}/consents")
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse changeConsent(
            @PathVariable long customerId,
            @Valid @RequestBody ConsentRequest r,
            HttpSession session) {
        return new IdResponse(
                customerService.changeConsent(
                        shopSession.shopId(),
                        shopSession.currentStaffId(session),
                        customerId,
                        r.item(),
                        r.agreed(),
                        r.method(),
                        r.withdrawalPath()));
    }

    record CorrectionRequest(@NotNull(message = "올바른 동의 여부를 보내 주세요") Boolean agreed) {}

    /** 잘못 입력한 동의 정정 (CONTRACTS 6장, F44). 처리 스탭은 잠긴 상태면 PIN 인증 스탭 */
    @RequireFeature(FeatureKey.CUSTOMER_EDIT)
    @PostMapping("/api/consents/{consentId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    IdResponse correctConsent(
            @PathVariable long consentId,
            @Valid @RequestBody CorrectionRequest r,
            HttpSession session) {
        return new IdResponse(
                customerService.correctConsent(
                        shopSession.shopId(),
                        featureGuard.actorStaffId(session, FeatureKey.CUSTOMER_EDIT),
                        consentId,
                        r.agreed()));
    }

    record BookingRestrictionRequest(
            @NotNull(message = "예약 제한 여부를 보내 주세요") Boolean restricted, String reason) {}

    /** 예약 제한 켜기 · 끄기와 사유 (CONTRACTS 6장, FR-CUS-14, F53). 사유 규칙은 서비스가 검사한다 */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PutMapping("/api/customers/{customerId}/booking-restriction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeBookingRestriction(
            @PathVariable long customerId, @Valid @RequestBody BookingRestrictionRequest r) {
        customerService.changeBookingRestriction(
                shopSession.shopId(), customerId, r.restricted(), r.reason());
    }

    record DeletionPreviewResponse(int cancelReservationCount) {}

    /** 삭제 전 확인 (CONTRACTS 6장, FR-CUS-10) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @GetMapping("/api/customers/{customerId}/deletion-preview")
    DeletionPreviewResponse deletionPreview(@PathVariable long customerId) {
        return new DeletionPreviewResponse(
                customerService.deletionPreview(shopSession.shopId(), customerId));
    }

    /** 고객 삭제(파기) (CONTRACTS 6장, FR-CUS-10 · 12, C12). 접근 로그는 서비스 트랜잭션 안에서 남긴다 */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @DeleteMapping("/api/customers/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long customerId) {
        customerService.delete(shopSession.shopId(), customerId);
    }

    /** 파기 대상 목록 (CONTRACTS 6장, FR-CUS-12) */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @GetMapping("/api/customers/retention-expired")
    PageResponse<RetentionExpiredQuery.Row> retentionExpired(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return customerService.retentionExpired(shopSession.shopId(), page, size);
    }
}
