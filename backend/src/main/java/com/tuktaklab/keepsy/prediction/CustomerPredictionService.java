package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.prediction.port.PredictionVisits;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 상세의 카테고리별 예측 (FR-PRD-02 · 03 · 04, FR-CUS-03, CONTRACTS 6장 「고객 (CUS)」) */
@Service
@RequiredArgsConstructor
public class CustomerPredictionService {

    /** CONTRACTS 3장 「시술 카테고리」 순서 */
    private static final List<String> CATEGORY_ORDER =
            List.of("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC");

    private final PredictionVisits visits;
    private final CustomerPredictionRepository predictions;
    private final Clock clock;

    /**
     * CONTRACTS 6장 「{@code predictions} 항목 필드」. 예측 없음이면 날짜 · 평균 간격이 null이다.
     *
     * @param status 다음 예약에 이 카테고리가 있으면 관리 대상이 아니라 null
     * @param defaultCycleBased 근거 방문 1~2회 = "시술 평균 주기 기준" (FR-PRD-03)
     */
    public record Item(
            String category,
            PredictionStatus status,
            LocalDate lastServiceDate,
            LocalDate startDate,
            LocalDate endDate,
            Integer averageIntervalDays,
            int basisVisitCount,
            boolean defaultCycleBased,
            boolean endSignal) {}

    /**
     * @throws ApiException 다른 매장 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public List<Item> forCustomer(long shopId, long customerId) {
        if (!visits.isActiveCustomer(shopId, customerId)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        LocalDate today = LocalDate.now(clock);
        int churnCap = visits.churnCapDays(shopId);
        Set<String> upcoming = visits.upcomingCategories(shopId, customerId, today);
        return predictions.findByShopIdAndCustomerIdIn(shopId, List.of(customerId)).stream()
                .sorted(Comparator.comparingInt(p -> CATEGORY_ORDER.indexOf(p.getCategory())))
                .map(p ->
                        new Item(
                                p.getCategory(),
                                PredictionStatusClassifier.classify(
                                                p.prediction(),
                                                upcoming.contains(p.getCategory()),
                                                churnCap,
                                                today)
                                        .orElse(null),
                                p.getLastServiceDate(),
                                p.getStartDate(),
                                p.getEndDate(),
                                p.getAvgIntervalDays(),
                                p.getBasisVisitCount(),
                                p.getBasisVisitCount() == 1 || p.getBasisVisitCount() == 2,
                                p.isEndSignal()))
                .toList();
    }
}
