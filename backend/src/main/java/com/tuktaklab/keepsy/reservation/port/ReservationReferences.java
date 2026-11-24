package com.tuktaklab.keepsy.reservation.port;

import com.tuktaklab.keepsy.reservation.AvailableTimes.OpeningHours;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 예약이 다른 도메인(매장 설정 · 스탭 · 시술 메뉴 · 고객)에서 필요한 값 (DEVELOPMENT 4장 「도메인 연동」). 모두 세션 매장 조건으로 찾는다
 * (NFR-03).
 */
public interface ReservationReferences {

    /** 그날 요일의 영업시간. 정기 휴무 요일이면 비어 있다 (FR-SET-01) */
    Optional<OpeningHours> openingHours(long shopId, LocalDate date);

    /** 매장 임시 휴무, 스탭 임시 휴무, 스탭 정기 휴무 요일 중 하나라도 해당하는 날 (FR-SET-07) */
    boolean isDayOff(long shopId, long staffId, LocalDate date);

    /** 스탭 상태. 그 매장 스탭이 아니면 비어 있다 */
    Optional<StaffState> staff(long shopId, long staffId);

    /**
     * {@link #staff}와 같지만 그 스탭 행을 트랜잭션 끝까지 잠근다. 같은 스탭의 예약 저장을 한 줄로 세워, 동시에 같은 시간을 잡으면 하나만 저장된다
     * (FR-RSV-04, C7)
     */
    Optional<StaffState> lockStaff(long shopId, long staffId);

    /** 그 매장 메뉴의 소요 시간(분). 다른 매장 · 없는 메뉴는 결과에서 빠진다 */
    Map<Long, Integer> menuDurations(long shopId, Collection<Long> serviceMenuIds);

    /** 정상 고객의 예약 제한 상태. 다른 매장 · 없는 · 삭제된 고객이면 비어 있다 */
    Optional<CustomerState> customer(long shopId, long customerId);

    record StaffState(boolean active) {}

    record CustomerState(boolean restricted, String restrictReason) {}
}
