package com.tuktaklab.keepsy.analytics.query;

import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 전환 퍼널 · 연결 매출 (FR-ANL-01 · 02 · 03, CONTRACTS 6장 「성과 · 대시보드 · 검색」). 캠페인 · 예약 · 방문 · 결제를 함께 세는
 * 화면용 횡단 조회라 조회 전용 SQL로 DB에서 집계한다 (DEVELOPMENT 4장 「도메인 연동」, NFR-01).
 *
 * <p>귀속은 저장할 때 이미 판정됐다 (#101, 결정 F33). 여기서는 예약 시술 · 방문 시술의 귀속 발송 ID(F15)가 이 캠페인의 발송 성공 광고 메시지(실패 ·
 * 대조군 제외, E2)이고 그 시술이 캠페인과 같은 카테고리(E7)인 것만 다시 거른다. 기간으로 거르지 않는다 (F33). 삭제된 고객도 그대로 센다 (C12).
 */
@Repository
@RequiredArgsConstructor
public class CampaignResultQuery {

    /** 모두 고객 수 */
    public record Funnel(
            long sentCount, long reachedCount, long rebookedCount, long revisitedCount) {}

    /** 이 캠페인의 발송 성공 광고 메시지 (A8, E2). {@code c} · {@code cm} 별칭을 쓴다 */
    private static final String ATTRIBUTABLE =
            " FROM campaign c"
                    + " JOIN campaign_message cm ON cm.campaign_id = c.id AND cm.shop_id = c.shop_id"
                    + " AND cm.kind = 'AD' AND cm.send_status = 'SENT' AND cm.control_group = FALSE";

    /** 이 캠페인 발송이 귀속된 방문 시술. 가져온 방문은 귀속 · 매출 제외 (FR-CUS-13) */
    private static final String ATTRIBUTED_VISIT_SERVICE =
            ATTRIBUTABLE
                    + " JOIN visit_service vs ON vs.attributed_message_id = cm.id"
                    + " AND vs.category = c.category"
                    + " JOIN visit v ON v.id = vs.visit_id AND v.source = 'NORMAL'"
                    + " WHERE c.id = :campaignId AND c.shop_id = :shopId";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @return 세션 매장에 그 캠페인이 없으면 비어 있다 (NFR-03)
     */
    @Transactional(readOnly = true)
    public Optional<Funnel> funnel(long shopId, long campaignId) {
        Map<String, Object> p = Map.of("shopId", shopId, "campaignId", campaignId);
        if (!exists(p)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                jdbc.queryForObject(
                        "SELECT"
                                // 발송: 대조군 아닌 발송 대상 (대기 · 실패 포함). 도달: 발송 성공 (FR-ANL-01, A6)
                                + " (SELECT COUNT(DISTINCT customer_id) FROM campaign_message"
                                + "   WHERE shop_id = :shopId AND campaign_id = :campaignId"
                                + "   AND control_group = FALSE) AS sent,"
                                + " (SELECT COUNT(DISTINCT customer_id) FROM campaign_message"
                                + "   WHERE shop_id = :shopId AND campaign_id = :campaignId"
                                + "   AND control_group = FALSE AND send_status = 'SENT') AS reached,"
                                // 재예약: 예약 상태와 관계없이(취소 · 노쇼 포함), 승계로 같은 발송을 가진 예약이 여럿이어도
                                // 고객당 1 (FR-ANL-01 · 03)
                                + " (SELECT COUNT(DISTINCT cm.customer_id)"
                                + ATTRIBUTABLE
                                + "   JOIN reservation_service rs ON rs.attributed_message_id = cm.id"
                                + "   JOIN service_menu sm ON sm.id = rs.service_menu_id"
                                + "   AND sm.category = c.category"
                                + "   WHERE c.id = :campaignId AND c.shop_id = :shopId) AS rebooked,"
                                // 재방문: 귀속된 예약에서 생긴 방문은 방문일이 기간 밖이어도 든다 (F33)
                                + " (SELECT COUNT(DISTINCT cm.customer_id)"
                                + ATTRIBUTED_VISIT_SERVICE
                                + ") AS revisited",
                        p,
                        (rs, i) ->
                                new Funnel(
                                        rs.getLong("sent"),
                                        rs.getLong("reached"),
                                        rs.getLong("rebooked"),
                                        rs.getLong("revisited"))));
    }

    /**
     * 귀속된 방문의 취소 안 된 결제 전액 (FR-ANL-02, A13, FR-PAY-02). 같은 방문은 한 번만 센다 ({@code IN}).
     *
     * @return 세션 매장에 그 캠페인이 없으면 비어 있다 (NFR-03)
     */
    @Transactional(readOnly = true)
    public Optional<Long> revenue(long shopId, long campaignId) {
        Map<String, Object> p = Map.of("shopId", shopId, "campaignId", campaignId);
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

    private boolean exists(Map<String, Object> p) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM campaign WHERE id = :campaignId AND shop_id = :shopId",
                        p,
                        Integer.class);
        return n != null && n > 0;
    }
}
