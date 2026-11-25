package com.tuktaklab.keepsy.customer.query;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 파기 대상 (FR-CUS-12, D3, B6, CONTRACTS 6장 「고객 (CUS)」). 고객 · 방문 · 예약을 함께 읽는 화면용 조회라 조회 전용 SQL 하나로 거른다
 * (DEVELOPMENT 4장 「도메인 연동」). 마지막 방문일은 갱신되지 않는 {@code customer.last_visit_date} 대신 방문 표에서 센다.
 */
@Repository
@RequiredArgsConstructor
public class RetentionExpiredQuery {

    /** 기준일의 종류 (CONTRACTS 6장) */
    public enum BaseKind {
        VISIT,
        RESERVATION,
        REGISTERED
    }

    /** CONTRACTS 6장 「`GET /api/customers/retention-expired` 항목 필드」. 번호는 마스킹한다 (NFR-04) */
    public record Row(
            long customerId, String name, String phone, LocalDate baseDate, BaseKind baseKind) {}

    /** 기준일: 마지막 방문일 → 없으면 상태 무관 마지막 예약 시작일 → 없으면 등록일 */
    private static final String BASE =
            "SELECT b.*, COALESCE(b.last_visit, b.last_reservation, b.registered) AS base_date"
                    + " FROM (SELECT c.id, c.name,"
                    + "   CONCAT(LEFT(c.phone, 4), '****', SUBSTRING(c.phone, 9)) AS masked_phone,"
                    + "   (SELECT MAX(v.visit_date) FROM visit v WHERE v.customer_id = c.id) AS last_visit,"
                    + "   (SELECT DATE(MAX(r.start_at)) FROM reservation r"
                    + "     WHERE r.customer_id = c.id) AS last_reservation,"
                    + "   DATE(c.registered_at) AS registered"
                    + "   FROM customer c WHERE c.shop_id = :shopId AND c.status = 'ACTIVE') b";

    /** 기준일 + 보관 기간이 오늘보다 이전 */
    private static final String EXPIRED =
            "SELECT * FROM ("
                    + BASE
                    + ") e WHERE DATE_ADD(e.base_date, INTERVAL :years YEAR) < :today";

    private final NamedParameterJdbcTemplate jdbc;

    /** 기준일이 오래된 순, 같으면 고객 순 */
    public List<Row> find(long shopId, int years, LocalDate today, int page, int size) {
        return jdbc.query(
                EXPIRED + " ORDER BY base_date, id LIMIT :limit OFFSET :offset",
                params(shopId, years, today)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                (rs, i) ->
                        new Row(
                                rs.getLong("id"),
                                rs.getString("name"),
                                rs.getString("masked_phone"),
                                rs.getObject("base_date", LocalDate.class),
                                rs.getObject("last_visit") != null
                                        ? BaseKind.VISIT
                                        : rs.getObject("last_reservation") != null
                                                ? BaseKind.RESERVATION
                                                : BaseKind.REGISTERED));
    }

    public long count(long shopId, int years, LocalDate today) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (" + EXPIRED + ") x",
                        params(shopId, years, today),
                        Long.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource params(long shopId, int years, LocalDate today) {
        return new MapSqlParameterSource("shopId", shopId)
                .addValue("years", years)
                .addValue("today", today);
    }
}
