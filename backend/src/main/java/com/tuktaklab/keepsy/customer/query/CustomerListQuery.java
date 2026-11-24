package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.prediction.PredictionStatus;
import com.tuktaklab.keepsy.prediction.PredictionStatusSql;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 고객 목록 · 검색 · 필터 (FR-CUS-02, CONTRACTS 6장 「고객 (CUS)」). 방문 · 예측 · 동의 · 스탭을 함께 거르는 화면용 횡단 조회라 조회 전용
 * SQL 하나로 걸러 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 메모리에서 거르거나 정렬하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class CustomerListQuery {

    /** null인 조건은 쓰지 않는다 */
    public record Filter(
            String nameContains,
            String phoneLast4,
            Integer visitCountMin,
            Integer visitCountMax,
            String category,
            PredictionStatus predictionStatus,
            Long staffId,
            Boolean marketingConsent) {}

    public record Row(
            long customerId,
            String name,
            String phone,
            int visitCount,
            LocalDate lastVisitDate,
            boolean marketingConsent) {}

    private static final String VISIT_COUNT =
            "(SELECT COUNT(*) FROM visit v WHERE v.customer_id = c.id)";

    private static final String MARKETING_CONSENT =
            "COALESCE((SELECT cs.agreed FROM consent cs WHERE cs.customer_id = c.id"
                    + " AND cs.item = 'MARKETING' ORDER BY cs.id DESC LIMIT 1), FALSE)";

    /** FR-PRD-04 규칙은 prediction 패키지가 둔다 */
    private static final String PREDICTION_STATUS = PredictionStatusSql.EXPRESSION;

    private final NamedParameterJdbcTemplate jdbc;

    public List<Row> find(long shopId, Filter f, LocalDate today, int page, int size) {
        MapSqlParameterSource p = params(shopId, f, today);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(
                "SELECT c.id, c.name, c.phone, "
                        + VISIT_COUNT
                        + " AS visit_count,"
                        + " (SELECT MAX(v.visit_date) FROM visit v WHERE v.customer_id = c.id) AS last_visit,"
                        + MARKETING_CONSENT
                        + " AS marketing"
                        + from(f)
                        + " ORDER BY c.id DESC LIMIT :limit OFFSET :offset",
                p,
                (rs, i) -> {
                    Date last = rs.getDate("last_visit");
                    return new Row(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("phone"),
                            rs.getInt("visit_count"),
                            last == null ? null : last.toLocalDate(),
                            rs.getBoolean("marketing"));
                });
    }

    public long count(long shopId, Filter f, LocalDate today) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + from(f), params(shopId, f, today), Long.class);
        return n == null ? 0 : n;
    }

    /** 조건 칼럼 · 문장은 고정 문자열이고, 사용자 값은 모두 바인딩한다 (NFR-08) */
    private static String from(Filter f) {
        StringBuilder sql =
                new StringBuilder(
                        " FROM customer c JOIN shop s ON s.id = c.shop_id"
                                + " WHERE c.shop_id = :shopId AND c.status = 'ACTIVE'");
        if (f.phoneLast4() != null) {
            sql.append(" AND c.phone_last4 = :phoneLast4");
        }
        if (f.nameContains() != null) {
            sql.append(" AND c.name LIKE :namePattern");
        }
        if (f.visitCountMin() != null) {
            sql.append(" AND ").append(VISIT_COUNT).append(" >= :visitCountMin");
        }
        if (f.visitCountMax() != null) {
            sql.append(" AND ").append(VISIT_COUNT).append(" <= :visitCountMax");
        }
        if (f.category() != null) {
            sql.append(
                    " AND EXISTS (SELECT 1 FROM visit_service vs JOIN visit v ON v.id = vs.visit_id"
                            + " WHERE v.customer_id = c.id AND vs.category = :category)");
        }
        if (f.predictionStatus() != null) {
            sql.append(
                    " AND EXISTS (SELECT 1 FROM customer_prediction cp WHERE cp.customer_id = c.id");
            if (f.category() != null) {
                sql.append(" AND cp.category = :category");
            }
            sql.append(" AND (").append(PREDICTION_STATUS).append(") = :predictionStatus)");
        }
        if (f.staffId() != null) {
            sql.append(
                    " AND EXISTS (SELECT 1 FROM visit_service vs WHERE vs.staff_id = :staffId"
                            + " AND vs.visit_id = (SELECT v.id FROM visit v WHERE v.customer_id = c.id"
                            + " ORDER BY v.visit_date DESC, v.id DESC LIMIT 1))");
        }
        if (f.marketingConsent() != null) {
            sql.append(" AND ").append(MARKETING_CONSENT).append(" = :marketingConsent");
        }
        return sql.toString();
    }

    private static MapSqlParameterSource params(long shopId, Filter f, LocalDate today) {
        return new MapSqlParameterSource()
                .addValue("shopId", shopId)
                .addValue("today", today)
                .addValue("phoneLast4", f.phoneLast4())
                .addValue(
                        "namePattern",
                        f.nameContains() == null ? null : "%" + escapeLike(f.nameContains()) + "%")
                .addValue("visitCountMin", f.visitCountMin())
                .addValue("visitCountMax", f.visitCountMax())
                .addValue("category", f.category())
                .addValue(
                        "predictionStatus",
                        f.predictionStatus() == null ? null : f.predictionStatus().name())
                .addValue("staffId", f.staffId())
                .addValue("marketingConsent", f.marketingConsent());
    }

    /** LIKE 특수 문자를 글자 그대로 찾게 한다 (MySQL 기본 escape 문자 \) */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
