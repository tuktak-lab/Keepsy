package com.tuktaklab.keepsy.reservation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예약 (명세서 5장 reservation, FR-RSV-01·03). 상태를 바꾸는 규칙은 이 엔티티 메서드에 두고, 어기면 예외를 던진다 (DEVELOPMENT 4장).
 * 고객 · 스탭 · 방문은 다른 도메인이라 ID로만 참조한다.
 */
// ponytail: 규칙 위반은 IllegalArgumentException(→ invalid) · IllegalStateException(→ conflict). 공통
// 예외(#1)가 생기면 그 오류 code로 바꾼다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 고객 없는 예약이면 null (E3) */
    private Long customerId;

    private Long staffId;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    private ReservationChannel channel;

    /** [상담 후 진행] (A13) */
    private boolean consultFirst;

    /** 상담 후 진행일 때 실제 시술 확인 여부 */
    private boolean servicesConfirmed;

    @Column(columnDefinition = "TEXT")
    private String memo;

    /** 완료 시 */
    private Long visitId;

    /** 귀속 계산 기준 시각 (FR-VIS-04, E3) */
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationService> services = new ArrayList<>();

    /**
     * 확정 상태로 만든다 (FR-RSV-01). 겹침 검사({@link AvailableTimes})와 스탭 · 고객 확인은 호출하는 Service가 먼저 한다.
     *
     * @param customerId 고객 없는 예약이면 null. 이때 메모는 받지 않는다 (E3)
     * @param serviceMenuIds 예정 시술 메뉴. 1개 이상 (A13)
     * @param minutes 소요 시간 (30분 단위)
     * @param now 지금 (Asia/Seoul)
     */
    public static Reservation create(
            Long shopId,
            Long customerId,
            Long staffId,
            LocalDateTime startAt,
            int minutes,
            ReservationChannel channel,
            boolean consultFirst,
            String memo,
            List<Long> serviceMenuIds,
            LocalDateTime now) {
        if (serviceMenuIds == null || serviceMenuIds.isEmpty()) {
            throw new IllegalArgumentException("예정 시술은 1개 이상이어야 한다");
        }
        if (minutes <= 0 || minutes % AvailableTimes.SLOT_MINUTES != 0) {
            throw new IllegalArgumentException("소요 시간은 30분 단위여야 한다: " + minutes);
        }
        if (customerId == null && memo != null && !memo.isBlank()) {
            throw new IllegalArgumentException("고객 없는 예약은 메모를 받지 않는다");
        }
        Reservation r = new Reservation();
        r.shopId = shopId;
        r.customerId = customerId;
        r.staffId = staffId;
        r.startAt = startAt;
        r.endAt = startAt.plusMinutes(minutes);
        r.status = ReservationStatus.CONFIRMED;
        r.channel = channel == null ? ReservationChannel.STORE : channel;
        r.consultFirst = consultFirst;
        r.memo = customerId == null ? null : memo;
        r.createdAt = now;
        serviceMenuIds.forEach(r::addService);
        return r;
    }

    /** 확정 → 취소. 그 시간은 다시 예약할 수 있다 (FR-RSV-03·04). */
    public void cancel() {
        requireConfirmed("취소");
        status = ReservationStatus.CANCELED;
    }

    /** 확정 → 노쇼. 예약 시작 시각이 지난 뒤에만 (C8). 고객 없는 예약도 가능하다 (E3). */
    public void markNoShow(LocalDateTime now) {
        requireConfirmed("노쇼 처리");
        if (!now.isAfter(startAt)) {
            throw new IllegalStateException("노쇼는 예약 시작 시각이 지난 뒤에만 처리할 수 있다");
        }
        status = ReservationStatus.NO_SHOW;
    }

    /**
     * 고객 없는 확정 예약에 고객을 연결한다 (FR-RSV-01, FR-DSH-01, E3). 완료된 예약은 마감 카드에서 고객 없음으로 마감한 것이라 연결하지 않는다
     * (FR-VIS-03).
     */
    public void linkCustomer(Long customerId) {
        if (this.customerId != null) {
            throw new IllegalStateException("이미 고객이 연결된 예약이다");
        }
        requireConfirmed("고객 연결");
        this.customerId = customerId;
    }

    private void addService(Long serviceMenuId) {
        services.add(new ReservationService(this, shopId, serviceMenuId));
    }

    private void requireConfirmed(String action) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException(action + "는 확정 예약만 할 수 있다: " + status);
        }
    }
}
