package com.tuktaklab.keepsy.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface StaffRepository extends JpaRepository<Staff, Long> {

    /** 매장 격리: 다른 매장 스탭은 없는 것과 같다 (NFR-03) */
    Optional<Staff> findByIdAndShopId(Long id, Long shopId);

    List<Staff> findByShopIdAndStatus(Long shopId, StaffStatus status);

    /** 담당 스탭 선택용: 원장 먼저, 그다음 등록 순 (CONTRACTS 6장 GET /api/staff) */
    List<Staff> findByShopIdAndStatusOrderByOwnerDescIdAsc(Long shopId, StaffStatus status);

    /** 관리용: 활성(ACTIVE) 먼저, 그 안에서 원장 먼저, 그다음 등록 순. status는 문자열이라 ACTIVE < INACTIVE */
    List<Staff> findByShopIdOrderByStatusAscOwnerDescIdAsc(Long shopId);
}
