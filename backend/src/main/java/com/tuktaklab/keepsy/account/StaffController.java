package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.RequireWorker;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.time.DayOfWeek;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 스탭 목록 (CONTRACTS 4 · 6장 ACC [제안]). PIN 해시는 어느 응답에도 넣지 않는다 */
@RestController
@RequiredArgsConstructor
class StaffController {

    private final StaffQueryService queryService;
    private final ShopSession shopSession;

    record StaffItem(long id, String name) {}

    record StaffDetail(
            long id,
            String name,
            boolean owner,
            boolean lockedFeatureAllowed,
            List<DayOfWeek> regularDaysOff,
            StaffStatus status) {}

    /** 활성 스탭 목록, 담당 스탭 선택용 (FR-RSV-01, FR-ACC-04). 근무자 */
    @RequireWorker
    @GetMapping("/api/staff")
    List<StaffItem> activeStaff() {
        return queryService.activeStaff(shopSession.shopId()).stream()
                .map(s -> new StaffItem(s.getId(), s.getName()))
                .toList();
    }

    /** 스탭 관리 화면용, 비활성 포함 (FR-ACC-04, FR-SET-05). 원장 PIN */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @GetMapping(value = "/api/staff", params = "includeInactive=true")
    List<StaffDetail> allStaff() {
        return queryService.allStaff(shopSession.shopId()).stream()
                .map(s ->
                        new StaffDetail(
                                s.getId(),
                                s.getName(),
                                s.isOwner(),
                                s.isLockedFeatureAllowed(),
                                s.daysOff(),
                                s.getStatus()))
                .toList();
    }
}
