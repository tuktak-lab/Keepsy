package com.tuktaklab.keepsy.account.port;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ponytail: 예약 도메인에 이 조회를 하는 공개 Service가 없어 V2 · V3 테이블을 읽기만 한다 (JdbcReservationReferences와 같은 방식).
 * 예약 도메인에 공개 메서드가 생기면 이 어댑터만 그것을 부르게 바꾼다 (DEVELOPMENT 4장 「도메인 연동」)
 */
@Component
@RequiredArgsConstructor
class JdbcStaffReservations implements StaffReservations {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<Booked> confirmedOn(
            long shopId, long staffId, Collection<DayOfWeek> days, LocalDateTime from) {
        if (days.isEmpty()) {
            return List.of();
        }
        // MySQL DAYOFWEEK: 일요일 1 … 토요일 7
        List<Integer> mysqlDays = days.stream().map(d -> d.getValue() % 7 + 1).toList();
        return jdbc.query(
                "SELECT r.id, r.start_at, c.name FROM reservation r"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId AND r.staff_id = :staffId"
                        + " AND r.status = 'CONFIRMED' AND r.start_at >= :from"
                        + " AND DAYOFWEEK(r.start_at) IN (:days) ORDER BY r.start_at, r.id",
                new MapSqlParameterSource()
                        .addValue("shopId", shopId)
                        .addValue("staffId", staffId)
                        .addValue("from", from)
                        .addValue("days", mysqlDays),
                (rs, i) ->
                        new Booked(
                                rs.getLong("id"),
                                rs.getTimestamp("start_at").toLocalDateTime(),
                                rs.getString("name")));
    }

    @Override
    public List<Booked> allConfirmed(long shopId, long staffId) {
        return jdbc.query(
                "SELECT r.id, r.start_at, c.name FROM reservation r"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId AND r.staff_id = :staffId"
                        + " AND r.status = 'CONFIRMED' ORDER BY r.start_at, r.id",
                new MapSqlParameterSource().addValue("shopId", shopId).addValue("staffId", staffId),
                (rs, i) ->
                        new Booked(
                                rs.getLong("id"),
                                rs.getTimestamp("start_at").toLocalDateTime(),
                                rs.getString("name")));
    }

    @Override
    public List<Timed> shopConfirmedFrom(long shopId, LocalDateTime from) {
        return jdbc.query(
                "SELECT r.id, r.start_at, r.end_at, c.name FROM reservation r"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId AND r.status = 'CONFIRMED'"
                        + " AND r.start_at >= :from ORDER BY r.start_at, r.id",
                new MapSqlParameterSource().addValue("shopId", shopId).addValue("from", from),
                (rs, i) ->
                        new Timed(
                                rs.getLong("id"),
                                rs.getTimestamp("start_at").toLocalDateTime(),
                                rs.getTimestamp("end_at").toLocalDateTime(),
                                rs.getString("name")));
    }
}
