package com.tuktaklab.keepsy.reservation.query;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기간 예약 · 주간 캘린더 · 일간 보기 (FR-RSV-02 · 05)와 고객의 예약 목록 (FR-CUS-03), CONTRACTS 6장 「예약 (RSV)」. 예약 · 스탭
 * · 고객 · 메뉴를 함께 읽는 화면용 횡단 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 기간 조회는 최대 7일, 페이징
 * 없음 (F13).
 */
@Repository
@RequiredArgsConstructor
public class ReservationCalendarQuery {

    static final int MAX_DAYS = 7;

    public record Item(
            long id,
            String status,
            OffsetDateTime startAt,
            int minutes,
            Ref staff,
            boolean consultFirst,
            String channel,
            Ref customer,
            List<Service> services) {}

    public record Ref(long id, String name) {}

    public record Service(long serviceMenuId, String name) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param to 포함
     * @throws ApiException {@code from} > {@code to}이거나 7일을 넘으면 {@code invalid}
     */
    @Transactional(readOnly = true)
    public List<Item> find(long shopId, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ApiException(ErrorCode.INVALID, "기간은 7일 이내로 조회해 주세요");
        }
        return items(
                " AND r.start_at >= :from AND r.start_at < :toExclusive ORDER BY r.start_at, r.id",
                Map.of(
                        "shopId", shopId,
                        "from", from.atStartOfDay(),
                        "toExclusive", to.plusDays(1).atStartOfDay()));
    }

    /**
     * 고객의 예약 목록 (FR-CUS-03, CONTRACTS 6장 「예약 (RSV)」). 시작 시각이 늦은 순, 상태와 관계없이.
     *
     * @throws ApiException 다른 매장 · 없는 · 삭제된 고객이면 {@code not_found}
     */
    @Transactional(readOnly = true)
    public PageResponse<Item> findByCustomer(long shopId, long customerId, int page, int size) {
        Map<String, Object> p = Map.of("shopId", shopId, "customerId", customerId);
        if (jdbc.queryForList(
                        "SELECT 1 FROM customer WHERE id = :customerId AND shop_id = :shopId"
                                + " AND status = 'ACTIVE'",
                        p)
                .isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reservation r WHERE r.shop_id = :shopId"
                                + " AND r.customer_id = :customerId",
                        p,
                        Long.class);
        Map<String, Object> paged = new HashMap<>(p);
        paged.put("limit", size);
        paged.put("offset", (long) page * size);
        return new PageResponse<>(
                items(
                        " AND r.customer_id = :customerId ORDER BY r.start_at DESC, r.id DESC"
                                + " LIMIT :limit OFFSET :offset",
                        paged),
                page,
                size,
                total);
    }

    /**
     * @param tail 매장 조건 뒤에 붙일 조건 · 정렬 (사용자 입력을 넣지 않는다)
     */
    private List<Item> items(String tail, Map<String, Object> p) {
        List<Item> items = new ArrayList<>();
        Map<Long, List<Service>> servicesById = new HashMap<>();
        jdbc.query(
                "SELECT r.id, r.status, r.start_at, r.end_at, r.consult_first, r.channel,"
                        + " st.id AS staff_id, st.name AS staff_name, c.id AS customer_id, c.name AS customer_name"
                        + " FROM reservation r JOIN staff st ON st.id = r.staff_id"
                        + " LEFT JOIN customer c ON c.id = r.customer_id"
                        + " WHERE r.shop_id = :shopId"
                        + tail,
                p,
                rs -> {
                    LocalDateTime start = rs.getObject("start_at", LocalDateTime.class);
                    LocalDateTime end = rs.getObject("end_at", LocalDateTime.class);
                    Long customerId = rs.getObject("customer_id", Long.class);
                    List<Service> services = new ArrayList<>();
                    servicesById.put(rs.getLong("id"), services);
                    items.add(
                            new Item(
                                    rs.getLong("id"),
                                    rs.getString("status"),
                                    TimeConfig.offset(start),
                                    (int) Duration.between(start, end).toMinutes(),
                                    new Ref(rs.getLong("staff_id"), rs.getString("staff_name")),
                                    rs.getBoolean("consult_first"),
                                    rs.getString("channel"),
                                    customerId == null
                                            ? null
                                            : new Ref(customerId, rs.getString("customer_name")),
                                    services));
                });
        if (!servicesById.isEmpty()) {
            // 예정 시술은 한 번에 읽는다 (반복문 안 조회 없음, DEVELOPMENT 4장)
            jdbc.query(
                    "SELECT rs.reservation_id, m.id, m.name FROM reservation_service rs"
                            + " JOIN service_menu m ON m.id = rs.service_menu_id"
                            + " WHERE rs.reservation_id IN (:ids) ORDER BY rs.id",
                    Map.of("ids", servicesById.keySet()),
                    rs -> {
                        servicesById
                                .get(rs.getLong("reservation_id"))
                                .add(new Service(rs.getLong("id"), rs.getString("name")));
                    });
        }
        return items;
    }
}
