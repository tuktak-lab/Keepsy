package com.tuktaklab.keepsy.prediction;

/**
 * {@link ManagementPriority#ORDER}와 같은 정렬을 SQL로 (FR-PRD-09, E6). 인사이트 목록 · 오늘 관리할 고객이 페이지를 나눠도 같은
 * 순서가 되도록 메모리에서 정렬하지 않고 SQL 하나로 쓴다. 규칙을 바꾸면 두 곳을 함께 바꾼다.
 */
public final class ManagementPrioritySql {

    private ManagementPrioritySql() {}

    /**
     * {@code ORDER BY} 뒤에 붙인다. ① 경과 비율 높은 순 ② 마케팅 수신 동의(최신 행) 먼저 ③ 최근 6개월 결제 합계 높은 순(취소 결제 제외, F6).
     * 최근 6개월은 오늘의 6개월 전 같은 날 00:00부터다. 그래도 같으면 고객 · 카테고리 순으로 고정해 페이지가 흔들리지 않게 한다.
     *
     * <p>경과 비율은 실수로 나눈다(정수 나눗셈이면 소수 4자리에서 잘려 순서가 달라진다). 예측 없음 행(종료일 NULL)은 맨 뒤다. 별칭 {@code
     * c}(customer) · {@code cp}(customer_prediction) · {@code s}(shop)와 파라미터 {@code :today}가 있어야
     * 한다.
     */
    public static final String ORDER_BY =
            "1E0 * DATEDIFF(:today, cp.end_date)"
                    + " / GREATEST(1, LEAST(cp.avg_interval_days, s.churn_signal_cap_days)) DESC,"
                    + " COALESCE((SELECT cs.agreed FROM consent cs WHERE cs.customer_id = c.id"
                    + "   AND cs.item = 'MARKETING' ORDER BY cs.id DESC LIMIT 1), FALSE) DESC,"
                    + " (SELECT COALESCE(SUM(p.amount), 0) FROM payment p"
                    + "   WHERE p.shop_id = c.shop_id AND p.customer_id = c.id AND NOT p.canceled"
                    + "   AND p.paid_at >= DATE_SUB(:today, INTERVAL 6 MONTH)) DESC,"
                    + " c.id, cp.category";
}
