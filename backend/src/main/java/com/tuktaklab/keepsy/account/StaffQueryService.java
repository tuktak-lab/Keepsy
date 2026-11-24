package com.tuktaklab.keepsy.account;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 스탭 목록 (FR-ACC-04, FR-RSV-01). 매장 단위 작은 목록이라 페이징하지 않는다 (F22) */
@Service
@RequiredArgsConstructor
public class StaffQueryService {

    private final StaffRepository staffRepository;

    /** 활성 스탭만 (담당 스탭 선택용) */
    @Transactional(readOnly = true)
    public List<Staff> activeStaff(long shopId) {
        return staffRepository.findByShopIdAndStatusOrderByOwnerDescIdAsc(
                shopId, StaffStatus.ACTIVE);
    }

    /** 비활성 포함 (스탭 관리 화면용) */
    @Transactional(readOnly = true)
    public List<Staff> allStaff(long shopId) {
        return staffRepository.findByShopIdOrderByStatusAscOwnerDescIdAsc(shopId);
    }
}
