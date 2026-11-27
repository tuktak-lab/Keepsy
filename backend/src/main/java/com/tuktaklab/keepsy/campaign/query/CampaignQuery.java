package com.tuktaklab.keepsy.campaign.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 캠페인 목록 · 상세 · 고객별 발송 결과 (FR-CMP-10 · 18, CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」). 대상 · 고객 · 스탭을
 * 함께 읽는 화면용 횡단 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 목록의 대상 · 대조군 수는 행마다 서브쿼리라
 * N+1이 없다 (NFR-01). 세션 매장 조건 (NFR-03).
 */
@Repository
@RequiredArgsConstructor
public class CampaignQuery {

    public record StaffRef(long id, String name) {}

    /** CONTRACTS 6장 「`GET /api/campaigns` 항목 필드」 */
    public record Item(
            long id,
            String name,
            String category,
            String messageKind,
            String status,
            int targetCount,
            int controlCount,
            OffsetDateTime scheduledAt,
            OffsetDateTime sentAt,
            String cancelReason) {}

    /** 상세 머리: 목록 항목 + 목록 밖 칸 */
    public record Header(Item item, Extra extra) {}

    /** 상세의 목록 밖 칸 */
    public record Extra(
            long templateId,
            int controlPercent,
            StaffRef reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime canceledAt) {}

    /**
     * CONTRACTS 6장 「`messages` 항목 필드」. 번호는 응답 단계에서 마스킹한다 (NFR-04). {@code reservations} · {@code
     * visits}는 이 발송이 귀속된 예약 · 방문이고(FR-ANL-04), 결제 금액은 넣지 않는다 (SALES_ANALYTICS는 /revenue)
     */
    public record Message(
            long customerId,
            String customerName,
            boolean customerDeleted,
            String phone,
            boolean controlGroup,
            String sendStatus,
            String failureReason,
            OffsetDateTime resultAt,
            Long messageId,
            List<LinkedReservation> reservations,
            List<LinkedVisit> visits) {}

    public record LinkedReservation(long id, OffsetDateTime startAt, String status) {}

    public record LinkedVisit(long id, LocalDate visitDate) {}

    private record Link<T>(long messageId, T value) {}

    /**
     * 귀속에 쓰는 광고 발송만 (퍼널 · 고객 상세 수신 이력과 같은 조건: 광고 · 발송 성공 · 대조군 아님). 귀속 판정 SQL은 고객 상세 수신
     * 이력(analytics CustomerMessageQuery)과 같다 — 다른 도메인의 조회 클래스를 import하지 않으려고 여기 둔다 (DEVELOPMENT
     * 4장). 두 결과가 같은지는 CampaignResultTest가 본다
     */
    private static final String ATTRIBUTABLE_IN_PAGE =
            " WHERE cm.id IN (:ids) AND cm.shop_id = :shopId AND cm.kind = 'AD'"
                    + " AND cm.send_status = 'SENT' AND cm.control_group = FALSE";

    private static final String ITEM_COLUMNS =
            "c.id, c.name, c.category, c.message_kind, c.status, c.scheduled_at, c.sent_at,"
                    + " c.cancel_reason,"
                    + " (SELECT COUNT(*) FROM campaign_target t WHERE t.campaign_id = c.id) AS target_count,"
                    + " (SELECT COUNT(*) FROM campaign_target t WHERE t.campaign_id = c.id"
                    + "   AND t.control_group) AS control_count";

    private final NamedParameterJdbcTemplate jdbc;

    /** 최근 만든 순 (ID 내림차순) */
    public List<Item> find(long shopId, int page, int size) {
        return jdbc.query(
                "SELECT "
                        + ITEM_COLUMNS
                        + " FROM campaign c WHERE c.shop_id = :shopId"
                        + " ORDER BY c.id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("shopId", shopId)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                (rs, i) -> item(rs));
    }

    public long count(long shopId) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign WHERE shop_id = :shopId",
                        Map.of("shopId", shopId),
                        Long.class);
        return n == null ? 0 : n;
    }

    /** 다른 매장 · 없는 캠페인이면 비어 있다 */
    public Optional<Header> findOne(long shopId, long campaignId) {
        return jdbc
                .query(
                        "SELECT "
                                + ITEM_COLUMNS
                                + ", c.template_id, c.control_percent, c.reviewed_at, c.canceled_at,"
                                + " st.id AS reviewer_id, st.name AS reviewer_name"
                                + " FROM campaign c LEFT JOIN staff st ON st.id = c.reviewed_by_staff_id"
                                + " WHERE c.id = :campaignId AND c.shop_id = :shopId",
                        Map.of("shopId", shopId, "campaignId", campaignId),
                        (rs, i) -> {
                            Long reviewer = rs.getObject("reviewer_id", Long.class);
                            return new Header(
                                    item(rs),
                                    new Extra(
                                            rs.getLong("template_id"),
                                            rs.getInt("control_percent"),
                                            reviewer == null
                                                    ? null
                                                    : new StaffRef(
                                                            reviewer,
                                                            rs.getString("reviewer_name")),
                                            time(rs, "reviewed_at"),
                                            time(rs, "canceled_at")));
                        })
                .stream()
                .findFirst();
    }

    /** 고객별 발송 결과 한 페이지, 고객 ID 순. 발송을 시작하기 전에는 비어 있다 (F7) */
    public List<Message> messages(long shopId, long campaignId, int page, int size) {
        List<Message> rows =
                jdbc.query(
                        "SELECT m.id, m.customer_id, cu.name, cu.status = 'DELETED' AS deleted,"
                                + " CONCAT(LEFT(m.recipient_phone, 4), '****', SUBSTRING(m.recipient_phone, 9))"
                                + "   AS masked_phone,"
                                + " m.control_group, m.send_status, m.failure_reason, m.result_at"
                                + " FROM campaign_message m JOIN customer cu ON cu.id = m.customer_id"
                                + " WHERE m.shop_id = :shopId AND m.campaign_id = :campaignId"
                                + " ORDER BY m.customer_id LIMIT :limit OFFSET :offset",
                        new MapSqlParameterSource("shopId", shopId)
                                .addValue("campaignId", campaignId)
                                .addValue("limit", size)
                                .addValue("offset", (long) page * size),
                        (rs, i) ->
                                new Message(
                                        rs.getLong("customer_id"),
                                        rs.getString("name"),
                                        rs.getBoolean("deleted"),
                                        rs.getString("masked_phone"),
                                        rs.getBoolean("control_group"),
                                        rs.getString("send_status"),
                                        rs.getString("failure_reason"),
                                        time(rs, "result_at"),
                                        rs.getBoolean("control_group") ? null : rs.getLong("id"),
                                        List.of(),
                                        List.of()));
        List<Long> ids = rows.stream().map(Message::messageId).filter(id -> id != null).toList();
        if (ids.isEmpty()) {
            return rows;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("shopId", shopId).addValue("ids", ids);
        // 예약 상태와 관계없이(취소 · 노쇼 포함), 승계한 다음 예약도 함께 (FR-ANL-01, FR-VIS-04)
        Map<Long, List<LinkedReservation>> reservations =
                group(
                        jdbc.query(
                                "SELECT DISTINCT cm.id AS message_id, r.id, r.start_at, r.status"
                                        + " FROM campaign_message cm"
                                        + " JOIN reservation_service rs ON rs.attributed_message_id = cm.id"
                                        + " JOIN service_menu sm ON sm.id = rs.service_menu_id"
                                        + " AND sm.category = cm.category"
                                        + " JOIN reservation r ON r.id = rs.reservation_id"
                                        + ATTRIBUTABLE_IN_PAGE
                                        + " ORDER BY r.start_at, r.id",
                                p,
                                (rs, i) ->
                                        new Link<>(
                                                rs.getLong("message_id"),
                                                new LinkedReservation(
                                                        rs.getLong("id"),
                                                        time(rs, "start_at"),
                                                        rs.getString("status")))));
        // 귀속된 예약에서 생긴 방문은 방문일이 귀속 기간 밖이어도 든다 (F33). CSV로 가져온 방문은 뺀다
        Map<Long, List<LinkedVisit>> visits =
                group(
                        jdbc.query(
                                "SELECT DISTINCT cm.id AS message_id, v.id, v.visit_date"
                                        + " FROM campaign_message cm"
                                        + " JOIN visit_service vs ON vs.attributed_message_id = cm.id"
                                        + " AND vs.category = cm.category"
                                        + " JOIN visit v ON v.id = vs.visit_id AND v.source = 'NORMAL'"
                                        + ATTRIBUTABLE_IN_PAGE
                                        + " ORDER BY v.visit_date, v.id",
                                p,
                                (rs, i) ->
                                        new Link<>(
                                                rs.getLong("message_id"),
                                                new LinkedVisit(
                                                        rs.getLong("id"),
                                                        rs.getObject(
                                                                "visit_date", LocalDate.class)))));
        return rows.stream()
                .map(m ->
                        m.messageId() == null
                                ? m
                                : new Message(
                                        m.customerId(),
                                        m.customerName(),
                                        m.customerDeleted(),
                                        m.phone(),
                                        m.controlGroup(),
                                        m.sendStatus(),
                                        m.failureReason(),
                                        m.resultAt(),
                                        m.messageId(),
                                        reservations.getOrDefault(m.messageId(), List.of()),
                                        visits.getOrDefault(m.messageId(), List.of())))
                .toList();
    }

    /** 메시지 ID별로 묶는다. 묶음 안의 순서는 SQL 정렬 그대로 */
    private static <T> Map<Long, List<T>> group(List<Link<T>> links) {
        return links.stream()
                .collect(
                        Collectors.groupingBy(
                                Link::messageId,
                                Collectors.mapping(Link::value, Collectors.toList())));
    }

    public long countMessages(long shopId, long campaignId) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign_message"
                                + " WHERE shop_id = :shopId AND campaign_id = :campaignId",
                        Map.of("shopId", shopId, "campaignId", campaignId),
                        Long.class);
        return n == null ? 0 : n;
    }

    private static Item item(ResultSet rs) throws SQLException {
        return new Item(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("message_kind"),
                rs.getString("status"),
                rs.getInt("target_count"),
                rs.getInt("control_count"),
                time(rs, "scheduled_at"),
                time(rs, "sent_at"),
                rs.getString("cancel_reason"));
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return TimeConfig.offset(rs.getObject(column, LocalDateTime.class));
    }
}
