package com.tuktaklab.keepsy.prediction;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 예측 상태 분류와 이탈 신호 기간 (FR-PRD-04, FR-SET-03, 기획서 9장). 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이 단위 테스트한다
 * (DEVELOPMENT 4장). 오늘 날짜는 호출하는 쪽이 Asia/Seoul 기준으로 넘긴다 (NFR-12). 고객 목록의 상태 필터({@code
 * customer.query.CustomerListQuery})가 같은 규칙을 SQL로 쓰므로 이 규칙을 바꾸면 그 SQL도 함께 바꾼다.
 */
public final class PredictionStatusClassifier {

    private PredictionStatusClassifier() {}

    /**
     * @param prediction {@link NextVisitCalculator#calculate}의 결과. 비어 있으면 예측 없음
     * @param hasUpcomingReservation 다음 예약(오늘 이후의 확정 예약, 아직 완료되지 않은 오늘 예약 포함)에 해당 카테고리 예정 시술이 있는지
     *     (A11, A13)
     * @param churnCapDays 이탈 신호 기간 상한 (매장 설정, 기본 120일)
     * @param today 오늘 (Asia/Seoul)
     * @return 상태. 다음 예약에 해당 카테고리가 있으면 관리 대상에서 빠지므로 비어 있다
     */
    public static Optional<PredictionStatus> classify(
            Optional<NextVisitPrediction> prediction,
            boolean hasUpcomingReservation,
            int churnCapDays,
            LocalDate today) {
        if (prediction.isEmpty()) {
            return Optional.of(PredictionStatus.NO_PREDICTION);
        }
        if (hasUpcomingReservation) {
            return Optional.empty();
        }
        NextVisitPrediction p = prediction.get();
        LocalDate churnEnd = p.endDate().plusDays(churnPeriodDays(p, churnCapDays));
        if (today.isAfter(churnEnd)) {
            return Optional.of(PredictionStatus.LONG_ABSENT);
        }
        if (p.endSignal() || today.isAfter(p.endDate())) {
            // 종료 신호면 새 예측 없이 방문 당일부터 바로 이탈 신호 (FR-PRD-01 ②)
            return Optional.of(PredictionStatus.CHURN_SIGNAL);
        }
        if (today.isBefore(p.startDate())) {
            return Optional.of(PredictionStatus.SCHEDULED);
        }
        return Optional.of(PredictionStatus.DUE);
    }

    /**
     * 이탈 신호 기간 = 평균 간격(방문 3회 이상이면 최근 5개 간격 평균, 부족하면 기본 주기 최대값)을 매장 상한으로 자른 값 (FR-SET-03). 평균 간격은
     * {@link NextVisitCalculator}가 이미 이 규칙대로 채운다. 경과 비율(FR-PRD-09)의 분모라서 최소 1일로 둔다.
     */
    public static int churnPeriodDays(NextVisitPrediction prediction, int churnCapDays) {
        if (churnCapDays <= 0) {
            throw new IllegalArgumentException("이탈 신호 기간 상한은 0보다 커야 한다: " + churnCapDays);
        }
        return Math.max(1, Math.min(prediction.averageIntervalDays(), churnCapDays));
    }
}
