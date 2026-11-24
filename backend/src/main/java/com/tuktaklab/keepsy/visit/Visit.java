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

    /**
     * 예약 하나의 실제 시술을 방문 목록에 더한다. 그 예약의 시술이 이미 있으면 다시 만들지 않는다 (실제 시술 목록은 하나, FR-VIS-01).
     *
     * @return 새로 더했으면 true
     */
    public boolean addReservationServices(
            long reservationId, long staffId, List<ActualService> actualServices) {
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
                                staffId)));
        return true;
    }

    /** 실제 시술 한 건: 메뉴와 그 메뉴의 시술 카테고리 */
    public record ActualService(long serviceMenuId, String category) {}
}
