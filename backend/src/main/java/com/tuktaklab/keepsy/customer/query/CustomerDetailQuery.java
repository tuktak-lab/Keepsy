package com.tuktaklab.keepsy.customer.query;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.customer.Customer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 고객 상세 (FR-CUS-03 · 04 · 05, CONTRACTS 6장 「고객 (CUS)」). 노쇼 횟수를 예약에서 함께 세는 화면용 조회라 조회 전용 SQL로 필요한
 * 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 세션 매장 · 정상 고객만 (NFR-03, C12).
 */
@Repository
@RequiredArgsConstructor
public class CustomerDetailQuery {

    public record PreferenceTag(String category, String tag) {}

    /** CONTRACTS 6장 「`GET /api/customers/{customerId}`」 응답. 휴대폰은 마스킹한 값 (NFR-04) */
    public record Detail(
            long customerId,
            String name,
            String phone,
            Customer.Gender gender,
            Integer birthYear,
            Customer.PreferredLanguage preferredLanguage,
            Customer.AcquisitionChannel acquisitionChannel,
            OffsetDateTime registeredAt,
            String serviceCaution,
            String healthCaution,
            int noShowCount,
            boolean bookingRestricted,
            String bookingRestrictionReason,
            List<PreferenceTag> preferenceTags,
            String preferenceMemo) {}

    private final NamedParameterJdbcTemplate jdbc;

    /** 다른 매장 · 삭제된 고객이면 비어 있다 */
    public Optional<Detail> find(long shopId, long customerId) {
        Map<String, Long> p = Map.of("shopId", shopId, "customerId", customerId);
        List<PreferenceTag> tags =
                jdbc.query(
                        "SELECT category, tag FROM preference_tag"
                                + " WHERE shop_id = :shopId AND customer_id = :customerId"
                                + " ORDER BY category, tag",
                        p,
                        (rs, i) ->
                                new PreferenceTag(rs.getString("category"), rs.getString("tag")));
        return jdbc
                .query(
                        "SELECT c.*, (SELECT COUNT(*) FROM reservation r WHERE r.customer_id = c.id"
                                + " AND r.status = 'NO_SHOW') AS no_show_count"
                                + " FROM customer c"
                                + " WHERE c.id = :customerId AND c.shop_id = :shopId AND c.status = 'ACTIVE'",
                        p,
                        (rs, i) -> {
                            boolean restricted = rs.getBoolean("reservation_restricted");
                            return new Detail(
                                    rs.getLong("id"),
                                    rs.getString("name"),
                                    Customer.maskPhone(rs.getString("phone")),
                                    valueOf(Customer.Gender.class, rs.getString("gender")),
                                    rs.getObject("birth_year", Integer.class),
                                    valueOf(
                                            Customer.PreferredLanguage.class,
                                            rs.getString("preferred_language")),
                                    valueOf(
                                            Customer.AcquisitionChannel.class,
                                            rs.getString("acquisition_channel")),
                                    TimeConfig.offset(
                                            rs.getObject("registered_at", LocalDateTime.class)),
                                    rs.getString("service_caution"),
                                    rs.getString("health_caution"),
                                    rs.getInt("no_show_count"),
                                    restricted,
                                    restricted ? rs.getString("reservation_restrict_reason") : null,
                                    tags,
                                    rs.getString("preference_memo"));
                        })
                .stream()
                .findFirst();
    }

    private static <E extends Enum<E>> E valueOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
