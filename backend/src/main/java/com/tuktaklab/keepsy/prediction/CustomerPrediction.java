package com.tuktaklab.keepsy.prediction;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고객 × 시술 카테고리 하나의 현재 예측 (명세서 5장 customer_prediction, A7). 예측 상태는 저장하지 않고 조회할 때 계산한다 (A12).
 *
 * <p>처리 표시 칸(handling_mark · handling_marked_at · handling_base_visit_date)은 처리 표시(#78)에서 매핑한다. 그
 * 전까지 매핑하지 않으므로 재계산의 UPDATE가 그 칸을 건드리지 않는다 (F2 "재계산은 갱신만, 처리 표시는 그대로").
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long customerId;

    /** NAIL | LASH | WAXING | SEMI_PERMANENT | ETC */
    private String category;

    /** 기준 마지막 방문일 (주기 계산 제외 방문은 뺌). null이면 예측 없음 */
    private LocalDate lastServiceDate;

    /** 근거 방문 수 = "방문 n회" (주기 계산 제외 방문은 세지 않음, FR-PRD-03) */
    private int basisVisitCount;

    private Integer avgIntervalDays;

    private LocalDate startDate;

    private LocalDate endDate;

    private boolean endSignal;

    static CustomerPrediction of(long shopId, long customerId, String category) {
        CustomerPrediction p = new CustomerPrediction();
        p.shopId = shopId;
        p.customerId = customerId;
        p.category = category;
        return p;
    }

    /** 다시 계산한 결과로 예측 칸만 바꾼다. 비어 있으면 "예측 없음": 행은 남기고 칸을 비운다 (FR-PRD-01 ①, F2) */
    void apply(Optional<NextVisitPrediction> result) {
        NextVisitPrediction p = result.orElse(null);
        lastServiceDate = p == null ? null : p.lastVisitDate();
        basisVisitCount = p == null ? 0 : p.visitCount();
        avgIntervalDays = p == null ? null : p.averageIntervalDays();
        startDate = p == null ? null : p.startDate();
        endDate = p == null ? null : p.endDate();
        endSignal = p != null && p.endSignal();
    }

    /** 저장된 예측. 예측 없음이면 비어 있다 ({@link PredictionStatusClassifier#classify}의 입력) */
    public Optional<NextVisitPrediction> prediction() {
        return endDate == null
                ? Optional.empty()
                : Optional.of(
                        new NextVisitPrediction(
                                lastServiceDate,
                                basisVisitCount,
                                avgIntervalDays,
                                startDate,
                                endDate,
                                endSignal));
    }
}
