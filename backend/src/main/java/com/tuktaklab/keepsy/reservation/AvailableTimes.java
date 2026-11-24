package com.tuktaklab.keepsy.reservation;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 예약 가능한 시작 시각과 겹침 검사 (FR-RSV-04, C7). 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이 단위 테스트한다 (DEVELOPMENT 4장). 조회
 * 화면(available-times)과 예약 등록 · 수정 저장 검사가 같은 규칙을 쓴다.
 *
 * <p>호출하는 쪽이 채우는 값: 그날(Asia/Seoul, NFR-12) 요일의 영업시간, 매장 · 담당 스탭 휴무 여부(FR-SET-07), 그 스탭의 그날 예약. 예약
 * 수정이면 수정하는 예약 자신은 {@code bookings}에서 뺀다.
 */
public final class AvailableTimes {

    /** 시작 시각 · 소요 시간 단위 (FR-RSV-01, C7) */
    public static final int SLOT_MINUTES = 30;

    private AvailableTimes() {}

    /** 그날 영업시간. 정기 휴무 요일이면 호출하는 쪽이 {@code Optional.empty()}를 넘긴다 (FR-SET-01). */
    public record OpeningHours(LocalTime open, LocalTime close) {
        public OpeningHours {
            if (!open.isBefore(close)) {
                throw new IllegalArgumentException("영업 시작은 종료보다 빨라야 한다: " + open + "~" + close);
            }
        }
    }

    /** 담당 스탭의 그날 기존 예약. 고객 없는 예약도 포함한다 (E3). */
    public record Booking(LocalTime start, LocalTime end, ReservationStatus status) {}

    /**
     * @param hours 그날 영업시간. 비어 있으면 정기 휴무
     * @param dayOff 매장 임시 휴무 또는 담당 스탭 정기 · 임시 휴무인 날
     * @param bookings 담당 스탭의 그날 예약 (상태 무관, 취소 · 노쇼는 여기서 거른다)
     * @param minutes 소요 시간 (30분 단위)
     * @return 시작부터 종료까지 전체가 영업시간 안이고 확정 · 완료 예약과 겹치지 않는 30분 단위 시작 시각, 이른 순
     */
    public static List<LocalTime> startTimes(
            Optional<OpeningHours> hours, boolean dayOff, List<Booking> bookings, int minutes) {
        if (minutes <= 0 || minutes % SLOT_MINUTES != 0) {
            throw new IllegalArgumentException("소요 시간은 30분 단위여야 한다: " + minutes);
        }
        List<LocalTime> result = new ArrayList<>();
        if (hours.isEmpty() || dayOff) {
            return result;
        }
        int open = toMinutes(hours.get().open());
        int close = toMinutes(hours.get().close());
        // 영업 시작이 30분 단위가 아니면 다음 30분 칸부터 연다
        int first = (open + SLOT_MINUTES - 1) / SLOT_MINUTES * SLOT_MINUTES;
        for (int start = first; start + minutes <= close; start += SLOT_MINUTES) {
            if (!overlaps(start, start + minutes, bookings)) {
                result.add(LocalTime.MIN.plusMinutes(start));
            }
        }
        return result;
    }

    /** 저장 직전 서버 재검사. 조회 화면과 같은 규칙이라 그 선택지 안에 있어야 한다. */
    public static boolean isAvailable(
            Optional<OpeningHours> hours,
            boolean dayOff,
            List<Booking> bookings,
            LocalTime start,
            int minutes) {
        return startTimes(hours, dayOff, bookings, minutes).contains(start);
    }

    /** 반열린 구간 [start, end)끼리 비교한다. 끝나는 시각에 바로 시작하는 예약은 겹치지 않는다. */
    private static boolean overlaps(int start, int end, List<Booking> bookings) {
        for (Booking b : bookings) {
            if (b.status().occupiesTime()
                    && start < toMinutes(b.end())
                    && toMinutes(b.start()) < end) {
                return true;
            }
        }
        return false;
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
