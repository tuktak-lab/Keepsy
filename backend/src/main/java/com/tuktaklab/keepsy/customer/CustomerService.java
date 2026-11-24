package com.tuktaklab.keepsy.customer;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Year;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 등록 (FR-CUS-01, E3, C12) */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final ConsentRepository consentRepository;
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

    private static ApiException duplicatePhone() {
        return new ApiException(ErrorCode.DUPLICATE, "이미 등록된 휴대폰 번호입니다");
    }
}
