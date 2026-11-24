package com.tuktaklab.keepsy.analytics.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예측 정확도 (FR-ANL-07, 결정 F29). 예측 스냅샷(prediction 도메인)을 세는 화면용 조회라 조회 전용 SQL로 DB에서 집계한다 (DEVELOPMENT
 * 4장 「도메인 연동」, NFR-01). 삭제된 고객의 스냅샷도 그대로 센다 (FR-CUS-10).
 */
@Repository
@RequiredArgsConstructor
public class PredictionAccuracyQuery {

    /**
     * @param averageErrorDays 실제 방문일이 있는 스냅샷이 없으면 null
     */
    public record Accuracy(long evaluatedCount, long hitCount, BigDecimal averageErrorDays) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 예측 종료일이 기간 안이고 {@code today}보다 이전인(끝난) 스냅샷만 평가한다 (E9, F29).
     *
     * @param to 포함
     * @param today Asia/Seoul 오늘. 종료일이 오늘이면 아직 끝나지 않은 예측이다
     */
    @Transactional(readOnly = true)
    public Accuracy find(long shopId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS evaluated,"
                        // 다음 방문 없이 끝난 스냅샷(hit NULL)은 빗나감: 분모에만 든다 (F29)
                        + " COALESCE(SUM(CASE WHEN hit THEN 1 ELSE 0 END), 0) AS hits,"
                        // |실제 − 중앙일| = |(실제 − 시작) + (실제 − 종료)| / 2. 실제 방문일이 없으면 AVG가 뺀다 (F29)
                        + " ROUND(AVG(ABS(DATEDIFF(actual_visit_date, start_date)"
                        + "   + DATEDIFF(actual_visit_date, end_date)) / 2), 1) AS avg_error"
                        + " FROM prediction_snapshot"
                        + " WHERE shop_id = :shopId AND end_date BETWEEN :from AND :to"
                        + " AND end_date < :today",
                Map.of("shopId", shopId, "from", from, "to", to, "today", today),
                (rs, i) ->
                        new Accuracy(
                                rs.getLong("evaluated"),
                                rs.getLong("hits"),
                                rs.getBigDecimal("avg_error")));
    }
}
