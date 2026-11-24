package com.tuktaklab.keepsy.visit.port;

import com.tuktaklab.keepsy.customer.PhotoStorage;
import com.tuktaklab.keepsy.prediction.PredictionAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService.ClosingTarget;
import com.tuktaklab.keepsy.reservation.ReservationStatus;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 예약은 reservation 도메인이 공개한 {@link ReservationAppService}로 부른다. 메뉴 · 고객은 조회 Service가 아직 없어 V1 · V2
 * 테이블을 직접 읽는다. 사진도 customer 도메인에 사진 Service가 아직 없어 행 삭제를 여기서 한다 (ponytail: 그 도메인에 Service가 생기면
 * 바꾼다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class VisitReferencesAdapter implements VisitReferences {

    private final ReservationAppService reservations;
    private final NamedParameterJdbcTemplate jdbc;
    private final PhotoStorage photoStorage;
    private final PredictionAppService predictions;

    @Override
    public void recalculatePrediction(long shopId, long customerId) {
        predictions.recalculate(shopId, customerId);
    }

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
    public void reopenReservations(long shopId, long visitId) {
        reservations.reopenForVisit(shopId, visitId);
    }

    @Override
    public Long earliestReservationId(long shopId, long visitId) {
        return reservations.earliestReservationIdForVisit(shopId, visitId).orElse(null);
    }

    @Override
    public void deletePhotos(long shopId, long visitId) {
        Map<String, Object> p = Map.of("shopId", shopId, "visitId", visitId);
        List<String> keys =
                jdbc.queryForList(
                        "SELECT storage_key FROM photo WHERE shop_id = :shopId AND visit_id = :visitId",
                        p,
                        String.class);
        jdbc.update("DELETE FROM photo WHERE shop_id = :shopId AND visit_id = :visitId", p);
        if (keys.isEmpty()) {
            return;
        }
        // 롤백되면 파일이 남아야 하므로 커밋 뒤에 지운다. 실패해도 DB는 이미 맞으니 기록만 남긴다
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (String key : keys) {
                            try {
                                photoStorage.delete(key);
                            } catch (IOException e) {
                                log.warn("사진 파일 삭제 실패 visitId={}", visitId, e);
                            }
                        }
                    }
                });
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
