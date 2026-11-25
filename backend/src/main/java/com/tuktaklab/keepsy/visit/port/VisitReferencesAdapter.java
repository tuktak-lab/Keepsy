package com.tuktaklab.keepsy.visit.port;

import com.tuktaklab.keepsy.account.ShopSettingsService;
import com.tuktaklab.keepsy.analytics.Attribution.SentAd;
import com.tuktaklab.keepsy.campaign.SentAdQueryService;
import com.tuktaklab.keepsy.customer.PhotoFileService;
import com.tuktaklab.keepsy.prediction.PredictionAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.reservation.ReservationAppService.ClosingTarget;
import com.tuktaklab.keepsy.reservation.ReservationStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 예약은 reservation 도메인이 공개한 {@link ReservationAppService}로, 발송 기록 · 귀속 기간은 campaign · account가 공개한
 * Service로 부른다. 메뉴 · 고객은 조회 Service가 아직 없어 V1 · V2 테이블을 직접 읽는다. 사진 삭제는 customer 도메인이 공개한 {@link
 * PhotoFileService}로 한다.
 */
@Component
@RequiredArgsConstructor
class VisitReferencesAdapter implements VisitReferences {

    private final ReservationAppService reservations;
    private final NamedParameterJdbcTemplate jdbc;
    private final PhotoFileService photoFiles;
    private final PredictionAppService predictions;
    private final SentAdQueryService sentAds;
    private final ShopSettingsService shopSettings;

    @Override
    public List<SentAd> sentAds(long shopId, long customerId) {
        return sentAds.sentAds(shopId, customerId).stream()
                .map(m -> new SentAd(m.messageId(), m.category(), m.sentAt()))
                .toList();
    }

    @Override
    public int attributionDays(long shopId) {
        return shopSettings.attributionDays(shopId);
    }

    @Override
    public void recalculatePredictions(long shopId, Collection<Long> customerIds) {
        predictions.recalculate(shopId, customerIds);
    }

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
                t.plannedMenuIds(),
                t.startAt(),
                t.attributedMessageIds());
    }

    @Override
    public Long reservationCustomerId(long shopId, long reservationId) {
        return reservations.customerIdForClosing(shopId, reservationId);
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
        photoFiles.deleteVisitPhotos(shopId, visitId);
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

    @Override
    public boolean isActiveCustomer(long shopId, long customerId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM customer WHERE id = :customerId"
                                + " AND shop_id = :shopId AND status = 'ACTIVE')",
                        Map.of("shopId", shopId, "customerId", customerId),
                        Boolean.class));
    }
}
