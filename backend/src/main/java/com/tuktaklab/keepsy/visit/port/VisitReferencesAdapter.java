package com.tuktaklab.keepsy.visit.port;

import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService.ClosingTarget;
import com.tuktaklab.keepsy.reservation.ReservationStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 예약은 reservation 도메인이 공개한 {@link ReservationAppService}로 부른다. 메뉴 · 고객은 조회 Service가 아직 없어 V1 · V2
 * 테이블을 직접 읽는다 (ponytail: 그 도메인에 조회 Service가 생기면 바꾼다).
 */
@Component
@RequiredArgsConstructor
class VisitReferencesAdapter implements VisitReferences {

    private final ReservationAppService reservations;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public ReservationToClose lockReservation(long shopId, long reservationId) {
        ClosingTarget t = reservations.lockForClosing(shopId, reservationId);
        return new ReservationToClose(
                t.id(),
                t.customerId(),
                t.staffId(),
                t.date(),
                t.status() == ReservationStatus.CONFIRMED,
                t.status() == ReservationStatus.COMPLETED,
                t.visitId(),
                t.plannedMenuIds());
    }

    @Override
    public void completeReservation(
            long shopId, long reservationId, long visitId, boolean confirmServices) {
        reservations.complete(shopId, reservationId, visitId, confirmServices);
    }

    @Override
    public Map<Long, String> menuCategories(long shopId, Collection<Long> serviceMenuIds) {
        Map<Long, String> result = new HashMap<>();
        if (serviceMenuIds.isEmpty()) {
            return result;
        }
        jdbc.query(
                "SELECT id, category FROM service_menu WHERE shop_id = :shopId AND id IN (:ids)",
                Map.of("shopId", shopId, "ids", serviceMenuIds),
                rs -> {
                    result.put(rs.getLong("id"), rs.getString("category"));
                });
        return result;
    }

    @Override
    public Set<Long> shopStaffIds(long shopId, Collection<Long> staffIds) {
        if (staffIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
                jdbc.queryForList(
                        "SELECT id FROM staff WHERE shop_id = :shopId AND id IN (:ids)",
                        Map.of("shopId", shopId, "ids", staffIds),
                        Long.class));
    }

    @Override
    public void lockCustomer(long shopId, long customerId) {
        jdbc.queryForList(
                "SELECT id FROM customer WHERE id = :customerId AND shop_id = :shopId FOR UPDATE",
                Map.of("shopId", shopId, "customerId", customerId),
                Long.class);
    }
}
