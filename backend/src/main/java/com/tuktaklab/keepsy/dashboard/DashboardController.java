package com.tuktaklab.keepsy.dashboard;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.dashboard.query.DashboardTodayQuery;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 대시보드 (CONTRACTS 4장 「성과 · 대시보드 · 검색」). 권한은 {@code DASHBOARD_SEARCH}. */
@RestController
@RequestMapping("/api/dashboard")
@RequireFeature(FeatureKey.DASHBOARD_SEARCH)
@RequiredArgsConstructor
class DashboardController {

    private final DashboardTodayQuery todayQuery;
    private final ShopSession shopSession;
    private final Clock clock;

    /** 오늘 예약 · 마감 안 된 예약 · 이번 주 요약 (FR-DSH-01 · 03 · 05) */
    @GetMapping("/today")
    DashboardTodayQuery.Today today() {
        return todayQuery.find(shopSession.shopId(), LocalDateTime.now(clock));
    }
}
