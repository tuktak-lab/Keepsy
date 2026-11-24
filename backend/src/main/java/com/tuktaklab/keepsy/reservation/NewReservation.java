package com.tuktaklab.keepsy.reservation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약 등록 입력 (FR-RSV-01, CONTRACTS 4장 {@code POST /api/reservations}).
 *
 * @param customerId 고객 없는 예약이면 null (E3)
 * @param minutes 소요 시간. null이면 예정 시술 메뉴 소요 시간 합계
 * @param channel 예약 경로. null이면 매장
 */
public record NewReservation(
        Long customerId,
        long staffId,
        LocalDateTime startAt,
        Integer minutes,
        ReservationChannel channel,
        boolean consultFirst,
        String memo,
        List<Long> serviceMenuIds) {}
