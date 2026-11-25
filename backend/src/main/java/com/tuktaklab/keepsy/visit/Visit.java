package com.tuktaklab.keepsy.visit;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방문 (명세서 5장 visit, FR-VIS-01·02). 같은 고객 · 같은 날은 1건이고 시술 건수는 {@link VisitService}로 따로 센다 (C6). 고객 ·
 * 예약은 다른 도메인이라 ID로만 참조한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 고객 없음으로 마감하면 null (E3) */
    private Long customerId;

    /** 처리한 날이 아니라 예약일 (FR-VIS-01) */
    private LocalDate visitDate;

    @Enumerated(EnumType.STRING)
    private VisitSource source;

    @OneToMany(mappedBy = "visit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VisitService> services = new ArrayList<>();

    /** 예약에서 생기는 방문. 워크인은 없다 (FR-VIS-02) */
    public static Visit fromReservation(long shopId, Long customerId, LocalDate visitDate) {
        Visit v = new Visit();
        v.shopId = shopId;
        v.customerId = customerId;
        v.visitDate = visitDate;
        v.source = VisitSource.NORMAL;
        return v;
    }

    /** CSV로 가져온 과거 방문 (FR-CUS-13). 매출 · 귀속에서 빠진다 */
    static Visit imported(long shopId, long customerId, LocalDate visitDate) {
        Visit v = new Visit();
        v.shopId = shopId;
        v.customerId = customerId;
        v.visitDate = visitDate;
        v.source = VisitSource.IMPORT;
        return v;
    }

    /**
     * 가져온 방문에 메뉴 없는 시술(카테고리만, A13)을 더한다. 매장 방문이거나 그 카테고리가 이미 있으면 더하지 않는다 (C6, F50).
     *
     * @return 더했으면 true
     */
    boolean addImportedService(String category) {
        if (source != VisitSource.IMPORT
                || services.stream().anyMatch(s -> s.getCategory().equals(category))) {
            return false;
        }
        services.add(new VisitService(this, shopId, null, null, category, null, false));
        return true;
    }

    /**
     * 예약 하나의 실제 시술을 방문 목록에 더한다. 그 예약의 시술이 이미 있으면 다시 만들지 않는다 (실제 시술 목록은 하나, FR-VIS-01).
     *
     * @return 새로 더했으면 true
     */
    public boolean addReservationServices(long reservationId, List<ActualService> actualServices) {
        boolean already =
                services.stream()
                        .anyMatch(s -> Long.valueOf(reservationId).equals(s.getReservationId()));
        if (already) {
            return false;
        }
        actualServices.forEach(a ->
                services.add(
                        new VisitService(
                                this,
                                shopId,
                                reservationId,
                                a.serviceMenuId(),
                                a.category(),
                                a.staffId(),
                                a.cycleExcluded())));
        return true;
    }

    /**
     * 방문 수정 (FR-VIS-03 · 05, F25). 같은 메뉴의 기존 시술은 행을 남기고 담당 스탭 · 주기 계산 제외만 바꿔 귀속(F15)을 지키고, 빠진 메뉴의
     * 시술은 지우고, 새 메뉴는 {@code newReservationId} 예약의 시술로 더한다.
     */
    public void replaceServices(List<ActualService> actualServices, Long newReservationId) {
        List<VisitService> left = new ArrayList<>(services);
        List<VisitService> added = new ArrayList<>();
        for (ActualService a : actualServices) {
            VisitService same =
                    left.stream()
                            .filter(s ->
                                    Long.valueOf(a.serviceMenuId()).equals(s.getServiceMenuId()))
                            .findFirst()
                            .orElse(null);
            if (same != null) {
                left.remove(same);
                same.edit(a.staffId(), a.cycleExcluded());
            } else {
                added.add(
                        new VisitService(
                                this,
                                shopId,
                                newReservationId,
                                a.serviceMenuId(),
                                a.category(),
                                a.staffId(),
                                a.cycleExcluded()));
            }
        }
        services.removeAll(left);
        services.addAll(added);
    }

    /** 실제 시술 한 건: 메뉴, 그 메뉴의 시술 카테고리, 시술 담당 스탭, 이번 주기 계산 제외 (A13, FR-VIS-05) */
    public record ActualService(
            long serviceMenuId, String category, long staffId, boolean cycleExcluded) {}
}
