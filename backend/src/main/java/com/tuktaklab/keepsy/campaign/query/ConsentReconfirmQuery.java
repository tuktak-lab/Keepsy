package com.tuktaklab.keepsy.campaign.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 동의 재확인 대상 (FR-CMP-13, D1, 결정 F45, CONTRACTS 6장 「캠페인 · 발송 · 가상 휴대폰 (CMP)」). 고객 · 동의 이력 · 발송 기록을 함께
 * 읽는 화면용 횡단 조회라 조회 전용 SQL 하나로 거르고 정렬한다 (DEVELOPMENT 4장 「도메인 연동」). 재확인 상태 칸 없이 이력으로 계산한다.
 */
@Repository
@RequiredArgsConstructor
public class ConsentReconfirmQuery {

    /** CONTRACTS 6장 「`GET /api/consent-reconfirm-targets` 항목 필드」 */
    public record Row(
            long customerId,
            String name,
            String phone,
            LocalDate consentDate,
            OffsetDateTime lastReconfirmedAt,
            LocalDate dueDate,
            boolean nightAdConsent) {}

    /**
     * 마케팅 동의 최신 행이 동의인 정상 고객. 최신 행의 일시가 유효 동의일이다 (정정 행이면 효력 일시, F44). 마지막 재확인은 동의 재확인 안내의 발송 성공 행만
     * 센다 (대기 · 실패 · 대조군 제외, F45). 번호는 응답 단계에서 마스킹한다 (NFR-04).
     */
    private static final String TARGETS =
            "SELECT c.id, c.name,"
                    + " CONCAT(LEFT(c.phone, 4), '****', SUBSTRING(c.phone, 9)) AS masked_phone,"
                    + " DATE(cs.recorded_at) AS consent_date,"
                    + " (SELECT MAX(cm.result_at) FROM campaign_message cm"
                    + "   WHERE cm.customer_id = c.id AND cm.kind = 'CONSENT_RECONFIRM'"
                    + "   AND cm.send_status = 'SENT' AND NOT cm.control_group) AS last_reconfirmed_at,"
                    + " COALESCE((SELECT n.agreed FROM consent n WHERE n.customer_id = c.id"
                    + "   AND n.item = 'NIGHT_AD' ORDER BY n.id DESC LIMIT 1), FALSE) AS night_ad"
                    + " FROM customer c JOIN consent cs ON cs.id = (SELECT MAX(x.id) FROM consent x"
                    + "   WHERE x.customer_id = c.id AND x.item = 'MARKETING')"
                    + " WHERE c.shop_id = :shopId AND c.status = 'ACTIVE' AND cs.agreed";

    /** 기준일 = 동의일과 마지막 재확인 날짜 중 늦은 날, 기한 = 기준일 + 2년, 기한 30일 전부터 (D1, F45) */
    private static final String DUE =
            "SELECT t.*, DATE_ADD(GREATEST(t.consent_date,"
                    + " COALESCE(DATE(t.last_reconfirmed_at), t.consent_date)), INTERVAL 2 YEAR) AS due_date"
                    + " FROM ("
                    + TARGETS
                    + ") t HAVING :today >= DATE_SUB(due_date, INTERVAL 30 DAY)";

    private final NamedParameterJdbcTemplate jdbc;

    /** 기한이 이른 순, 같으면 고객 순 */
    public List<Row> find(long shopId, LocalDate today, int page, int size) {
        return jdbc.query(
                DUE + " ORDER BY due_date, id LIMIT :limit OFFSET :offset",
                params(shopId, today)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                (rs, i) -> {
                    LocalDateTime last = rs.getObject("last_reconfirmed_at", LocalDateTime.class);
                    return new Row(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("masked_phone"),
                            rs.getObject("consent_date", LocalDate.class),
                            last == null ? null : TimeConfig.offset(last),
                            rs.getObject("due_date", LocalDate.class),
                            rs.getBoolean("night_ad"));
                });
    }

    public long count(long shopId, LocalDate today) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (" + DUE + ") d", params(shopId, today), Long.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource params(long shopId, LocalDate today) {
        return new MapSqlParameterSource("shopId", shopId).addValue("today", today);
    }
}
