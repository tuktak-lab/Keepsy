package com.tuktaklab.keepsy.dashboard.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 오늘 예약 · 마감 안 된 예약 · 이번 주 요약 (FR-DSH-01 · 03 · 05, CONTRACTS 6장 「성과 · 대시보드 · 검색」). 예약 · 스탭 ·
 * 고객 · 메뉴를 함께 읽는 화면용 횡단 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」).
 */
@Repository
@RequiredArgsConstructor
public class DashboardTodayQuery {

    /** 예약 상태(CONTRACTS 3장). 요일별 건수는 0건 상태도 넣는다 */
    static final List<String> STATUSES = List.of("CONFIRMED", "COMPLETED", "CANCELED", "NO_SHOW");

    public record Today(List<Item> today, List<Item> unclosed, List<Day> week) {}

    public record Item(
            long id,
            String status,
            OffsetDateTime startAt,
            int minutes,
            Staff staff,
            boolean consultFirst,
            String channel,
            Customer customer,
            List<Service> services) {}

    public record Staff(long id, String name) {}

    public record Customer(long id, String name, boolean hasCaution) {}

    public record Service(long serviceMenuId, String name) {}

    public record Day(LocalDate date, Map<String, Integer> counts) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param now Asia/Seoul 지금. "오늘" · "이번 주"도 여기서 구한다
     */
    @Transactional(readOnly = true)
    public Today find(long shopId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        Map<Long, List<Service>> servicesById = new HashMap<>();
        List<Item> todayItems =
                items(
                        "r.start_at >= :from AND r.start_at < :to",
                        Map.of(
                                "shopId",
                                shopId,
                                "from",
                                today.atStartOfDay(),
                                "to",
                                today.plusDays(1).atStartOfDay()),
                        servicesById);
        // 마감 안 된 예약: 시작 시각이 지났는데 확정인 예약 전부, 고객 없는 예약 포함 (FR-DSH-05, E4)
        List<Item> unclosed =
                items(
                        "r.status = 'CONFIRMED' AND r.start_at < :now",
                        Map.of("shopId", shopId, "now", now),
                        servicesById);
        if (!servicesById.isEmpty()) {
            // 예정 시술은 한 번에 읽는다 (반복문 안 조회 없음, DEVELOPMENT 4장)
            jdbc.query(
                    "SELECT rs.reservation_id, m.id, m.name FROM reservation_service rs"
                            + " JOIN service_menu m ON m.id = rs.service_menu_id"
                            + " WHERE rs.shop_id = :shopId AND rs.reservation_id IN (:ids)"
                            + " ORDER BY rs.id",
                    Map.of("shopId", shopId, "ids", servicesById.keySet()),
                    rs -> {
                        servicesById
                                .get(rs.getLong("reservation_id"))
                                .add(new Service(rs.getLong("id"), rs.getString("name")));
                    });
        }
        return new Today(todayItems, unclosed, week(shopId, today));
    }

    private List<Item> items(
            String condition, Map<String, Object> params, Map<Long, List<Service>> servicesById) {
        List<Item> items = new ArrayList<>();
        jdbc.query(
                "SELECT r.id, r.status, r.start_at, r.end_at, r.consult_first, r.channel,"
                        + " st.id AS staff_id, st.name AS staff_name, c.id AS customer_id,"
                        + " c.name AS customer_name,"
                        + " (COALESCE(c.service_caution, '') <> '' OR COALESCE(c.health_caution, '') <> '')"
                        + " AS has_caution"
                        + " FROM reservation r JOIN staff st ON st.id = r.staff_id"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId AND "
                        + condition
                        + " ORDER BY r.start_at, r.id",
                params,
                rs -> {
                    LocalDateTime start = rs.getObject("start_at", LocalDateTime.class);
                    LocalDateTime end = rs.getObject("end_at", LocalDateTime.class);
                    Long customerId = rs.getObject("customer_id", Long.class);
                    items.add(
                            new Item(
                                    rs.getLong("id"),
                                    rs.getString("status"),
                                    TimeConfig.offset(start),
                                    (int) Duration.between(start, end).toMinutes(),
                                    new Staff(rs.getLong("staff_id"), rs.getString("staff_name")),
                                    rs.getBoolean("consult_first"),
                                    rs.getString("channel"),
                                    customerId == null
                                            ? null
                                            : new Customer(
                                                    customerId,
                                                    rs.getString("customer_name"),
                                                    rs.getBoolean("has_caution")),
                                    // 오늘 · 마감 안 된 목록에 같은 예약이 있으면 시술 목록을 함께 쓴다
                                    servicesById.computeIfAbsent(
                                            rs.getLong("id"), id -> new ArrayList<>())));
                });
        return items;
    }

    /** 이번 주 월요일부터 7일, 날마다 상태별 건수 (FR-DSH-03, F10) */
    private List<Day> week(long shopId, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<LocalDate, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Integer> byStatus = new LinkedHashMap<>();
            STATUSES.forEach(s -> byStatus.put(s, 0));
            counts.put(monday.plusDays(i), byStatus);
        }
        jdbc.query(
                "SELECT DATE(start_at) AS day, status, COUNT(*) AS n FROM reservation"
                        + " WHERE shop_id = :shopId AND start_at >= :from AND start_at < :to"
                        + " GROUP BY DATE(start_at), status",
                Map.of(
                        "shopId",
                        shopId,
                        "from",
                        monday.atStartOfDay(),
                        "to",
                        monday.plusDays(7).atStartOfDay()),
                rs -> {
                    counts.get(rs.getObject("day", LocalDate.class))
                            .put(rs.getString("status"), rs.getInt("n"));
                });
        return counts.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue())).toList();
    }
}
