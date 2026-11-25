package com.tuktaklab.keepsy.customer.port;

/**
 * 고객 삭제(파기) · 파기 대상이 다른 도메인(예약 · 예측 · 발송 기록 · 매장 설정)에서 필요한 값과 동작 (FR-CUS-10 · 12, C12, DEVELOPMENT
 * 4장 「도메인 연동」). 모두 부르는 쪽 트랜잭션에서 함께 커밋된다.
 */
public interface CustomerDeletionReferences {

    /** 삭제하면 자동 취소될 확정 예약 수 (시작 시각과 관계없음) */
    int confirmedReservationCount(long shopId, long customerId);

    /** 확정 예약 취소 · 예약 메모 비움, 현재 예측 삭제, 발송 기록의 수신 번호 · 본문 비움 */
    void eraseElsewhere(long shopId, long customerId);

    /** 매장 설정 보관 기간(년) (FR-SET-06) */
    int retentionYears(long shopId);
}
