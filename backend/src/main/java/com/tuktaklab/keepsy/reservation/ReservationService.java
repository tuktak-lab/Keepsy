package com.tuktaklab.keepsy.reservation;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예약의 예정 시술 (명세서 5장 reservation_service, A13). 업무 Service 클래스와 헷갈리지 않도록 업무 Service는 다른 이름을 쓴다
 * (DEVELOPMENT 4장 「이름」).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    private Long serviceMenuId;

    /** 귀속 발송 campaign_message.id (F15, FR-VIS-04) */
    private Long attributedMessageId;

    ReservationService(Reservation reservation, Long shopId, Long serviceMenuId) {
        this.reservation = reservation;
        this.shopId = shopId;
        this.serviceMenuId = serviceMenuId;
    }

    /**
     * 캠페인 귀속 · 승계 (FR-VIS-04, FR-ANL-03). 판정은 {@link com.tuktaklab.keepsy.analytics.Attribution}이
     * 한다
     */
    void attribute(long messageId) {
        this.attributedMessageId = messageId;
    }
}
