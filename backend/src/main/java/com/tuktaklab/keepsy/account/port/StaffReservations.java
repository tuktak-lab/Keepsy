package com.tuktaklab.keepsy.account.port;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 스탭 정기 휴무를 바꿀 때 걸리는 예약 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」) */
public interface StaffReservations {

    /**
     * @param customerName 고객 없는 예약이면 null
     */
    record Booked(long id, LocalDateTime startAt, String customerName) {}

    /** 이 스탭의 확정 예약 중 {@code from} 이후이고 요일이 {@code days}에 드는 것, 시작 시각순 (F12) */
    List<Booked> confirmedOn(
            long shopId, long staffId, Collection<DayOfWeek> days, LocalDateTime from);
}
