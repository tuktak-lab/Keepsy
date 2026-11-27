package com.tuktaklab.keepsy.analytics.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.LocalDate;
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
    /** 주기 내 재방문 네 값은 결정 F57 */
    public record ControlComparison(
            long reachedCount,
            long rebookedCount,
            long controlCount,
            long controlRebookedCount,
            long reachedCycleCount,
            long reachedCycleRevisitedCount,
            long controlCycleCount,
            long controlCycleRevisitedCount) {}

    /** 도달 · 재예약은 퍼널과 같은 값 (F35, F36) */
    public record RecentCampaign(
            long id,
            String name,
            String category,
            OffsetDateTime sentAt,
            long reachedCount,
            long rebookedCount) {}

    /** 기간 안 광고 캠페인 하나의 퍼널 · 연결 매출. 퍼널 · 연결 매출 API와 같은 값 (F65) */
    public record CampaignResult(
            long id,
            String name,
            String category,
            OffsetDateTime sentAt,
            long sentCount,
            long reachedCount,
            long rebookedCount,
            long revisitedCount,
            long revenue) {}

    /** 기간 안 캠페인 전체 합계. 단계마다 고객 · 방문을 중복 없이 센다 (F65, FR-ANL-02) */
    public record CampaignTotal(
            long campaignCount,
            long sentCount,
            long reachedCount,
            long rebookedCount,
            long revisitedCount,
            long revenue) {}

    /** 캠페인 성과 목록 한 페이지와 기간 전체 합계 */
    public record CampaignResults(
            List<CampaignResult> items, long totalElements, CampaignTotal total) {}

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

    /** 발송: 대조군 아닌 발송 대상 (대기 · 실패 포함) (FR-ANL-01) */
    private static final String SENT_MESSAGES =
            " FROM campaign_message WHERE shop_id = :shopId AND campaign_id IN (:campaignIds)"
                    + " AND control_group = FALSE";

    /** 재방문: 귀속 방문이 있고 같은 캠페인의 재예약 단계에 든 고객 (F35). 캠페인이 여럿이면 캠페인마다 따지므로 (캠페인, 고객) 쌍으로 거른다 (F65) */
    private static final String REVISITED =
            ATTRIBUTED_VISIT_SERVICE
                    + " AND (c.id, cm.customer_id) IN (SELECT c.id, cm.customer_id"
                    + REBOOKED
                    + ")";

    /**
     * 집단 고객 {@code cm}과 그 고객의 발송 시각 예측 {@code ps} (F57): 캠페인 카테고리 스냅샷 중 발송 시각 이전에 만들어졌고 실제 다음 방문일이
     * 없거나 발송일 이후인 것 가운데 가장 먼저 만든 것. 이탈 신호 기간이 없는 스냅샷이면 고객을 뺀다. 앞에 집단 조건을 붙여 쓴다
     */
    private static final String CYCLE_SNAPSHOT =
            " JOIN prediction_snapshot ps ON ps.id = (SELECT MIN(ps2.id) FROM prediction_snapshot ps2"
                    + "   WHERE ps2.shop_id = c.shop_id AND ps2.customer_id = cm.customer_id"
                    + "   AND ps2.category = c.category AND ps2.created_at <= c.sent_at"
                    + "   AND (ps2.actual_visit_date IS NULL"
                    + "   OR ps2.actual_visit_date >= DATE(c.sent_at)))"
                    + " AND ps.churn_signal_days IS NOT NULL"
                    + OF_CAMPAIGNS;

    /** 재방문: 발송 시각 예측의 이탈 신호 기간이 끝나기 전에 다음 방문이 왔다 (FR-ANL-09, F57) */
    private static final String CYCLE_COUNTS =
            "SELECT COUNT(DISTINCT cm.customer_id) AS base,"
                    + " COUNT(DISTINCT CASE WHEN ps.actual_visit_date"
                    + "   <= DATE_ADD(ps.end_date, INTERVAL ps.churn_signal_days DAY)"
                    + "   THEN cm.customer_id END) AS revisited"
                    + " FROM campaign c JOIN campaign_message cm ON cm.campaign_id = c.id"
                    + " AND cm.shop_id = c.shop_id AND ";

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
     * 고객별 연결 매출 (FR-ANL-04 · 02). 규칙은 {@link #revenue}와 같다. (고객, 방문) 쌍을 먼저 중복 없이 고른 뒤 결제를 더해 한 방문을
     * 한 번만 센다. 한 고객에 이 캠페인 메시지는 하나고 방문은 한 고객 것이라, 고객별 값을 모두 더하면 {@link #revenue}가 된다.
     *
     * @return 고객 ID → 매출. 귀속 방문이 없는 고객은 빠진다
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> customerRevenues(long shopId, long campaignId, List<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> p = new java.util.HashMap<>(params(shopId, campaignId));
        p.put("customerIds", customerIds);
        Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query(
                "SELECT cv.customer_id, COALESCE(SUM(pay.amount), 0) AS revenue"
                        + " FROM (SELECT DISTINCT cm.customer_id, vs.visit_id"
                        + ATTRIBUTED_VISIT_SERVICE
                        + " AND cm.customer_id IN (:customerIds)) cv"
                        + " JOIN payment pay ON pay.visit_id = cv.visit_id"
                        + " AND pay.shop_id = :shopId AND pay.canceled = FALSE"
                        + " GROUP BY cv.customer_id",
                p,
                rs -> {
                    result.put(rs.getLong("customer_id"), rs.getLong("revenue"));
                });
        return result;
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
        Map<String, Object> reachedCycle =
                jdbc.queryForMap(
                        CYCLE_COUNTS
                                + "cm.control_group = FALSE AND cm.send_status = 'SENT'"
                                + CYCLE_SNAPSHOT,
                        p);
        Map<String, Object> controlCycle =
                jdbc.queryForMap(CYCLE_COUNTS + "cm.control_group = TRUE" + CYCLE_SNAPSHOT, p);
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
                                        rs.getLong("control_rebooked"),
                                        count(reachedCycle, "base"),
                                        count(reachedCycle, "revisited"),
                                        count(controlCycle, "base"),
                                        count(controlCycle, "revisited"))));
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

    /**
     * 발송 시각의 날짜가 기간 안인 광고 캠페인(동의 재확인 안내 · 미발송 · 취소 제외)의 퍼널 · 연결 매출 한 페이지와 기간 전체 합계 (FR-ANL-01 · 02,
     * 결정 F65). 발송 시각 늦은 순, 같으면 ID 큰 순. 귀속은 퍼널 · 매출과 같고 기간으로 거르지 않는다 (F33). 쿼리는 캠페인 ID · 페이지 · 합계 세
     * 번이다.
     *
     * @param from 발송일 시작 (포함)
     * @param to 발송일 끝 (포함)
     */
    @Transactional(readOnly = true)
    public CampaignResults campaignResults(
            long shopId, LocalDate from, LocalDate to, int page, int size) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM campaign WHERE shop_id = :shopId AND message_kind = 'AD'"
                                + " AND status <> 'CANCELED' AND sent_at >= :from AND sent_at < :toNext"
                                + " ORDER BY sent_at DESC, id DESC",
                        Map.of(
                                "shopId",
                                shopId,
                                "from",
                                from.atStartOfDay(),
                                "toNext",
                                to.plusDays(1).atStartOfDay()),
                        Long.class);
        if (ids.isEmpty()) {
            return new CampaignResults(List.of(), 0, new CampaignTotal(0, 0, 0, 0, 0, 0));
        }
        int start = (int) Math.min((long) page * size, ids.size());
        List<Long> pageIds = ids.subList(start, Math.min(start + size, ids.size()));
        List<CampaignResult> items =
                pageIds.isEmpty()
                        ? List.of()
                        : jdbc.query(
                                "SELECT c.id, c.name, c.category, c.sent_at,"
                                        + " COALESCE(se.n, 0) AS sent, COALESCE(rc.n, 0) AS reached,"
                                        + " COALESCE(rb.n, 0) AS rebooked, COALESCE(rv.n, 0) AS revisited,"
                                        + " COALESCE(rev.n, 0) AS revenue"
                                        + " FROM campaign c"
                                        + " LEFT JOIN (SELECT campaign_id, COUNT(DISTINCT customer_id) AS n"
                                        + SENT_MESSAGES
                                        + " GROUP BY campaign_id) se ON se.campaign_id = c.id"
                                        + " LEFT JOIN (SELECT campaign_id, COUNT(DISTINCT customer_id) AS n"
                                        + REACHED_MESSAGES
                                        + " GROUP BY campaign_id) rc ON rc.campaign_id = c.id"
                                        + " LEFT JOIN (SELECT c.id AS campaign_id,"
                                        + " COUNT(DISTINCT cm.customer_id) AS n"
                                        + REBOOKED
                                        + " GROUP BY c.id) rb ON rb.campaign_id = c.id"
                                        + " LEFT JOIN (SELECT c.id AS campaign_id,"
                                        + " COUNT(DISTINCT cm.customer_id) AS n"
                                        + REVISITED
                                        + " GROUP BY c.id) rv ON rv.campaign_id = c.id"
                                        // 같은 방문은 캠페인마다 한 번 (FR-ANL-02)
                                        + " LEFT JOIN (SELECT av.campaign_id, SUM(p.amount) AS n FROM"
                                        + " (SELECT DISTINCT c.id AS campaign_id, vs.visit_id"
                                        + ATTRIBUTED_VISIT_SERVICE
                                        + ") av JOIN payment p ON p.visit_id = av.visit_id"
                                        + " AND p.shop_id = :shopId AND p.canceled = FALSE"
                                        + " GROUP BY av.campaign_id) rev ON rev.campaign_id = c.id"
                                        + OF_CAMPAIGNS
                                        + " ORDER BY c.sent_at DESC, c.id DESC",
                                Map.of("shopId", shopId, "campaignIds", pageIds),
                                (rs, i) ->
                                        new CampaignResult(
                                                rs.getLong("id"),
                                                rs.getString("name"),
                                                rs.getString("category"),
                                                TimeConfig.offset(
                                                        rs.getObject(
                                                                "sent_at", LocalDateTime.class)),
                                                rs.getLong("sent"),
                                                rs.getLong("reached"),
                                                rs.getLong("rebooked"),
                                                rs.getLong("revisited"),
                                                rs.getLong("revenue")));
        // 합계: 같은 조각을 기간 안 캠페인 전체로 넓히고 고객 · 방문을 한 번만 센다 (F65)
        CampaignTotal total =
                jdbc.queryForObject(
                        "SELECT"
                                + " (SELECT COUNT(DISTINCT customer_id)"
                                + SENT_MESSAGES
                                + ") AS sent,"
                                + " (SELECT COUNT(DISTINCT customer_id)"
                                + REACHED_MESSAGES
                                + ") AS reached,"
                                + " (SELECT COUNT(DISTINCT r.customer_id) FROM ("
                                + REBOOKED_CUSTOMERS
                                + ") r) AS rebooked,"
                                + " (SELECT COUNT(DISTINCT cm.customer_id)"
                                + REVISITED
                                + ") AS revisited,"
                                + " (SELECT COALESCE(SUM(amount), 0) FROM payment"
                                + "   WHERE shop_id = :shopId AND canceled = FALSE"
                                + "   AND visit_id IN (SELECT vs.visit_id"
                                + ATTRIBUTED_VISIT_SERVICE
                                + ")) AS revenue",
                        Map.of("shopId", shopId, "campaignIds", ids),
                        (rs, i) ->
                                new CampaignTotal(
                                        ids.size(),
                                        rs.getLong("sent"),
                                        rs.getLong("reached"),
                                        rs.getLong("rebooked"),
                                        rs.getLong("revisited"),
                                        rs.getLong("revenue")));
        return new CampaignResults(items, ids.size(), total);
    }

    private static long count(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
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
