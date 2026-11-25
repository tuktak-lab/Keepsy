package com.tuktaklab.keepsy.prediction.query;

import com.tuktaklab.keepsy.prediction.HandlingMark;
import com.tuktaklab.keepsy.prediction.ManagementPrioritySql;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import com.tuktaklab.keepsy.prediction.PredictionStatusSql;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 인사이트 목록 (FR-PRD-05, CONTRACTS 6장 「예측 · 인사이트 (PRD)」). 예측 · 고객 · 동의 · 예약 · 결제를 함께 보는 화면용 횡단 조회라 조회
 * 전용 SQL 하나로 거르고 정렬한다 (DEVELOPMENT 4장 「도메인 연동」). 상태는 {@link PredictionStatusSql}, 정렬은 {@link
 * ManagementPrioritySql}.
 */
@Repository
@RequiredArgsConstructor
public class InsightQuery {

    /**
     * @param marketingConsent 마케팅 동의 최신 행이 동의인지 (받은 적 없으면 false)
     * @param marketingWithdrawn 최신 행이 철회 경로가 있는 철회인지 (F19)
     */
    public record Row(
            long customerId,
            String name,
            String category,
            PredictionStatus status,
            LocalDate lastServiceDate,
            LocalDate startDate,
            LocalDate endDate,
            boolean marketingConsent,
            boolean marketingWithdrawn,
            HandlingMark handlingMark,
            LocalDateTime handlingMarkedAt) {}

    private static final String STATUS = "(" + PredictionStatusSql.EXPRESSION + ")";

    private static final String LATEST_MARKETING =
            " FROM consent cs WHERE cs.customer_id = c.id AND cs.item = 'MARKETING'"
                    + " ORDER BY cs.id DESC LIMIT 1";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param statuses 보여 줄 상태 (예측 없음 제외)
     * @param category null이면 모든 카테고리
     */
    public List<Row> find(
            long shopId,
            Collection<PredictionStatus> statuses,
            String category,
            LocalDate today,
            int page,
            int size) {
        MapSqlParameterSource p = params(shopId, statuses, category, today);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(
                "SELECT c.id, c.name, cp.category, cp.last_service_date, cp.start_date,"
                        + " cp.end_date, cp.handling_mark, cp.handling_marked_at, "
                        + STATUS
                        + " AS status,"
                        + " COALESCE((SELECT cs.agreed"
                        + LATEST_MARKETING
                        + "), FALSE) AS consent,"
                        + " COALESCE((SELECT NOT cs.agreed AND cs.withdrawal_path IS NOT NULL"
                        + LATEST_MARKETING
                        + "), FALSE) AS withdrawn"
                        + from(category)
                        + " ORDER BY "
                        + ManagementPrioritySql.ORDER_BY
                        + " LIMIT :limit OFFSET :offset",
                p,
                (rs, i) ->
                        new Row(
                                rs.getLong("id"),
                                rs.getString("name"),
                                rs.getString("category"),
                                PredictionStatus.valueOf(rs.getString("status")),
                                rs.getObject("last_service_date", LocalDate.class),
                                rs.getObject("start_date", LocalDate.class),
                                rs.getObject("end_date", LocalDate.class),
                                rs.getBoolean("consent"),
                                rs.getBoolean("withdrawn"),
                                rs.getString("handling_mark") == null
                                        ? null
                                        : HandlingMark.valueOf(rs.getString("handling_mark")),
                                rs.getObject("handling_marked_at", LocalDateTime.class)));
    }

    public long count(
            long shopId, Collection<PredictionStatus> statuses, String category, LocalDate today) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + from(category),
                        params(shopId, statuses, category, today),
                        Long.class);
        return n == null ? 0 : n;
    }

    /** 다음 예약이 있는 행은 상태가 'NONE'이라, 예측 없음 행은 'NO_PREDICTION'이라 상태 목록에 걸리지 않는다 */
    private static String from(String category) {
        return " FROM customer_prediction cp JOIN customer c ON c.id = cp.customer_id"
                + " JOIN shop s ON s.id = c.shop_id"
                + " WHERE cp.shop_id = :shopId AND c.status = 'ACTIVE'"
                // 관리 종료 행은 숨긴다. 페이지 · 전체 건수도 뺀 뒤 기준 (FR-PRD-07, F55)
                + " AND (cp.handling_mark IS NULL OR cp.handling_mark <> 'CLOSED')"
                + (category == null ? "" : " AND cp.category = :category")
                + " AND "
                + STATUS
                + " IN (:statuses)";
    }

    private static MapSqlParameterSource params(
            long shopId, Collection<PredictionStatus> statuses, String category, LocalDate today) {
        return new MapSqlParameterSource("shopId", shopId)
                .addValue("statuses", statuses.stream().map(Enum::name).toList())
                .addValue("category", category)
                .addValue("today", today);
    }
}
