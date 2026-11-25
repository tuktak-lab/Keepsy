package com.tuktaklab.keepsy.campaign.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 캠페인 대상(campaign_target)과 변수 치환에 쓰는 고객별 값 (F41, F46, F47). 고객 · 동의 · 예측 · 방문을 함께 읽는 횡단 조회라 조회 전용
 * SQL로 쓴다 (DEVELOPMENT 4장 「도메인 연동」). 미리보기(#92)와 발송이 같이 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class CampaignRecipientQuery {

    /**
     * @param name 삭제된 고객이면 null (C12)
     * @param marketingConsentAt 마케팅 동의 최신 행이 동의일 때 그 일시(정정 행이면 효력 일시, F44). 아니면 null (F47)
     * @param nightAdConsent 야간 광고 동의 최신 행이 동의인지 (F46)
     * @param lastServiceDate 캠페인 카테고리 예측의 마지막 시술일. 카테고리 없음 · 예측 없음이면 null
     * @param lastServiceName 그날 그 카테고리에서 주기 계산 제외가 아닌 첫 시술(저장 순)의 메뉴 이름. 메뉴가 없으면 카테고리 이름 (F46)
     */
    public record Recipient(
            long customerId,
            boolean controlGroup,
            boolean deleted,
            String name,
            LocalDateTime marketingConsentAt,
            boolean nightAdConsent,
            LocalDate lastServiceDate,
            String lastServiceName) {}

    private static final String SQL =
            "SELECT t.customer_id, t.control_group, c.status = 'DELETED' AS deleted,"
                    + " CASE WHEN c.status = 'DELETED' THEN NULL ELSE c.name END AS name,"
                    + " (SELECT CASE WHEN m.agreed THEN m.recorded_at END FROM consent m"
                    + "   WHERE m.customer_id = t.customer_id AND m.item = 'MARKETING'"
                    + "   ORDER BY m.id DESC LIMIT 1) AS marketing_at,"
                    + " COALESCE((SELECT n.agreed FROM consent n WHERE n.customer_id = t.customer_id"
                    + "   AND n.item = 'NIGHT_AD' ORDER BY n.id DESC LIMIT 1), FALSE) AS night_ad,"
                    + " p.last_service_date,"
                    + " (SELECT COALESCE(sm.name, CASE vs.category WHEN 'NAIL' THEN '네일'"
                    + "     WHEN 'LASH' THEN '속눈썹' WHEN 'WAXING' THEN '왁싱'"
                    + "     WHEN 'SEMI_PERMANENT' THEN '반영구' ELSE '기타' END)"
                    + "   FROM visit v JOIN visit_service vs ON vs.visit_id = v.id"
                    + "   LEFT JOIN service_menu sm ON sm.id = vs.service_menu_id"
                    + "   WHERE v.customer_id = t.customer_id AND v.visit_date = p.last_service_date"
                    + "   AND vs.category = p.category AND NOT vs.cycle_excluded"
                    + "   ORDER BY vs.id LIMIT 1) AS last_service_name"
                    + " FROM campaign_target t JOIN customer c ON c.id = t.customer_id"
                    + " LEFT JOIN customer_prediction p ON p.customer_id = t.customer_id"
                    + "   AND p.category = :category"
                    + " WHERE t.campaign_id = :campaignId AND t.shop_id = :shopId"
                    + " ORDER BY t.customer_id";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 대조군을 포함한 전체 대상, 고객 ID 순.
     *
     * @param category 캠페인 카테고리. null이면 {최근시술} {경과일} 값이 없다
     */
    public List<Recipient> find(long shopId, long campaignId, String category) {
        return jdbc.query(
                SQL,
                new MapSqlParameterSource("shopId", shopId)
                        .addValue("campaignId", campaignId)
                        .addValue("category", category),
                (rs, i) ->
                        new Recipient(
                                rs.getLong("customer_id"),
                                rs.getBoolean("control_group"),
                                rs.getBoolean("deleted"),
                                rs.getString("name"),
                                rs.getObject("marketing_at", LocalDateTime.class),
                                rs.getBoolean("night_ad"),
                                rs.getObject("last_service_date", LocalDate.class),
                                rs.getString("last_service_name")));
    }
}
