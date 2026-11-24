package com.tuktaklab.keepsy.prediction;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PredictionSnapshotRepository extends JpaRepository<PredictionSnapshot, Long> {

    /** 아직 평가 전인 스냅샷 (고객 · 카테고리마다 보통 하나) */
    List<PredictionSnapshot> findByShopIdAndCustomerIdInAndHitIsNull(
            long shopId, Collection<Long> customerIds);
}
