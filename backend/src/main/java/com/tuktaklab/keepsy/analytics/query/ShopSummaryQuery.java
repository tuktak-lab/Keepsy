package com.tuktaklab.keepsy.analytics.query;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 기본 지표: 매출 · 방문 수 · 신규 고객 수 (FR-ANL-05, 결정 F24). 결제 · 방문 · 고객을 함께 세는 화면용 횡단 조회라 조회 전용 SQL로 DB에서
 * 집계한다 (DEVELOPMENT 4장 「도메인 연동」, NFR-01). 삭제된 고객의 결제 · 방문도 그대로 센다 (C12).
 */
@Repository
@RequiredArgsConstructor
public class ShopSummaryQuery {

    public record Summary(long revenue, long visitCount, long newCustomerCount) {}

    /** 주기 내 재방문 (FR-ANL-05, F58). 비율은 화면이 {@code revisitedCount ÷ endedCount}로 나눈다 */
    public record CycleRevisit(String category, long endedCount, long revisitedCount) {}

    /** CONTRACTS 3절 「시술 카테고리」 순서 */
    private static final List<String> CATEGORIES =
            List.of("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC");

    /** 이탈 신호 기간 끝날: 예측 종료일 + 스냅샷에 남긴 이탈 신호 기간 (F57) */
    private static final String CHURN_END = "DATE_ADD(end_date, INTERVAL churn_signal_days DAY)";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param to 포함
     */
    @Transactional(readOnly = true)
    public Summary find(long shopId, LocalDate from, LocalDate to) {
        Map<String, Object> p =
                Map.of(
                        "shopId", shopId,
                        "from", from,
                        "to", to,
                        "fromAt", from.atStartOfDay(),
                        "toExclusiveAt", to.plusDays(1).atStartOfDay());
        return jdbc.queryForObject(
                "SELECT"
                        // 매출: 결제일(서울) 기준, 취소 결제 제외 (FR-PAY-02). 고객 없는 방문의 결제 포함 (E3)
                        + " (SELECT COALESCE(SUM(amount), 0) FROM payment"
                        + "   WHERE shop_id = :shopId AND canceled = FALSE"
                        + "   AND paid_at >= :fromAt AND paid_at < :toExclusiveAt) AS revenue,"
                        // 방문 수: CSV로 가져온 방문 제외, 고객 없는 방문 포함 (FR-CUS-13, E3)
                        + " (SELECT COUNT(*) FROM visit"
                        + "   WHERE shop_id = :shopId AND source = 'NORMAL'"
                        + "   AND visit_date BETWEEN :from AND :to) AS visit_count,"
                        // 신규 고객: 첫 방문일(가져온 방문 포함)이 기간 안. 고객 없는 방문은 고객이 없어 빠진다 (E9, E3)
                        + " (SELECT COUNT(*) FROM (SELECT customer_id FROM visit"
                        + "   WHERE shop_id = :shopId AND customer_id IS NOT NULL"
                        + "   GROUP BY customer_id HAVING MIN(visit_date) BETWEEN :from AND :to) f)"
                        + " AS new_customer_count",
                p,
                (rs, i) ->
                        new Summary(
                                rs.getLong("revenue"),
                                rs.getLong("visit_count"),
                                rs.getLong("new_customer_count")));
    }

    /**
     * 이탈 신호 기간 끝날이 기간 안이고 오늘보다 이전인 예측 스냅샷을 카테고리별로 센다 (F58). 이탈 신호 기간이 빈 스냅샷(F57 이전)은 뺀다. 삭제된 고객의
     * 스냅샷도 넣는다 (F29와 같음). 0건 카테고리도 넣어 다섯 개를 준다.
     *
     * @param to 포함
     */
    @Transactional(readOnly = true)
    public List<CycleRevisit> cycleRevisits(
            long shopId, LocalDate from, LocalDate to, LocalDate today) {
        Map<String, CycleRevisit> byCategory = new HashMap<>();
        jdbc.query(
                "SELECT category, COUNT(*) AS ended,"
                        // 실제 다음 방문일은 주기 계산 제외 방문을 뺀 첫 방문 (F23). 예측 기간보다 이른 방문도 재방문
                        + " SUM(actual_visit_date IS NOT NULL AND actual_visit_date <= "
                        + CHURN_END
                        + ") AS revisited"
                        + " FROM prediction_snapshot"
                        + " WHERE shop_id = :shopId AND churn_signal_days IS NOT NULL"
                        + " AND "
                        + CHURN_END
                        + " BETWEEN :from AND :to AND "
                        + CHURN_END
                        + " < :today GROUP BY category",
                Map.of("shopId", shopId, "from", from, "to", to, "today", today),
                rs -> {
                    String category = rs.getString("category");
                    byCategory.put(
                            category,
                            new CycleRevisit(
                                    category, rs.getLong("ended"), rs.getLong("revisited")));
                });
        return CATEGORIES.stream()
                .map(c -> byCategory.getOrDefault(c, new CycleRevisit(c, 0, 0)))
                .toList();
    }
}
