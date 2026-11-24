package com.tuktaklab.keepsy.reservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 담당 스탭의 [from, to) 사이에 시작하는 예약. 상태 무관 (취소 · 노쇼는 {@link AvailableTimes}가 거른다) */
    @Query(
            "select r from Reservation r where r.shopId = :shopId and r.staffId = :staffId"
                    + " and r.startAt >= :from and r.startAt < :to")
    List<Reservation> findStaffReservations(
            long shopId, long staffId, LocalDateTime from, LocalDateTime to);

    Optional<Reservation> findByIdAndShopId(long id, long shopId);
}
