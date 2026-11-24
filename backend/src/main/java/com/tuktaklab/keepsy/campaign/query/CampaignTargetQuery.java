package com.tuktaklab.keepsy.campaign.query;

import com.tuktaklab.keepsy.campaign.MessageKind;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
import com.tuktaklab.keepsy.prediction.PredictionStatusSql;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 캠페인 대상 후보와 제외 판정에 쓸 값 (FR-CMP-01 · 02, CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」). 고객 · 예측 · 동의 · 발송
 * 기록을 함께 거르는 화면용 횡단 조회라 조회 전용 SQL 하나로 읽는다 (DEVELOPMENT 4장 「도메인 연동」). 세션 매장 · 정상 고객만 (NFR-03,
 * FR-CUS-10). 한 고객은 한 번만 나온다 (한 캠페인에서 고객당 메시지 1건).
 */
@Repository
@RequiredArgsConstructor
public class CampaignTargetQuery {

    /**
     * 대상 고르기. null인 조건은 쓰지 않는다. 광고는 {@code category}의 "고객 × 카테고리" 행(예측 행)이 있는 고객 중에서, 동의 재확인 안내는
     * {@code customerIds} 중에서 고른다.
     */
    public record Selection(
            MessageKind kind,
            String category,
            List<Long> customerIds,
            Integer visitCountMin,
            Integer visitCountMax,
            PredictionStatus predictionStatus,
            LocalDate lastServiceFrom,
            LocalDate lastServiceTo) {}

    /**
     * @param marketingConsent 마케팅 동의 최신 행이 동의인지 (받은 적 없으면 false)
     * @param unsubscribed 최신 행의 철회 경로가 {@code UNSUBSCRIBE}인지
     * @param lastAdSentAt 발송 성공한 광고(대조군 제외)의 마지막 결과 시각. 없으면 null
     */
    public record Candidate(
            long customerId,
            String phone,
            boolean marketingConsent,
            boolean unsubscribed,
            LocalDateTime lastAdSentAt) {}

    private static final String LATEST_MARKETING =
            " FROM consent cs WHERE cs.customer_id = c.id AND cs.item = 'MARKETING'"
                    + " ORDER BY cs.id DESC LIMIT 1)";

    private final NamedParameterJdbcTemplate jdbc;

    public List<Candidate> find(long shopId, Selection s, LocalDate today) {
        boolean ad = s.kind() == MessageKind.AD;
        StringBuilder sql =
                new StringBuilder(
                        "SELECT c.id, c.phone, (SELECT cs.agreed"
                                + LATEST_MARKETING
                                + " AS agreed, (SELECT cs.withdrawal_path"
                                + LATEST_MARKETING
                                + " AS withdrawal_path,"
                                + " (SELECT MAX(m.result_at) FROM campaign_message m"
                                + " WHERE m.shop_id = :shopId AND m.customer_id = c.id AND m.kind = 'AD'"
                                + " AND m.send_status = 'SENT' AND m.control_group = FALSE) AS last_ad"
                                + " FROM customer c JOIN shop s ON s.id = c.shop_id");
        if (ad) {
            sql.append(
                    " JOIN customer_prediction cp ON cp.customer_id = c.id AND cp.category = :category");
        }
        sql.append(" WHERE c.shop_id = :shopId AND c.status = 'ACTIVE'");
        // 조건 칼럼 · 문장은 고정 문자열이고, 사용자 값은 모두 바인딩한다 (NFR-08)
        if (s.customerIds() != null) {
            sql.append(" AND c.id IN (:customerIds)");
        }
        String visitCount = "(SELECT COUNT(*) FROM visit v WHERE v.customer_id = c.id)";
        if (s.visitCountMin() != null) {
            sql.append(" AND ").append(visitCount).append(" >= :visitCountMin");
        }
        if (s.visitCountMax() != null) {
            sql.append(" AND ").append(visitCount).append(" <= :visitCountMax");
        }
        if (ad && s.predictionStatus() != null) {
            sql.append(" AND (").append(PredictionStatusSql.EXPRESSION).append(") = :status");
        }
        if (ad && s.lastServiceFrom() != null) {
            sql.append(" AND cp.last_service_date >= :lastServiceFrom");
        }
        if (ad && s.lastServiceTo() != null) {
            sql.append(" AND cp.last_service_date <= :lastServiceTo");
        }
        sql.append(" ORDER BY c.id");
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("shopId", shopId)
                        .addValue("today", today)
                        .addValue("category", s.category())
                        .addValue("customerIds", s.customerIds())
                        .addValue("visitCountMin", s.visitCountMin())
                        .addValue("visitCountMax", s.visitCountMax())
                        .addValue(
                                "status",
                                s.predictionStatus() == null ? null : s.predictionStatus().name())
                        .addValue("lastServiceFrom", s.lastServiceFrom())
                        .addValue("lastServiceTo", s.lastServiceTo());
        return jdbc.query(
                sql.toString(),
                p,
                (rs, i) ->
                        new Candidate(
                                rs.getLong("id"),
                                rs.getString("phone"),
                                rs.getBoolean("agreed"),
                                "UNSUBSCRIBE".equals(rs.getString("withdrawal_path")),
                                rs.getObject("last_ad", LocalDateTime.class)));
    }

    /** 재발송 제한 기간 (FR-SET-04, 기본 7일) */
    public int resendLimitDays(long shopId) {
        return jdbc.queryForObject(
                "SELECT resend_block_days FROM shop WHERE id = :shopId",
                new MapSqlParameterSource("shopId", shopId),
                Integer.class);
    }
}
