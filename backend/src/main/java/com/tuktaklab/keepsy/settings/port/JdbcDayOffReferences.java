package com.tuktaklab.keepsy.settings.port;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ponytail: account · 예약 도메인에 이 조회를 하는 공개 Service가 없어 staff · reservation 테이블을 읽기만 한다
 * (JdbcStaffReservations와 같은 방식). 공개 메서드가 생기면 이 어댑터만 그것을 부르게 바꾼다 (DEVELOPMENT 4장 「도메인 연동」)
 */
@Component
@RequiredArgsConstructor
class JdbcDayOffReferences implements DayOffReferences {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Optional<Boolean> staffActive(long shopId, long staffId) {
        return jdbc
                .queryForList(
                        "SELECT status FROM staff WHERE id = :staffId AND shop_id = :shopId",
                        new MapSqlParameterSource("staffId", staffId).addValue("shopId", shopId),
                        String.class)
                .stream()
                .findFirst()
                .map("ACTIVE"::equals);
    }

    @Override
    public List<Booked> confirmedOn(long shopId, Long staffId, LocalDate date, LocalDateTime from) {
        LocalDateTime dayStart = date.atStartOfDay();
        return jdbc.query(
                "SELECT r.id, r.start_at, c.name FROM reservation r"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId AND r.status = 'CONFIRMED'"
                        + " AND r.start_at >= :dayStart AND r.start_at < :nextDay AND r.start_at >= :from"
                        + (staffId == null ? "" : " AND r.staff_id = :staffId")
                        + " ORDER BY r.start_at, r.id",
                new MapSqlParameterSource()
                        .addValue("shopId", shopId)
                        .addValue("staffId", staffId)
                        .addValue("dayStart", dayStart)
                        .addValue("nextDay", dayStart.plusDays(1))
                        .addValue("from", from),
                (rs, i) ->
                        new Booked(
                                rs.getLong("id"),
                                rs.getTimestamp("start_at").toLocalDateTime(),
                                rs.getString("name")));
    }
}
