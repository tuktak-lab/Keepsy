package com.tuktaklab.keepsy.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    List<Consent> findByCustomerIdOrderByIdAsc(long customerId);

    /** 항목의 최신 이력 행 = 현재 상태 (명세서 5장 consent). 받은 적 없으면 비어 있다 */
    Optional<Consent> findFirstByCustomerIdAndItemOrderByIdDesc(long customerId, Consent.Item item);

    /** 정정할 행 바로 앞 행 (F44) */
    Optional<Consent> findFirstByCustomerIdAndItemAndIdLessThanOrderByIdDesc(
            long customerId, Consent.Item item, long id);
}
