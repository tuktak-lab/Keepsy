package com.tuktaklab.keepsy.visit.query;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시술 마감 카드 채울 값 (FR-PAY-01 · 04, FR-VIS-05, CONTRACTS 6장 「시술 마감 · 결제 (PAY)」). 예약 · 메뉴 · 스탭 · 고객 ·
 * 동의를 함께 읽는 화면용 횡단 조회라 조회 전용 SQL로 필요한 칼럼만 가져온다 (DEVELOPMENT 4장 「도메인 연동」). 모두 세션 매장 조건 (NFR-03).
 */
@Repository
@RequiredArgsConstructor
public class ClosingCardQuery {

    /** 동의 받기 대상 항목, 3절 「동의 항목」 순서. 개인정보(PRIVACY)는 고객 등록 때 필수라 빠진다 (FR-CUS-01) */
    private static final List<String> REQUESTABLE =
            List.of("PHOTO", "MARKETING", "NIGHT_AD", "HEALTH");

    public record Card(
            long reservationId,
            String status,
            boolean servicesConfirmRequired,
            Long visitId,
            CustomerRef customer,
            List<Service> services,
            boolean photoAllowed,
            List<String> consentsToRequest) {}

    public record CustomerRef(long id, String name) {}

    public record StaffRef(long id, String name) {}

    public record Service(
            long serviceMenuId,
            String name,
            String category,
            int price,
            String predictionAttribute,
            StaffRef staff,
            boolean cycleExcluded) {}

    private static final String SERVICE_COLUMNS =
            "SELECT m.id AS menu_id, m.name, m.category, m.price, m.prediction_attribute,"
                    + " st.id AS staff_id, st.name AS staff_name";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @throws ApiException 없거나 다른 매장 예약이면 {@code not_found}, 취소 · 노쇼 예약이면 {@code conflict}
     */
    @Transactional(readOnly = true)
    public Card get(long shopId, long reservationId) {
        Map<String, Object> p = new HashMap<>(Map.of("shopId", shopId, "id", reservationId));
        Map<String, Object> r;
        try {
            r =
                    jdbc.queryForMap(
                            "SELECT status, consult_first, services_confirmed, visit_id, customer_id,"
                                    + " staff_id FROM reservation WHERE id = :id AND shop_id = :shopId",
                            p);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        String status = (String) r.get("status");
        boolean confirmed = "CONFIRMED".equals(status);
        if (!confirmed && !"COMPLETED".equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT, "취소 · 노쇼 예약은 마감할 수 없습니다");
        }
        p.put("staffId", r.get("staff_id"));
        // 확정이면 예정 시술을 예약 담당 스탭으로, 완료면 이 예약으로 기록된 실제 시술 (A13, E4)
        List<Service> services =
                jdbc.query(
                        confirmed
                                ? SERVICE_COLUMNS
                                        + ", FALSE AS cycle_excluded FROM reservation_service rs"
                                        + " JOIN service_menu m ON m.id = rs.service_menu_id"
                                        + " JOIN staff st ON st.id = :staffId"
                                        + " WHERE rs.reservation_id = :id AND rs.shop_id = :shopId"
                                        + " ORDER BY rs.id"
                                : SERVICE_COLUMNS
                                        + ", vs.cycle_excluded FROM visit_service vs"
                                        + " JOIN service_menu m ON m.id = vs.service_menu_id"
                                        + " JOIN staff st ON st.id = vs.staff_id"
                                        + " WHERE vs.reservation_id = :id AND vs.shop_id = :shopId"
                                        + " ORDER BY vs.id",
                        p,
                        (rs, i) ->
                                new Service(
                                        rs.getLong("menu_id"),
                                        rs.getString("name"),
                                        rs.getString("category"),
                                        rs.getInt("price"),
                                        rs.getString("prediction_attribute"),
                                        new StaffRef(
                                                rs.getLong("staff_id"), rs.getString("staff_name")),
                                        rs.getBoolean("cycle_excluded")));

        Long customerId =
                r.get("customer_id") == null ? null : ((Number) r.get("customer_id")).longValue();
        CustomerRef customer = null;
        List<String> agreed = List.of();
        if (customerId != null) {
            p.put("customerId", customerId);
            customer =
                    jdbc.queryForObject(
                            "SELECT id, name FROM customer WHERE id = :customerId AND shop_id = :shopId",
                            p,
                            (rs, i) -> new CustomerRef(rs.getLong("id"), rs.getString("name")));
            // 항목별 최신 행이 현재 상태 (E8)
            agreed =
                    jdbc.queryForList(
                            "SELECT c.item FROM consent c WHERE c.customer_id = :customerId"
                                    + " AND c.agreed AND c.id = (SELECT MAX(c2.id) FROM consent c2"
                                    + " WHERE c2.customer_id = c.customer_id AND c2.item = c.item)",
                            p,
                            String.class);
        }
        List<String> toRequest = new ArrayList<>(customer == null ? List.of() : REQUESTABLE);
        toRequest.removeAll(agreed);
        Long visitId = r.get("visit_id") == null ? null : ((Number) r.get("visit_id")).longValue();
        return new Card(
                reservationId,
                status,
                confirmed
                        && Boolean.TRUE.equals(r.get("consult_first"))
                        && !Boolean.TRUE.equals(r.get("services_confirmed")),
                visitId,
                customer,
                services,
                agreed.contains("PHOTO"),
                toRequest);
    }
}
