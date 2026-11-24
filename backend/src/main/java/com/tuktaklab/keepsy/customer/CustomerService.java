package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.ConsentHistoryQuery;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery;
import com.tuktaklab.keepsy.customer.query.CustomerListQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 등록 · 목록 · 상세 · 동의 조회 (FR-CUS-01 · 02 · 03 · 09, E3, C12) */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final ConsentRepository consentRepository;
    private final CustomerListQuery listQuery;
    private final ConsentHistoryQuery consentHistoryQuery;
    private final CustomerDetailQuery detailQuery;
    private final Clock clock;

    /**
     * 고객과 동의 이력을 함께 만든다. 동의한 항목만 행을 만들어, 나머지는 "동의를 받은 적 없음"이 된다 (F19).
     *
     * @param staffId 처리 스탭 = 현재 근무자
     * @return 만든 고객 ID
     * @throws ApiException 개인정보 동의 없음 · CSV 전용 동의 방법 · 올해 뒤 출생 연도면 {@code invalid}, 매장 안 같은 번호면
     *     {@code duplicate}
     */
    @Transactional
    public long register(long shopId, long staffId, NewCustomer c) {
        if (!c.consents().contains(Consent.Item.PRIVACY)) {
            // 동의를 못 받은 손님은 고객 없는 예약으로만 (E3)
            throw new ApiException(ErrorCode.INVALID, "개인정보 수집 · 이용 동의가 필요합니다");
        }
        if (c.consentMethod() == Consent.Method.IMPORT) {
            throw new ApiException(ErrorCode.INVALID, "가져오기 동의 방법은 CSV 등록에서만 씁니다");
        }
        if (c.birthYear() != null && c.birthYear() > Year.now(clock).getValue()) {
            throw new ApiException(ErrorCode.INVALID, "출생 연도가 올해보다 늦습니다");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Customer customer = Customer.register(shopId, c, now);
        // 삭제된 고객은 번호가 NULL이라 걸리지 않고 새 고객이 된다 (C12 보완)
        if (customerRepository.existsByShopIdAndPhone(shopId, customer.getPhone())) {
            throw duplicatePhone();
        }
        try {
            customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            // 같은 번호를 동시에 등록하면 고유 제약(uk_customer_shop_phone)이 하나만 남긴다
            throw duplicatePhone();
        }
        consentRepository.saveAll(
                c.consents().stream()
                        .map(item ->
                                Consent.agreed(customer, item, c.consentMethod(), staffId, now))
                        .toList());
        return customer.getId();
    }

    /** 목록 항목 (CONTRACTS 6장 「고객 (CUS)」). 번호는 마스킹한다 (NFR-04) */
    public record ListItem(
            long customerId,
            String name,
            String phone,
            int visitCount,
            LocalDate lastVisitDate,
            boolean marketingConsent) {}

    /**
     * 고객 목록 · 검색 · 필터 (FR-CUS-02). 삭제된 고객은 빼고 최근 등록순.
     *
     * @param q 숫자 4자리면 휴대폰 뒷자리, 그 밖에는 이름 포함. 비어 있으면 검색하지 않는다
     * @param filter 검색어 칸(앞 두 칸)은 무시하고 {@code q}로 채운다
     * @throws ApiException 방문 횟수 범위가 음수이거나 최소가 최대보다 크면 {@code invalid}
     */
    @Transactional(readOnly = true)
    public PageResponse<ListItem> list(
            long shopId, String q, CustomerListQuery.Filter filter, int page, int size) {
        Integer min = filter.visitCountMin();
        Integer max = filter.visitCountMax();
        if ((min != null && min < 0)
                || (max != null && max < 0)
                || (min != null && max != null && min > max)) {
            throw new ApiException(ErrorCode.INVALID, "방문 횟수 범위를 확인해 주세요");
        }
        String query = q == null ? "" : q.strip();
        boolean last4 = query.matches("\\d{4}");
        CustomerListQuery.Filter f =
                new CustomerListQuery.Filter(
                        query.isEmpty() || last4 ? null : query,
                        last4 ? query : null,
                        min,
                        max,
                        filter.category(),
                        filter.predictionStatus(),
                        filter.staffId(),
                        filter.marketingConsent());
        LocalDate today = LocalDate.now(clock);
        List<ListItem> items =
                listQuery.find(shopId, f, today, page, size).stream()
                        .map(r ->
                                new ListItem(
                                        r.customerId(),
                                        r.name(),
                                        Customer.maskPhone(r.phone()),
                                        r.visitCount(),
                                        r.lastVisitDate(),
                                        r.marketingConsent()))
                        .toList();
        return new PageResponse<>(items, page, size, listQuery.count(shopId, f, today));
    }

    /**
     * 고객 상세와 방문 요약 (FR-CUS-03 · 11).
     *
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public CustomerDetailQuery.Detail detail(long shopId, long customerId) {
        // ponytail: 접근 로그(NFR-06)는 기록 수단(#12)이 머지되면 여기서 개인정보 조회로 남긴다
        return detailQuery
                .find(shopId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * 연락처 전체 (명세서 2장 「고객 연락처 전체 보기」, A4). 권한(CONTACT_FULL)은 컨트롤러에서 검사한다.
     *
     * @return {@code 010-0000-0000} 형태의 전체 번호
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public String contact(long shopId, long customerId) {
        // ponytail: 접근 로그(NFR-06, PIN 인증 스탭으로)는 기록 수단(#12)이 머지되면 여기서 남긴다
        return customerRepository
                .findByIdAndShopIdAndStatus(customerId, shopId, Customer.Status.ACTIVE)
                .map(Customer::getPhone)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * CONTRACTS 6장 「`current` 항목 필드」. 받은 적 없으면 {@code agreed} false, {@code recordedAt} null (F19)
     */
    public record ConsentState(Consent.Item item, boolean agreed, OffsetDateTime recordedAt) {}

    public record Consents(List<ConsentState> current, List<ConsentHistoryQuery.Row> history) {}

    /**
     * 동의 현재 상태와 이력 (FR-CUS-09). 항목별 현재 상태는 그 항목의 최신 이력 행이다.
     *
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found} (삭제하면 동의 이력도 지운다, C12)
     */
    @Transactional(readOnly = true)
    public Consents consents(long shopId, long customerId) {
        if (!customerRepository.existsByIdAndShopIdAndStatus(
                customerId, shopId, Customer.Status.ACTIVE)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        // ponytail: 접근 로그(NFR-06)는 기록 수단(#12)이 머지되면 여기서 개인정보 조회로 남긴다
        List<ConsentHistoryQuery.Row> history = consentHistoryQuery.find(shopId, customerId);
        Map<Consent.Item, ConsentHistoryQuery.Row> latest = new EnumMap<>(Consent.Item.class);
        history.forEach(r -> latest.put(r.item(), r));
        List<ConsentState> current =
                Arrays.stream(Consent.Item.values())
                        .map(item -> {
                            ConsentHistoryQuery.Row r = latest.get(item);
                            return r == null
                                    ? new ConsentState(item, false, null)
                                    : new ConsentState(item, r.agreed(), r.recordedAt());
                        })
                        .toList();
        return new Consents(current, history);
    }

    private static ApiException duplicatePhone() {
        return new ApiException(ErrorCode.DUPLICATE, "이미 등록된 휴대폰 번호입니다");
    }
}
