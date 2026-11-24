package com.tuktaklab.keepsy.campaign.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** CONTRACTS 6장 「`messages` 항목 필드」. 번호는 응답 단계에서 마스킹한다 (NFR-04) */
    public record Message(
            long customerId,
            String customerName,
            boolean customerDeleted,
            String phone,
            boolean controlGroup,
            String sendStatus,
            String failureReason,
            OffsetDateTime resultAt) {}

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
        return jdbc.query(
                "SELECT m.customer_id, cu.name, cu.status = 'DELETED' AS deleted,"
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
                                time(rs, "result_at")));
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
