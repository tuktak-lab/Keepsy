package com.tuktaklab.keepsy.reservation.port;

import com.tuktaklab.keepsy.reservation.AvailableTimes.OpeningHours;
import java.sql.Time;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ponytail: 매장 · 스탭 · 메뉴 · 고객 엔티티와 조회 Service가 아직 없어 V1 · V2 테이블을 직접 읽는다 (FeatureSettingsReader와 같은
 * 방식). 그 도메인에 조회 Service가 생기면 이 어댑터만 그것을 부르게 바꾼다 (DEVELOPMENT 4장 「도메인 연동」)
 */
@Component
@RequiredArgsConstructor
class JdbcReservationReferences implements ReservationReferences {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Optional<OpeningHours> openingHours(long shopId, LocalDate date) {
        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM shop WHERE id = :shopId", Map.of("shopId", shopId));
        // 요일 접두어는 DayOfWeek에서 나온 고정 값이라 사용자 입력이 아니다
        String day = date.getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
        Time open = (Time) row.get(day + "_open_time");
        Time close = (Time) row.get(day + "_close_time");
        if (open == null || close == null) {
            return Optional.empty();
        }
        return Optional.of(new OpeningHours(open.toLocalTime(), close.toLocalTime()));
    }

    @Override
    public boolean isDayOff(long shopId, long staffId, LocalDate date) {
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("shopId", shopId)
                        .addValue("staffId", staffId)
                        .addValue("date", date);
        String regular =
                jdbc.queryForObject(
                        "SELECT regular_days_off FROM staff WHERE id = :staffId AND shop_id = :shopId",
                        p,
                        String.class);
        boolean regularOff =
                Arrays.stream(regular.split(","))
                        .map(String::trim)
                        .anyMatch(d -> d.equals(date.getDayOfWeek().name()));
        Integer temporary =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM day_off WHERE shop_id = :shopId AND off_date = :date"
                                + " AND (staff_id IS NULL OR staff_id = :staffId)",
                        p,
                        Integer.class);
        return regularOff || temporary > 0;
    }

    @Override
    public Optional<StaffState> staff(long shopId, long staffId) {
        return staffStatus(
                "SELECT status FROM staff WHERE id = :staffId AND shop_id = :shopId",
                shopId,
                staffId);
    }

    @Override
    public Optional<StaffState> lockStaff(long shopId, long staffId) {
        return staffStatus(
                "SELECT status FROM staff WHERE id = :staffId AND shop_id = :shopId FOR UPDATE",
                shopId,
                staffId);
    }

    private Optional<StaffState> staffStatus(String sql, long shopId, long staffId) {
        List<String> status =
                jdbc.queryForList(sql, Map.of("shopId", shopId, "staffId", staffId), String.class);
        return status.stream().findFirst().map(s -> new StaffState("ACTIVE".equals(s)));
    }

    @Override
    public Map<Long, Integer> menuDurations(long shopId, Collection<Long> serviceMenuIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (serviceMenuIds.isEmpty()) {
            return result;
        }
        jdbc.query(
                "SELECT id, duration_minutes FROM service_menu WHERE shop_id = :shopId AND id IN (:ids)",
                Map.of("shopId", shopId, "ids", serviceMenuIds),
                rs -> {
                    result.put(rs.getLong("id"), rs.getInt("duration_minutes"));
                });
        return result;
    }

    @Override
    public Optional<CustomerState> customer(long shopId, long customerId) {
        return jdbc
                .query(
                        "SELECT reservation_restricted, reservation_restrict_reason FROM customer"
                                + " WHERE id = :customerId AND shop_id = :shopId AND status = 'ACTIVE'",
                        Map.of("shopId", shopId, "customerId", customerId),
                        (rs, i) ->
                                new CustomerState(
                                        rs.getBoolean("reservation_restricted"),
                                        rs.getString("reservation_restrict_reason")))
                .stream()
                .findFirst();
    }
}
