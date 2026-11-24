package com.tuktaklab.keepsy.prediction.port;

import com.tuktaklab.keepsy.prediction.CycleRange;
import com.tuktaklab.keepsy.prediction.PredictionAttribute;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** V1 · V2 · V3 테이블을 읽기만 한다. 다른 도메인의 엔티티 · Repository는 쓰지 않는다 (DEVELOPMENT 4장 「도메인 연동」) */
@Component
@RequiredArgsConstructor
class JdbcPredictionVisits implements PredictionVisits {

    private static final List<String> CATEGORIES =
            List.of("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC");

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<VisitedService> visitedServices(long shopId, Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT v.customer_id, v.visit_date, vs.category, vs.cycle_excluded,"
                        + " m.prediction_attribute, m.cycle_min_days, m.cycle_max_days"
                        + " FROM visit v"
                        + " JOIN customer c ON c.id = v.customer_id AND c.status = 'ACTIVE'"
                        + " JOIN visit_service vs ON vs.visit_id = v.id"
                        + " LEFT JOIN service_menu m ON m.id = vs.service_menu_id"
                        + " WHERE v.shop_id = :shopId AND v.customer_id IN (:ids)",
                new MapSqlParameterSource("shopId", shopId).addValue("ids", customerIds),
                (rs, i) -> {
                    String attr = rs.getString("prediction_attribute");
                    return new VisitedService(
                            rs.getLong("customer_id"),
                            rs.getDate("visit_date").toLocalDate(),
                            rs.getString("category"),
                            attr == null ? null : PredictionAttribute.valueOf(attr),
                            rs.getBoolean("cycle_excluded"),
                            attr == null
                                    ? null
                                    : new CycleRange(
                                            rs.getInt("cycle_min_days"),
                                            rs.getInt("cycle_max_days")));
                });
    }

    @Override
    public Map<String, CycleRange> categoryDefaults(long shopId) {
        return jdbc.queryForObject(
                "SELECT * FROM shop WHERE id = :shopId",
                Map.of("shopId", shopId),
                (rs, i) -> {
                    Map<String, CycleRange> m = new HashMap<>();
                    for (String c : CATEGORIES) {
                        String col = c.toLowerCase();
                        m.put(
                                c,
                                new CycleRange(
                                        rs.getInt(col + "_cycle_min_days"),
                                        rs.getInt(col + "_cycle_max_days")));
                    }
                    return m;
                });
    }

    @Override
    public List<Long> customersWithMenu(long shopId, long menuId) {
        return jdbc.queryForList(
                "SELECT DISTINCT v.customer_id FROM visit_service vs JOIN visit v ON v.id = vs.visit_id"
                        + " WHERE vs.shop_id = :shopId AND vs.service_menu_id = :menuId"
                        + " AND v.customer_id IS NOT NULL",
                new MapSqlParameterSource("shopId", shopId).addValue("menuId", menuId),
                Long.class);
    }

    @Override
    public List<Long> customersWithMenulessVisits(long shopId, String category) {
        return jdbc.queryForList(
                "SELECT DISTINCT v.customer_id FROM visit_service vs JOIN visit v ON v.id = vs.visit_id"
                        + " WHERE vs.shop_id = :shopId AND vs.category = :category"
                        + " AND vs.service_menu_id IS NULL AND v.customer_id IS NOT NULL",
                new MapSqlParameterSource("shopId", shopId).addValue("category", category),
                Long.class);
    }
}
