package com.tuktaklab.keepsy.analytics.query;

import java.time.LocalDate;
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
}
