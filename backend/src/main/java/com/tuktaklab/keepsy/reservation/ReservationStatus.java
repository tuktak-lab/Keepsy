package com.tuktaklab.keepsy.reservation;

/** 예약 상태 (FR-RSV-03, CONTRACTS 3장 「예약 상태」). */
public enum ReservationStatus {
    CONFIRMED,
    COMPLETED,
    CANCELED,
    NO_SHOW;

    /** 확정 · 완료 예약만 시간을 차지한다. 취소 · 노쇼는 그 시간을 다시 예약할 수 있다 (FR-RSV-04, E3). */
    public boolean occupiesTime() {
        return this == CONFIRMED || this == COMPLETED;
    }
}
