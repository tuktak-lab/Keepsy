package com.tuktaklab.keepsy.prediction;

import java.time.LocalDate;

/**
 * 고객 × 시술 카테고리 하나의 다음 방문 기간 (명세서 5장 customer_prediction).
 *
 * @param lastVisitDate        기준 마지막 방문일 (주기 계산 제외 방문은 빼고)
 * @param visitCount           방문 n회 (주기 계산 제외 방문은 세지 않음, FR-PRD-03)
 * @param averageIntervalDays  최근 5개 간격 평균. 방문 2회 이하면 기본 주기 최대값
 * @param startDate            예측 시작일
 * @param endDate              예측 종료일
 * @param endSignal            종료 신호 여부. 참이면 시작일 = 종료일 = 종료 신호 방문일
 */
public record NextVisitPrediction(
        LocalDate lastVisitDate,
        int visitCount,
        int averageIntervalDays,
        LocalDate startDate,
        LocalDate endDate,
        boolean endSignal
) {
}
