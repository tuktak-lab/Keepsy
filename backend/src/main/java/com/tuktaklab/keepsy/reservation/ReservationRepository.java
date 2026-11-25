package com.tuktaklab.keepsy.reservation;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 담당 스탭의 [from, to) 사이에 시작하는 예약. 상태 무관 (취소 · 노쇼는 {@link AvailableTimes}가 거른다) */
    @Query(
            "select r from Reservation r where r.shopId = :shopId and r.staffId = :staffId"
                    + " and r.startAt >= :from and r.startAt < :to")
    List<Reservation> findStaffReservations(
            long shopId, long staffId, LocalDateTime from, LocalDateTime to);

    Optional<Reservation> findByIdAndShopId(long id, long shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findForUpdateByIdAndShopId(long id, long shopId);

    List<Reservation> findByShopIdAndVisitId(long shopId, long visitId);

    /** 고객의 확정 · 완료 예약 중 {@code from} 이후 생성된 것, 예정 시술과 함께 (캠페인 귀속 · 승계, FR-ANL-03) */
    @Query(
            "select distinct r from Reservation r join fetch r.services where r.shopId = :shopId"
                    + " and r.customerId = :customerId and r.createdAt >= :from"
                    + " and r.status in (com.tuktaklab.keepsy.reservation.ReservationStatus.CONFIRMED,"
                    + " com.tuktaklab.keepsy.reservation.ReservationStatus.COMPLETED)")
    List<Reservation> findActiveWithServices(long shopId, long customerId, LocalDateTime from);

    @Query(
            "select count(r) from Reservation r where r.shopId = :shopId and r.customerId = :customerId"
                    + " and r.status = com.tuktaklab.keepsy.reservation.ReservationStatus.CONFIRMED")
    int countConfirmed(long shopId, long customerId);

    /** 확정 예약을 모두 취소한다. 상태 전이라 확정일 때만 바꾸는 조건부 UPDATE (F7) */
    @Modifying
    @Query(
            "update Reservation r set r.status = com.tuktaklab.keepsy.reservation.ReservationStatus.CANCELED"
                    + " where r.shopId = :shopId and r.customerId = :customerId"
                    + " and r.status = com.tuktaklab.keepsy.reservation.ReservationStatus.CONFIRMED")
    int cancelConfirmed(long shopId, long customerId);

    @Modifying
    @Query(
            "update Reservation r set r.memo = null"
                    + " where r.shopId = :shopId and r.customerId = :customerId")
    int clearMemos(long shopId, long customerId);
}
