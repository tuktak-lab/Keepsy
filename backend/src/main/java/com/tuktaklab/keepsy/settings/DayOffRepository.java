package com.tuktaklab.keepsy.settings;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DayOffRepository extends JpaRepository<DayOff, Long> {

    /** 기간 안 휴무일, 날짜순. 같은 날은 매장 휴무(staff_id NULL) 먼저: MySQL 오름차순은 NULL이 앞 (idx_day_off_shop_date) */
    List<DayOff> findByShopIdAndOffDateBetweenOrderByOffDateAscStaffIdAscIdAsc(
            Long shopId, LocalDate from, LocalDate to);

    /** 그날 매장 휴무가 있는지 (F32) */
    boolean existsByShopIdAndOffDateAndStaffIdIsNull(Long shopId, LocalDate offDate);

    /** 그날 그 스탭 휴무가 있는지 (F32) */
    boolean existsByShopIdAndOffDateAndStaffId(Long shopId, LocalDate offDate, Long staffId);

    Optional<DayOff> findByIdAndShopId(Long id, Long shopId);
}
