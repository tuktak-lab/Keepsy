package com.tuktaklab.keepsy.prediction;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예측 기록 (명세서 5장 prediction_snapshot, FR-PRD-08). 기준 방문당 하나이고, 다음 유효 방문이 생기면 실제 방문일 · 적중 여부를 채운다
 * (F23). 고객을 삭제해도 정확도 통계용으로 남긴다 (FR-CUS-10).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    private Long customerId;

    private String category;

    private LocalDateTime createdAt;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 근거: 예측에 쓴 방문 수 */
    private int basisVisitCount;

    /** 이탈 신호 기간(일): 그때의 평균 간격과 매장 상한 중 작은 값, 최소 1. F57 이전 행은 null */
    private Integer churnSignalDays;

    /** 실제 다음 방문일. 다음 방문 전에는 null */
    private LocalDate actualVisitDate;

    /** 실제 다음 방문이 기간 안이면 참. 다음 방문 전에는 null */
    private Boolean hit;

    static PredictionSnapshot of(
            long shopId,
            long customerId,
            String category,
            NextVisitPrediction p,
            int churnCapDays,
            LocalDateTime now) {
        PredictionSnapshot s = new PredictionSnapshot();
        s.shopId = shopId;
        s.customerId = customerId;
        s.category = category;
        s.rewrite(p, churnCapDays, now);
        return s;
    }

    /** 같은 기준 방문의 재계산: 아직 평가 전인 스냅샷을 새 기간 · 근거 · 이탈 신호 기간으로 고쳐 쓴다 (F23, F57) */
    void rewrite(NextVisitPrediction p, int churnCapDays, LocalDateTime now) {
        createdAt = now;
        startDate = p.startDate();
        endDate = p.endDate();
        basisVisitCount = p.visitCount();
        // PredictionStatusSql의 GREATEST(1, LEAST(avg_interval_days, churn_signal_cap_days))와 같은 값
        // (F57)
        churnSignalDays = Math.max(1, Math.min(p.averageIntervalDays(), churnCapDays));
    }

    /** 다음 유효 방문이 생겼다: 기간 안이면 적중 (FR-PRD-08) */
    void evaluate(LocalDate actual) {
        actualVisitDate = actual;
        hit = !actual.isBefore(startDate) && !actual.isAfter(endDate);
    }
}
