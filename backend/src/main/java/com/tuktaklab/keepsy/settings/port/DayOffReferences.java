package com.tuktaklab.keepsy.settings.port;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 휴무일 등록에 필요한 스탭 · 예약 값 (다른 도메인의 값, DEVELOPMENT 4장 「도메인 연동」) */
public interface DayOffReferences {

    /**
     * @return 이 매장 스탭이면 활성 여부, 없거나 다른 매장이면 empty
     */
    Optional<Boolean> staffActive(long shopId, long staffId);

    /**
     * @param customerName 고객 없는 예약이면 null
     */
    record Booked(long id, LocalDateTime startAt, String customerName) {}

    /**
     * 그날 {@code from} 이후 확정 예약, 시작 시각순 (FR-SET-07, F12)
     *
     * @param staffId null이면 매장 전체
     */
    List<Booked> confirmedOn(long shopId, Long staffId, LocalDate date, LocalDateTime from);
}
