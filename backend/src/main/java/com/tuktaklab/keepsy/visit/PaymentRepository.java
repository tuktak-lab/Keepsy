package com.tuktaklab.keepsy.visit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdAndShopId(long id, long shopId);

    List<Payment> findByShopIdAndVisitId(long shopId, long visitId);
}
