package com.tuktaklab.keepsy.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StaffRepository extends JpaRepository<Staff, Long> {

    /** 매장 격리: 다른 매장 스탭은 없는 것과 같다 (NFR-03) */
    Optional<Staff> findByIdAndShopId(Long id, Long shopId);

    /** 비활성화 중에 그 스탭 예약이 새로 들어오지 못하게 행을 잠근다. 예약 등록도 같은 행을 잠그고 활성 여부를 본다 (F33) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Staff s WHERE s.id = :id AND s.shopId = :shopId")
    Optional<Staff> findForUpdate(@Param("id") long id, @Param("shopId") long shopId);

    List<Staff> findByShopIdAndStatus(Long shopId, StaffStatus status);

    /** 담당 스탭 선택용: 원장 먼저, 그다음 등록 순 (CONTRACTS 6장 GET /api/staff) */
    List<Staff> findByShopIdAndStatusOrderByOwnerDescIdAsc(Long shopId, StaffStatus status);

    /** 관리용: 활성(ACTIVE) 먼저, 그 안에서 원장 먼저, 그다음 등록 순. status는 문자열이라 ACTIVE < INACTIVE */
    List<Staff> findByShopIdOrderByStatusAscOwnerDescIdAsc(Long shopId);
}
