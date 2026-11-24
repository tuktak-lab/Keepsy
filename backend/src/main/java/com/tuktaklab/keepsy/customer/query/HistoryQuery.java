package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.query.ConsentHistoryQuery.StaffRef;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시술 히스토리북 (FR-CUS-07, CONTRACTS 6장 「고객 (CUS)」). 방문 · 시술 · 메뉴 · 스탭 · 방문 메모 · 사진을 함께 읽는 화면용 횡단 조회라
 * 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 방문 한 페이지를 읽은 뒤 그 방문들의 시술 · 사진 · 메모를 IN 쿼리
 * 하나씩으로 읽어 N+1이 없다 (NFR-01). 세션 매장 조건 (NFR-03).
 */
@Repository
@RequiredArgsConstructor
public class HistoryQuery {

    /** 가져온 방문이면 메뉴 · 스탭이 null (A13) */
    public record Service(String category, String menuName, StaffRef staff) {}

    public record Photo(long photoId, OffsetDateTime uploadedAt) {}

    public record Note(long noteId, String content, StaffRef staff, OffsetDateTime createdAt) {}

    public record Visit(
            long visitId,
            LocalDate visitDate,
            List<Service> services,
            List<Photo> photos,
            List<Note> notes) {}

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 방문 최신순 한 페이지.
     *
     * @param withPhotos false면 사진을 읽지 않는다 (사진 동의가 없을 때)
     */
    public List<Visit> find(long shopId, long customerId, boolean withPhotos, int page, int size) {
        MapSqlParameterSource p =
                new MapSqlParameterSource("shopId", shopId)
                        .addValue("customerId", customerId)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size);
        List<Map<String, Object>> visits =
                jdbc.queryForList(
                        "SELECT id, visit_date FROM visit"
                                + " WHERE shop_id = :shopId AND customer_id = :customerId"
                                + " ORDER BY visit_date DESC, id DESC LIMIT :limit OFFSET :offset",
                        p);
        if (visits.isEmpty()) {
            return List.of();
        }
        p.addValue("ids", visits.stream().map(v -> ((Number) v.get("id")).longValue()).toList());

        Map<Long, List<Service>> services =
                group(
                        "SELECT vs.visit_id, vs.category, m.name AS menu_name,"
                                + " st.id AS staff_id, st.name AS staff_name"
                                + " FROM visit_service vs"
                                + " LEFT JOIN service_menu m ON m.id = vs.service_menu_id"
                                + " LEFT JOIN staff st ON st.id = vs.staff_id"
                                + " WHERE vs.shop_id = :shopId AND vs.visit_id IN (:ids) ORDER BY vs.id",
                        p,
                        r ->
                                new Service(
                                        (String) r.get("category"),
                                        (String) r.get("menu_name"),
                                        staff(r)));
        Map<Long, List<Photo>> photos =
                withPhotos
                        ? group(
                                "SELECT visit_id, id, uploaded_at FROM photo"
                                        + " WHERE shop_id = :shopId AND visit_id IN (:ids) ORDER BY id",
                                p,
                                r ->
                                        new Photo(
                                                ((Number) r.get("id")).longValue(),
                                                TimeConfig.offset(
                                                        (LocalDateTime) r.get("uploaded_at"))))
                        : Map.of();
        Map<Long, List<Note>> notes =
                group(
                        "SELECT n.visit_id, n.id, n.content, n.created_at,"
                                + " st.id AS staff_id, st.name AS staff_name"
                                + " FROM visit_note n JOIN staff st ON st.id = n.staff_id"
                                + " WHERE n.shop_id = :shopId AND n.visit_id IN (:ids) ORDER BY n.id",
                        p,
                        r ->
                                new Note(
                                        ((Number) r.get("id")).longValue(),
                                        (String) r.get("content"),
                                        staff(r),
                                        TimeConfig.offset((LocalDateTime) r.get("created_at"))));

        return visits.stream()
                .map(v -> {
                    long id = ((Number) v.get("id")).longValue();
                    return new Visit(
                            id,
                            ((Date) v.get("visit_date")).toLocalDate(),
                            services.getOrDefault(id, List.of()),
                            photos.getOrDefault(id, List.of()),
                            notes.getOrDefault(id, List.of()));
                })
                .toList();
    }

    public long count(long shopId, long customerId) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM visit WHERE shop_id = :shopId AND customer_id = :customerId",
                        Map.of("shopId", shopId, "customerId", customerId),
                        Long.class);
        return n == null ? 0 : n;
    }

    /** 방문 ID로 묶는다. 각 묶음 안의 순서는 SQL의 ORDER BY를 따른다 */
    private <T> Map<Long, List<T>> group(
            String sql, MapSqlParameterSource p, Function<Map<String, Object>, T> row) {
        return jdbc.queryForList(sql, p).stream()
                .collect(
                        Collectors.groupingBy(
                                r -> ((Number) r.get("visit_id")).longValue(),
                                Collectors.mapping(row, Collectors.toList())));
    }

    private static StaffRef staff(Map<String, Object> r) {
        Object id = r.get("staff_id");
        return id == null
                ? null
                : new StaffRef(((Number) id).longValue(), (String) r.get("staff_name"));
    }
}
