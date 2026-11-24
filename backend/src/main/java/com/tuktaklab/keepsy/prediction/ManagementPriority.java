package com.tuktaklab.keepsy.prediction;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

/**
 * 인사이트 · 오늘 관리할 고객의 정렬 규칙 (FR-PRD-09, E6, 기획서 9장 「오늘 관리할 고객 우선순위」). 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이
 * 단위 테스트한다 (DEVELOPMENT 4장). 목록 조회는 같은 정렬을 {@link ManagementPrioritySql}로 쓰므로 이 규칙을 바꾸면 그 SQL도 함께
 * 바꾼다.
 */
public final class ManagementPriority {

    /**
     * 정렬 키. 조회 쪽이 행마다 채운다.
     *
     * @param elapsedRatio 경과 비율 ({@link #elapsedRatio})
     * @param marketingConsent 마케팅 수신 동의의 현재 상태 (동의 이력의 최신 행)
     * @param recentPaymentTotal 최근 6개월 결제 합계(원)
     */
    public record PriorityKey(
            double elapsedRatio, boolean marketingConsent, long recentPaymentTotal) {}

    /** ① 경과 비율 높은 순 ② 수신 동의 고객 먼저 ③ 최근 6개월 결제 합계 높은 순 */
    public static final Comparator<PriorityKey> ORDER =
            Comparator.comparingDouble(PriorityKey::elapsedRatio)
                    .reversed()
                    .thenComparing(PriorityKey::marketingConsent, Comparator.reverseOrder())
                    .thenComparing(PriorityKey::recentPaymentTotal, Comparator.reverseOrder());

    private ManagementPriority() {}

    /**
     * 경과 비율 = (오늘 − 예측 종료일) ÷ 이탈 신호 기간. 주기 도래는 0 이하, 이탈 신호는 0~1이라 시술마다 주기가 달라도 같은 기준으로 비교된다. 종료 신호면
     * 종료일이 종료 신호 방문일이다 (FR-PRD-01).
     */
    public static double elapsedRatio(
            NextVisitPrediction prediction, int churnCapDays, LocalDate today) {
        long elapsed = ChronoUnit.DAYS.between(prediction.endDate(), today);
        return (double) elapsed
                / PredictionStatusClassifier.churnPeriodDays(prediction, churnCapDays);
    }
}
