package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.Customer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 고객 상세 (FR-CUS-03 · 04 · 05 · 11, CONTRACTS 6장 「고객 (CUS)」). 노쇼 횟수 · 방문 요약을 예약 · 방문 · 예측에서 함께 읽는
 * 화면용 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 세션 매장 · 정상 고객만 (NFR-03, C12).
 */
@Repository
@RequiredArgsConstructor
public class CustomerDetailQuery {

    public record PreferenceTag(String category, String tag) {}

    /** 방문 요약 (FR-CUS-11, CONTRACTS 6장 「`visitSummary` 필드」). 방문이 없으면 0 · null · 빈 배열 */
    public record VisitSummary(
            int visitCount,
            int serviceCount,
            LocalDate firstVisitDate,
            LocalDate lastVisitDate,
            List<CategorySummary> categories,
            List<MenuCount> menus) {}

    /** {@code averageIntervalDays}: 근거 방문 3회 미만이면 null (화면 "—") */
    public record CategorySummary(String category, int visitCount, Integer averageIntervalDays) {}

    /** 메뉴 없는 가져온 방문이면 {@code serviceMenuId} · {@code name}이 null (화면 "메뉴 미상", A13) */
    public record MenuCount(Long serviceMenuId, String name, String category, int count) {}

    /** CONTRACTS 3장 「시술 카테고리」 순 */
    private static final String CATEGORY_ORDER =
            "FIELD(vs.category, 'NAIL', 'LASH', 'WAXING', 'SEMI_PERMANENT', 'ETC')";

    /** CONTRACTS 6장 「`GET /api/customers/{customerId}`」 응답. 휴대폰은 마스킹한 값 (NFR-04) */
    public record Detail(
            long customerId,
            String name,
            String phone,
            Customer.Gender gender,
            Integer birthYear,
            Customer.PreferredLanguage preferredLanguage,
            Customer.AcquisitionChannel acquisitionChannel,
            OffsetDateTime registeredAt,
            String serviceCaution,
            String healthCaution,
            int noShowCount,
            boolean bookingRestricted,
            String bookingRestrictionReason,
            List<PreferenceTag> preferenceTags,
            String preferenceMemo,
            VisitSummary visitSummary) {}

    private final NamedParameterJdbcTemplate jdbc;

    /** 다른 매장 · 삭제된 고객이면 비어 있다 */
    public Optional<Detail> find(long shopId, long customerId) {
        Map<String, Long> p = Map.of("shopId", shopId, "customerId", customerId);
        List<PreferenceTag> tags =
                jdbc.query(
                        "SELECT category, tag FROM preference_tag"
                                + " WHERE shop_id = :shopId AND customer_id = :customerId"
                                + " ORDER BY category, tag",
                        p,
                        (rs, i) ->
                                new PreferenceTag(rs.getString("category"), rs.getString("tag")));
        VisitSummary summary = visitSummary(p);
        return jdbc
                .query(
                        "SELECT c.*, (SELECT COUNT(*) FROM reservation r WHERE r.customer_id = c.id"
                                + " AND r.status = 'NO_SHOW') AS no_show_count"
                                + " FROM customer c"
                                + " WHERE c.id = :customerId AND c.shop_id = :shopId AND c.status = 'ACTIVE'",
                        p,
                        (rs, i) -> {
                            boolean restricted = rs.getBoolean("reservation_restricted");
                            return new Detail(
                                    rs.getLong("id"),
                                    rs.getString("name"),
                                    Customer.maskPhone(rs.getString("phone")),
                                    valueOf(Customer.Gender.class, rs.getString("gender")),
                                    rs.getObject("birth_year", Integer.class),
                                    valueOf(
                                            Customer.PreferredLanguage.class,
                                            rs.getString("preferred_language")),
                                    valueOf(
                                            Customer.AcquisitionChannel.class,
                                            rs.getString("acquisition_channel")),
                                    TimeConfig.offset(
                                            rs.getObject("registered_at", LocalDateTime.class)),
                                    rs.getString("service_caution"),
                                    rs.getString("health_caution"),
                                    rs.getInt("no_show_count"),
                                    restricted,
                                    restricted ? rs.getString("reservation_restrict_reason") : null,
                                    tags,
                                    rs.getString("preference_memo"),
                                    summary);
                        })
                .stream()
                .findFirst();
    }

    /** 방문 · 시술 건수에는 주기 계산 제외 방문 · 가져온 방문도 들어간다 (FR-CUS-11, C6, E5) */
    private VisitSummary visitSummary(Map<String, Long> p) {
        List<CategorySummary> categories =
                jdbc.query(
                        // 평균 간격은 예측과 같은 값 (명세서 5장 customer_prediction). 근거 방문 3회 미만이면 "—"
                        "SELECT vs.category, COUNT(DISTINCT v.id) AS visit_count,"
                                + " MAX(CASE WHEN cp.basis_visit_count >= 3 THEN cp.avg_interval_days END)"
                                + " AS avg_interval"
                                + " FROM visit v JOIN visit_service vs ON vs.visit_id = v.id"
                                + " LEFT JOIN customer_prediction cp"
                                + " ON cp.customer_id = v.customer_id AND cp.category = vs.category"
                                + " WHERE v.shop_id = :shopId AND v.customer_id = :customerId"
                                + " GROUP BY vs.category ORDER BY "
                                + CATEGORY_ORDER,
                        p,
                        (rs, i) ->
                                new CategorySummary(
                                        rs.getString("category"),
                                        rs.getInt("visit_count"),
                                        rs.getObject("avg_interval", Integer.class)));
        List<MenuCount> menus =
                jdbc.query(
                        "SELECT vs.service_menu_id, m.name, vs.category, COUNT(*) AS cnt"
                                + " FROM visit v JOIN visit_service vs ON vs.visit_id = v.id"
                                + " LEFT JOIN service_menu m ON m.id = vs.service_menu_id"
                                + " WHERE v.shop_id = :shopId AND v.customer_id = :customerId"
                                + " GROUP BY vs.service_menu_id, m.name, vs.category"
                                + " ORDER BY cnt DESC, m.name IS NULL, m.name, "
                                + CATEGORY_ORDER,
                        p,
                        (rs, i) ->
                                new MenuCount(
                                        rs.getObject("service_menu_id", Long.class),
                                        rs.getString("name"),
                                        rs.getString("category"),
                                        rs.getInt("cnt")));
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS visit_count, MIN(v.visit_date) AS first_visit,"
                        + " MAX(v.visit_date) AS last_visit,"
                        + " (SELECT COUNT(*) FROM visit_service vs JOIN visit sv ON sv.id = vs.visit_id"
                        + " WHERE sv.shop_id = :shopId AND sv.customer_id = :customerId) AS service_count"
                        + " FROM visit v WHERE v.shop_id = :shopId AND v.customer_id = :customerId",
                p,
                (rs, i) ->
                        new VisitSummary(
                                rs.getInt("visit_count"),
                                rs.getInt("service_count"),
                                rs.getObject("first_visit", LocalDate.class),
                                rs.getObject("last_visit", LocalDate.class),
                                categories,
                                menus));
    }

    private static <E extends Enum<E>> E valueOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
