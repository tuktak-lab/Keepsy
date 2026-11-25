package com.tuktaklab.keepsy.prediction;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PredictionSnapshotRepository extends JpaRepository<PredictionSnapshot, Long> {

    /** 아직 평가 전인 스냅샷 (고객 · 카테고리마다 보통 하나) */
    List<PredictionSnapshot> findByShopIdAndCustomerIdInAndHitIsNull(
            long shopId, Collection<Long> customerIds);

    /** 평가된 스냅샷. 기준 방문일이 앞당겨졌을 때만 읽는다 (F62) */
    List<PredictionSnapshot> findByShopIdAndCustomerIdInAndHitIsNotNull(
            long shopId, Collection<Long> customerIds);
}
