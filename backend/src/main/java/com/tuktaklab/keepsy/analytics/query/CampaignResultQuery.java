package com.tuktaklab.keepsy.analytics.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 전환 퍼널 · 연결 매출 · 대조군 비교 · 최근 캠페인 성과 (FR-ANL-01 · 02 · 03 · 09, FR-DSH-04, CONTRACTS 6장 「성과 ·
 * 대시보드 · 검색」). 캠페인 · 예약 · 방문 · 결제를 함께 세는 화면용 횡단 조회라 조회 전용 SQL로 DB에서 집계한다 (DEVELOPMENT 4장 「도메인 연동」,
 * NFR-01).
 *
 * <p>귀속은 저장할 때 이미 판정됐다 (#101, 결정 F33). 여기서는 예약 시술 · 방문 시술의 귀속 발송 ID(F15)가 이 캠페인의 발송 성공 광고 메시지(실패 ·
 * 대조군 제외, E2)이고 그 시술이 캠페인과 같은 카테고리(E7)인 것만 다시 거른다. 기간으로 거르지 않는다 (F33). 삭제된 고객도 그대로 센다 (C12). 대조군은
 * 발송하지 않아 귀속이 없으므로 발송 시각부터 귀속 기간 안에 생성된 예약으로 센다 (FR-ANL-09, 결정 F36).
 */
@Repository
@RequiredArgsConstructor
public class CampaignResultQuery {

    /** 모두 고객 수 */
    public record Funnel(
            long sentCount, long reachedCount, long rebookedCount, long revisitedCount) {}

    /** 모두 고객 수. 발송군은 도달 고객, {@code controlCount}가 0이면 비교 대상이 아니다 (F36) */
    public record ControlComparison(
            long reachedCount, long rebookedCount, long controlCount, long controlRebookedCount) {}

    /** 도달 · 재예약은 퍼널과 같은 값 (F35, F36) */
    public record RecentCampaign(
            long id,
            String name,
            String category,
            OffsetDateTime sentAt,
            long reachedCount,
            long rebookedCount) {}

    /** 최근 캠페인 개수 (FR-DSH-04) */
    private static final int RECENT_LIMIT = 3;

    /** 고른 캠페인 {@code c}. 퍼널 · 매출 · 대조군 비교는 하나, 최근 캠페인은 여럿 */
    private static final String OF_CAMPAIGNS =
            " WHERE c.id IN (:campaignIds) AND c.shop_id = :shopId";

    /** 이 캠페인의 발송 성공 광고 메시지 (A8, E2). {@code c} · {@code cm} 별칭을 쓴다 */
    private static final String ATTRIBUTABLE =
            " FROM campaign c"
                    + " JOIN campaign_message cm ON cm.campaign_id = c.id AND cm.shop_id = c.shop_id"
                    + " AND cm.kind = 'AD' AND cm.send_status = 'SENT' AND cm.control_group = FALSE";

    /** 도달: 대조군 아닌 발송 대상 중 발송 성공 (FR-ANL-01, A6) */
    private static final String REACHED_MESSAGES =
            " FROM campaign_message WHERE shop_id = :shopId AND campaign_id IN (:campaignIds)"
                    + " AND control_group = FALSE AND send_status = 'SENT'";

    /** 이 캠페인 발송이 귀속된 방문 시술. 가져온 방문은 귀속 · 매출 제외 (FR-CUS-13) */
    private static final String ATTRIBUTED_VISIT_SERVICE =
            ATTRIBUTABLE
                    + " JOIN visit_service vs ON vs.attributed_message_id = cm.id"
                    + " AND vs.category = c.category"
                    + " JOIN visit v ON v.id = vs.visit_id AND v.source = 'NORMAL'"
                    + OF_CAMPAIGNS;

    /** 재예약 단계: 예약 상태와 관계없이(취소 · 노쇼 포함) 이 캠페인 발송이 귀속된 예약 시술 (FR-ANL-01 · 03) */
    private static final String REBOOKED =
            ATTRIBUTABLE
                    + " JOIN reservation_service rs ON rs.attributed_message_id = cm.id"
                    + " JOIN service_menu sm ON sm.id = rs.service_menu_id"
                    + " AND sm.category = c.category"
                    + OF_CAMPAIGNS;

    private static final String REBOOKED_CUSTOMERS = "SELECT cm.customer_id" + REBOOKED;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @return 세션 매장에 그 캠페인이 없으면 비어 있다 (NFR-03)
     */
    @Transactional(readOnly = true)
    public Optional<Funnel> funnel(long shopId, long campaignId) {
        Map<String, Object> p = params(shopId, campaignId);
        if (!exists(p)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                jdbc.queryForObject(
                        "SELECT"
                                // 발송: 대조군 아닌 발송 대상 (대기 · 실패 포함) (FR-ANL-01)
                                + " (SELECT COUNT(DISTINCT customer_id) FROM campaign_message"
                                + "   WHERE shop_id = :shopId AND campaign_id = :campaignId"
                                + "   AND control_group = FALSE) AS sent,"
                                + " (SELECT COUNT(DISTINCT customer_id)"
                                + REACHED_MESSAGES
                                + ") AS reached,"
                                // 재예약: 승계로 같은 발송을 가진 예약이 여럿이어도 고객당 1 (FR-ANL-03)
                                + " (SELECT COUNT(DISTINCT r.customer_id) FROM ("
                                + REBOOKED_CUSTOMERS
                                + ") r) AS rebooked,"
                                // 재방문: 재예약 고객 중 귀속된 방문이 있는 고객. 방문만 귀속된 고객은 뺀다 (F35).
                                // 귀속된 예약에서 생긴 방문은 방문일이 기간 밖이어도 든다 (F33)
                                + " (SELECT COUNT(DISTINCT cm.customer_id)"
                                + ATTRIBUTED_VISIT_SERVICE
                                + " AND cm.customer_id IN ("
                                + REBOOKED_CUSTOMERS
                                + ")) AS revisited",
                        p,
                        (rs, i) ->
                                new Funnel(
                                        rs.getLong("sent"),
                                        rs.getLong("reached"),
                                        rs.getLong("rebooked"),
                                        rs.getLong("revisited"))));
    }

    /**
     * 귀속된 방문의 취소 안 된 결제 전액 (FR-ANL-02, A13, FR-PAY-02). 같은 방문은 한 번만 센다 ({@code IN}). 퍼널 재방문과 달리 재예약
     * 고객으로 좁히지 않는다 (F35는 퍼널에만 적용).
     *
     * @return 세션 매장에 그 캠페인이 없으면 비어 있다 (NFR-03)
     */
    @Transactional(readOnly = true)
    public Optional<Long> revenue(long shopId, long campaignId) {
        Map<String, Object> p = params(shopId, campaignId);
        if (!exists(p)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount), 0) FROM payment"
                                + " WHERE shop_id = :shopId AND canceled = FALSE"
                                + " AND visit_id IN (SELECT vs.visit_id"
                                + ATTRIBUTED_VISIT_SERVICE
                                + ")",
                        p,
                        Long.class));
    }

    /**
     * 발송군(도달 고객, 퍼널과 같은 재예약)과 대조군의 재예약 (FR-ANL-09, 결정 F36). 대조군 재예약은 캠페인 발송 시각 이후 생성됐고 생성일이 발송일 +
     * 귀속 기간(지금 매장 설정) 이내인 같은 카테고리 예약이다. 날짜로 세는 방식은 귀속 판정과 같고(MySQL {@code DATEDIFF}는 날짜만 본다), 취소 ·
     * 노쇼 예약도 센다. 아직 발송하지 않은 캠페인은 {@code sent_at}이 없어 0이다. 주기 내 재방문율은 F24 · F36으로 미뤘다.
     *
     * @return 세션 매장에 그 캠페인이 없으면 비어 있다 (NFR-03)
     */
    @Transactional(readOnly = true)
    public Optional<ControlComparison> controlComparison(long shopId, long campaignId) {
        Map<String, Object> p = params(shopId, campaignId);
        if (!exists(p)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                jdbc.queryForObject(
                        "SELECT"
                                + " (SELECT COUNT(DISTINCT customer_id)"
                                + REACHED_MESSAGES
                                + ") AS reached,"
                                + " (SELECT COUNT(DISTINCT r.customer_id) FROM ("
                                + REBOOKED_CUSTOMERS
                                + ") r) AS rebooked,"
                                + " (SELECT COUNT(DISTINCT customer_id) FROM campaign_message"
                                + "   WHERE shop_id = :shopId AND campaign_id = :campaignId"
                                + "   AND control_group = TRUE) AS control,"
                                + " (SELECT COUNT(DISTINCT cm.customer_id) FROM campaign c"
                                + "   JOIN shop s ON s.id = c.shop_id"
                                + "   JOIN campaign_message cm ON cm.campaign_id = c.id"
                                + "   AND cm.shop_id = c.shop_id AND cm.control_group = TRUE"
                                + "   JOIN reservation r ON r.customer_id = cm.customer_id"
                                + "   AND r.shop_id = c.shop_id AND r.created_at >= c.sent_at"
                                + "   AND DATEDIFF(r.created_at, c.sent_at) <= s.attribution_days"
                                + "   JOIN reservation_service rs ON rs.reservation_id = r.id"
                                + "   JOIN service_menu sm ON sm.id = rs.service_menu_id"
                                + "   AND sm.category = c.category"
                                + OF_CAMPAIGNS
                                + ") AS control_rebooked",
                        p,
                        (rs, i) ->
                                new ControlComparison(
                                        rs.getLong("reached"),
                                        rs.getLong("rebooked"),
                                        rs.getLong("control"),
                                        rs.getLong("control_rebooked"))));
    }

    /**
     * 발송을 시작한 광고 캠페인을 발송 시각 최신순으로 3개, 같으면 ID가 큰 것 먼저 (FR-DSH-04, 결정 F36). 동의 재확인 안내는 카테고리가 없어 뺀다
     * (E7). 도달 · 재예약은 퍼널과 같은 조건으로 캠페인별로 한 번에 센다.
     */
    @Transactional(readOnly = true)
    public List<RecentCampaign> recentCampaigns(long shopId) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM campaign WHERE shop_id = :shopId AND message_kind = 'AD'"
                                + " AND sent_at IS NOT NULL ORDER BY sent_at DESC, id DESC LIMIT "
                                + RECENT_LIMIT,
                        Map.of("shopId", shopId),
                        Long.class);
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT c.id, c.name, c.category, c.sent_at,"
                        + " COALESCE(rc.n, 0) AS reached, COALESCE(rb.n, 0) AS rebooked"
                        + " FROM campaign c"
                        + " LEFT JOIN (SELECT campaign_id, COUNT(DISTINCT customer_id) AS n"
                        + REACHED_MESSAGES
                        + " GROUP BY campaign_id) rc ON rc.campaign_id = c.id"
                        + " LEFT JOIN (SELECT c.id AS campaign_id, COUNT(DISTINCT cm.customer_id) AS n"
                        + REBOOKED
                        + " GROUP BY c.id) rb ON rb.campaign_id = c.id"
                        + OF_CAMPAIGNS
                        + " ORDER BY c.sent_at DESC, c.id DESC",
                Map.of("shopId", shopId, "campaignIds", ids),
                (rs, i) ->
                        new RecentCampaign(
                                rs.getLong("id"),
                                rs.getString("name"),
                                rs.getString("category"),
                                TimeConfig.offset(rs.getObject("sent_at", LocalDateTime.class)),
                                rs.getLong("reached"),
                                rs.getLong("rebooked")));
    }

    private static Map<String, Object> params(long shopId, long campaignId) {
        return Map.of(
                "shopId", shopId, "campaignId", campaignId, "campaignIds", List.of(campaignId));
    }

    private boolean exists(Map<String, Object> p) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign WHERE id = :campaignId AND shop_id = :shopId",
                        p,
                        Integer.class);
        return n != null && n > 0;
    }
}
