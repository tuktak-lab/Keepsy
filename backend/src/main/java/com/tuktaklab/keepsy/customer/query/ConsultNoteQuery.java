package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.query.ConsentHistoryQuery.StaffRef;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 상담 메모 목록 (FR-CUS-06, CONTRACTS 6장 「고객 (CUS)」). 작성 스탭 이름을 함께 읽는 화면용 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다
 * (DEVELOPMENT 4장 「도메인 연동」). 세션 매장 조건 (NFR-03).
 */
@Repository
@RequiredArgsConstructor
public class ConsultNoteQuery {

    /** CONTRACTS 6장 「상담 메모 항목 필드」 */
    public record Row(long noteId, String content, StaffRef staff, OffsetDateTime writtenAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    /** 최신 작성순 (같은 시각이면 ID가 큰 순) */
    public List<Row> find(long shopId, long customerId, int page, int size) {
        return jdbc.query(
                "SELECT n.id, n.content, n.written_at, st.id AS staff_id, st.name AS staff_name"
                        + " FROM consult_note n JOIN staff st ON st.id = n.staff_id"
                        + " WHERE n.shop_id = :shopId AND n.customer_id = :customerId"
                        + " ORDER BY n.written_at DESC, n.id DESC LIMIT :limit OFFSET :offset",
                Map.of(
                        "shopId", shopId,
                        "customerId", customerId,
                        "limit", size,
                        "offset", (long) page * size),
                (rs, i) ->
                        new Row(
                                rs.getLong("id"),
                                rs.getString("content"),
                                new StaffRef(rs.getLong("staff_id"), rs.getString("staff_name")),
                                TimeConfig.offset(
                                        rs.getObject("written_at", LocalDateTime.class))));
    }

    public long count(long shopId, long customerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM consult_note WHERE shop_id = :shopId AND customer_id = :customerId",
                Map.of("shopId", shopId, "customerId", customerId),
                Long.class);
    }
}
