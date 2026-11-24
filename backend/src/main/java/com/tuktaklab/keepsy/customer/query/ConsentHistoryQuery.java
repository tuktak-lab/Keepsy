package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.Consent;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 동의 이력 (FR-CUS-09, CONTRACTS 6장 「고객 (CUS)」). 처리 스탭 이름을 함께 읽는 화면용 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다
 * (DEVELOPMENT 4장 「도메인 연동」). 세션 매장 조건 (NFR-03).
 */
@Repository
@RequiredArgsConstructor
public class ConsentHistoryQuery {

    public record StaffRef(long id, String name) {}

    /** CONTRACTS 6장 「`history` 항목 필드」 */
    public record Row(
            long consentId,
            Consent.Item item,
            boolean agreed,
            OffsetDateTime recordedAt,
            StaffRef staff,
            Consent.Method method,
            String withdrawalPath,
            Long correctsConsentId) {}

    private final NamedParameterJdbcTemplate jdbc;

    /** 기록한 순서(id 오름차순). 그래서 항목별 마지막 행이 현재 상태다 (명세서 5장 consent) */
    public List<Row> find(long shopId, long customerId) {
        return jdbc.query(
                "SELECT cs.id, cs.item, cs.agreed, cs.recorded_at, cs.method, cs.withdrawal_path,"
                        + " cs.corrects_consent_id,"
                        + " st.id AS staff_id, st.name AS staff_name"
                        + " FROM consent cs LEFT JOIN staff st ON st.id = cs.staff_id"
                        + " WHERE cs.shop_id = :shopId AND cs.customer_id = :customerId"
                        + " ORDER BY cs.id",
                Map.of("shopId", shopId, "customerId", customerId),
                (rs, i) -> {
                    Long staffId = rs.getObject("staff_id", Long.class);
                    String method = rs.getString("method");
                    return new Row(
                            rs.getLong("id"),
                            Consent.Item.valueOf(rs.getString("item")),
                            rs.getBoolean("agreed"),
                            TimeConfig.offset(rs.getObject("recorded_at", LocalDateTime.class)),
                            staffId == null
                                    ? null
                                    : new StaffRef(staffId, rs.getString("staff_name")),
                            method == null ? null : Consent.Method.valueOf(method),
                            rs.getString("withdrawal_path"),
                            rs.getObject("corrects_consent_id", Long.class));
                });
    }
}
