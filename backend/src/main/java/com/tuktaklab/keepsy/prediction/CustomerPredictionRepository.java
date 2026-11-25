package com.tuktaklab.keepsy.prediction;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CustomerPredictionRepository extends JpaRepository<CustomerPrediction, Long> {

    List<CustomerPrediction> findByShopIdAndCustomerIdIn(long shopId, Collection<Long> customerIds);

    @Modifying
    @Query(
            "delete from CustomerPrediction p where p.shopId = :shopId and p.customerId = :customerId")
    int deleteCustomer(long shopId, long customerId);

    /**
     * 처리 표시를 통째로 쓴다 (FR-PRD-07, F2, F55). 표시하면 처리 시각 = {@code now}, 처리 기준 방문일 = 지금 마지막 시술일. null이면
     * 셋 다 비운다. 표시는 예측이 있는 행(마지막 시술일 있음)에만 되고, 지우기는 행만 있으면 된다.
     *
     * @param mark null이면 지운다
     * @return 바뀐 행 수 (0이면 그런 예측이 없다)
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    "UPDATE customer_prediction SET handling_mark = :mark,"
                            + " handling_marked_at = CASE WHEN :mark IS NULL THEN NULL ELSE :now END,"
                            + " handling_base_visit_date ="
                            + "   CASE WHEN :mark IS NULL THEN NULL ELSE last_service_date END"
                            + " WHERE shop_id = :shopId AND customer_id = :customerId"
                            + " AND category = :category"
                            + " AND (last_service_date IS NOT NULL OR :mark IS NULL)")
    int mark(long shopId, long customerId, String category, String mark, LocalDateTime now);

    /**
     * 자동 해제 (FR-PRD-07, F2): 재계산 뒤 마지막 시술일이 처리 기준 방문일보다 늦어진 행만 표시를 지운다. 예측만 다시 계산 · 같은 날 · 과거 · 주기
     * 계산 제외 방문은 마지막 시술일이 늦어지지 않아 남는다. 재계산이 저장한 값을 보도록 먼저 내보낸다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    "UPDATE customer_prediction SET handling_mark = NULL, handling_marked_at = NULL,"
                            + " handling_base_visit_date = NULL"
                            + " WHERE shop_id = :shopId AND customer_id IN (:customerIds)"
                            + " AND handling_mark IS NOT NULL"
                            + " AND last_service_date > handling_base_visit_date")
    int releaseHandling(long shopId, Collection<Long> customerIds);
}
