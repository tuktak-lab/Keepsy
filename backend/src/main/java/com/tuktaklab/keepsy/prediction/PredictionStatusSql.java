package com.tuktaklab.keepsy.prediction;

/**
 * {@link PredictionStatusClassifier#classify}와 같은 규칙을 SQL로 (FR-PRD-04). 목록에서 상태로 거르는 조회(고객 목록 · 캠페인
 * 대상)가 메모리에서 거르지 않고 SQL 하나로 쓰도록 이 패키지가 함께 둔다.
 */
public final class PredictionStatusSql {

    private PredictionStatusSql() {}

    /**
     * 예측 상태 문자열({@link PredictionStatus} 이름). 다음 예약에 그 카테고리가 있으면 관리 대상이 아니라 어느 상태와도 맞지 않게 'NONE'으로
     * 둔다. 다음 예약은 오늘(아직 완료되지 않은 오늘 예약 포함) 이후의 확정 예약 (A11, A13).
     *
     * <p>별칭 {@code c}(customer) · {@code cp}(customer_prediction) · {@code s}(shop)와 파라미터 {@code
     * :today}가 있어야 한다.
     */
    public static final String EXPRESSION =
            "CASE"
                    + " WHEN cp.end_date IS NULL THEN 'NO_PREDICTION'"
                    + " WHEN EXISTS (SELECT 1 FROM reservation r"
                    + "   JOIN reservation_service rs ON rs.reservation_id = r.id"
                    + "   JOIN service_menu m ON m.id = rs.service_menu_id"
                    + "   WHERE r.customer_id = c.id AND r.status = 'CONFIRMED'"
                    + "   AND r.start_at >= :today AND m.category = cp.category) THEN 'NONE'"
                    + " WHEN :today > DATE_ADD(cp.end_date, INTERVAL"
                    + "   GREATEST(1, LEAST(cp.avg_interval_days, s.churn_signal_cap_days)) DAY)"
                    + "   THEN 'LONG_ABSENT'"
                    + " WHEN cp.end_signal OR :today > cp.end_date THEN 'CHURN_SIGNAL'"
                    + " WHEN :today < cp.start_date THEN 'SCHEDULED'"
                    + " ELSE 'DUE' END";
}
