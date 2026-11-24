package com.tuktaklab.keepsy.visit;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 (명세서 5장 payment, FR-PAY-01). 예약에서만 등록하고(FR-VIS-02) 방문에 붙는다. 한 방문에 여러 건 가능 (C6, A13). 시술 항목은 따로
 * 두지 않고 방문의 실제 시술을 쓴다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId;

    /** 고객 없음으로 마감하면 null (E3) */
    private Long customerId;

    private Long reservationId;

    private Long visitId;

    /** 원 단위 정수 */
    private int amount;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    private LocalDateTime paidAt;

    private boolean canceled;

    static Payment of(
            long shopId,
            Long customerId,
            long reservationId,
            long visitId,
            int amount,
            PaymentMethod method,
            LocalDateTime paidAt) {
        Payment p = new Payment();
        p.shopId = shopId;
        p.customerId = customerId;
        p.reservationId = reservationId;
        p.visitId = visitId;
        p.amount = amount;
        p.method = method;
        p.paidAt = paidAt;
        return p;
    }
}
