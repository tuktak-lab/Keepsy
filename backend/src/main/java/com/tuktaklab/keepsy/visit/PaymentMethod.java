package com.tuktaklab.keepsy.visit;

/** 결제 수단 (FR-PAY-01, C9, CONTRACTS 3장). PG 연동 없이 기록만 한다 */
public enum PaymentMethod {
    CARD,
    CASH,
    TRANSFER,
    ETC
}
