package com.tuktaklab.keepsy.analytics.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 수신 이력 · 캠페인 연결 기록 (FR-CUS-03, FR-ANL-04, CONTRACTS 6장 「고객 (CUS)」). 발송 기록 · 캠페인 · 예약 · 방문을 함께
 * 읽는 화면용 횡단 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」).
 *
 * <p>그 고객이 받은({@code SENT}) 메시지만, 종류와 상관없이 보인다. 대기 · 실패 · 대조군은 넣지 않는다 (결정 F37). 연결된 예약 · 방문의 귀속 판정은
 * 퍼널({@link CampaignResultQuery})과 같다: 저장된 귀속 발송 ID(F15)가 이 광고 메시지이고 시술이 같은 카테고리(E7), 가져온 방문
 * 제외(FR-CUS-13). 기간 · 재예약 여부로 거르지 않는다 (F33, F35는 퍼널에만).
 */
@Repository
@RequiredArgsConstructor
public class CustomerMessageQuery {

    /** CONTRACTS 6장 「고객 (CUS)」 수신 이력 항목 */
    public record Item(
            long messageId,
            String kind,
            OffsetDateTime receivedAt,
            CampaignRef campaign,
            String category,
            List<LinkedReservation> reservations,
            List<LinkedVisit> visits) {}

    /** 수신 거부 처리 안내면 항목의 {@code campaign}이 null (FR-CMP-11) */
    public record CampaignRef(long id, String name) {}

    public record LinkedReservation(long id, OffsetDateTime startAt, String status) {}

    public record LinkedVisit(long id, LocalDate visitDate) {}

    private record Link<T>(long messageId, T value) {}

    /** 받은 메시지 (F37) */
    private static final String RECEIVED =
            " WHERE cm.shop_id = :shopId AND cm.customer_id = :customerId"
                    + " AND cm.send_status = 'SENT' AND cm.control_group = FALSE";

    /** 이 메시지들 중 귀속에 쓰는 광고 (퍼널의 ATTRIBUTABLE과 같은 조건, 발송 성공은 RECEIVED가 이미 걸렀다) */
    private static final String ATTRIBUTABLE_IN_PAGE =
            " WHERE cm.id IN (:ids) AND cm.shop_id = :shopId AND cm.kind = 'AD'";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 받은 시각 최신순, 같으면 ID 역순 (idx_campaign_message_customer_result).
     *
     * @return 세션 매장의 삭제되지 않은 고객이 아니면 비어 있다 (NFR-03, FR-CUS-10)
     */
    @Transactional(readOnly = true)
    public Optional<PageResponse<Item>> messages(long shopId, long customerId, int page, int size) {
        Map<String, Object> p =
                Map.of(
                        "shopId",
                        shopId,
                        "customerId",
                        customerId,
                        "size",
                        size,
                        "offset",
                        (long) page * size);
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM customer WHERE id = :customerId"
                                + " AND shop_id = :shopId AND status = 'ACTIVE')",
                        p,
                        Boolean.class))) {
            return Optional.empty();
        }
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign_message cm" + RECEIVED, p, Long.class);
        List<Item> rows =
                jdbc.query(
                        "SELECT cm.id, cm.kind, cm.result_at, cm.category, c.id AS campaign_id,"
                                + " c.name AS campaign_name"
                                + " FROM campaign_message cm"
                                + " LEFT JOIN campaign c ON c.id = cm.campaign_id"
                                + RECEIVED
                                + " ORDER BY cm.result_at DESC, cm.id DESC"
                                + " LIMIT :size OFFSET :offset",
                        p,
                        (rs, i) -> {
                            Long campaignId = rs.getObject("campaign_id", Long.class);
                            return new Item(
                                    rs.getLong("id"),
                                    rs.getString("kind"),
                                    TimeConfig.offset(
                                            rs.getObject("result_at", LocalDateTime.class)),
                                    campaignId == null
                                            ? null
                                            : new CampaignRef(
                                                    campaignId, rs.getString("campaign_name")),
                                    rs.getString("category"),
                                    List.of(),
                                    List.of());
                        });
        if (rows.isEmpty()) {
            return Optional.of(new PageResponse<>(rows, page, size, total == null ? 0 : total));
        }

        Map<String, Object> ids =
                Map.of("shopId", shopId, "ids", rows.stream().map(Item::messageId).toList());
        // 예약 상태와 관계없이(취소 · 노쇼 포함), 승계한 다음 예약도 함께 (FR-ANL-01, FR-VIS-04)
        Map<Long, List<LinkedReservation>> reservations =
                group(
                        jdbc.query(
                                "SELECT DISTINCT cm.id AS message_id, r.id, r.start_at, r.status"
                                        + " FROM campaign_message cm"
                                        + " JOIN reservation_service rs"
                                        + " ON rs.attributed_message_id = cm.id"
                                        + " JOIN service_menu sm ON sm.id = rs.service_menu_id"
                                        + " AND sm.category = cm.category"
                                        + " JOIN reservation r ON r.id = rs.reservation_id"
                                        + ATTRIBUTABLE_IN_PAGE
                                        + " ORDER BY r.start_at, r.id",
                                ids,
                                (rs, i) ->
                                        new Link<>(
                                                rs.getLong("message_id"),
                                                new LinkedReservation(
                                                        rs.getLong("id"),
                                                        TimeConfig.offset(
                                                                rs.getObject(
                                                                        "start_at",
                                                                        LocalDateTime.class)),
                                                        rs.getString("status")))));
        // 귀속된 예약에서 생긴 방문은 방문일이 귀속 기간 밖이어도 든다 (F33)
        Map<Long, List<LinkedVisit>> visits =
                group(
                        jdbc.query(
                                "SELECT DISTINCT cm.id AS message_id, v.id, v.visit_date"
                                        + " FROM campaign_message cm"
                                        + " JOIN visit_service vs"
                                        + " ON vs.attributed_message_id = cm.id"
                                        + " AND vs.category = cm.category"
                                        + " JOIN visit v ON v.id = vs.visit_id"
                                        + " AND v.source = 'NORMAL'"
                                        + ATTRIBUTABLE_IN_PAGE
                                        + " ORDER BY v.visit_date, v.id",
                                ids,
                                (rs, i) ->
                                        new Link<>(
                                                rs.getLong("message_id"),
                                                new LinkedVisit(
                                                        rs.getLong("id"),
                                                        rs.getObject(
                                                                "visit_date", LocalDate.class)))));
        return Optional.of(
                new PageResponse<>(
                        rows.stream()
                                .map(m ->
                                        new Item(
                                                m.messageId(),
                                                m.kind(),
                                                m.receivedAt(),
                                                m.campaign(),
                                                m.category(),
                                                reservations.getOrDefault(m.messageId(), List.of()),
                                                visits.getOrDefault(m.messageId(), List.of())))
                                .toList(),
                        page,
                        size,
                        total == null ? 0 : total));
    }

    /** 메시지 ID별로 묶는다. 묶음 안의 순서는 SQL 정렬 그대로 */
    private static <T> Map<Long, List<T>> group(List<Link<T>> links) {
        return links.stream()
                .collect(
                        Collectors.groupingBy(
                                Link::messageId,
                                Collectors.mapping(Link::value, Collectors.toList())));
    }
}
