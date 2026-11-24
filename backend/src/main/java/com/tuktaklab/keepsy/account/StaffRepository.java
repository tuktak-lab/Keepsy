package com.tuktaklab.keepsy.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface StaffRepository extends JpaRepository<Staff, Long> {

    /** 매장 격리: 다른 매장 스탭은 없는 것과 같다 (NFR-03) */
    Optional<Staff> findByIdAndShopId(Long id, Long shopId);

    List<Staff> findByShopIdAndStatus(Long shopId, StaffStatus status);
}
