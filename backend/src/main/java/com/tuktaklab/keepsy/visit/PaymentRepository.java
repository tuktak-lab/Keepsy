package com.tuktaklab.keepsy.visit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdAndShopId(long id, long shopId);

    List<Payment> findByShopIdAndVisitId(long shopId, long visitId);

    /** 고객 결제 금액 이력: 결제 시각 최신순, 취소된 결제 포함 (FR-ANL-04) */
    Page<Payment> findByShopIdAndCustomerIdOrderByPaidAtDescIdDesc(
            long shopId, long customerId, Pageable pageable);
}
