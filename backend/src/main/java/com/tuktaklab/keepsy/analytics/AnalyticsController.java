package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.ShopSummaryQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 매장 기본 지표 (CONTRACTS 6장 「성과 · 대시보드 · 검색」, FR-ANL-05 · 06). 권한은 {@code SALES_ANALYTICS} */
@RestController
@RequestMapping("/api/analytics")
@RequireFeature(FeatureKey.SALES_ANALYTICS)
@RequiredArgsConstructor
class AnalyticsController {

    private final ShopSummaryQuery summaryQuery;
    private final ShopSession shopSession;
    private final Clock clock;

    record SummaryResponse(
            LocalDate from, LocalDate to, long revenue, long visitCount, long newCustomerCount) {}

    /** 주기 내 재방문율은 스냅샷 스키마 결정 뒤에 더한다 (F24) */
    @GetMapping("/summary")
    SummaryResponse summary(
            @RequestParam SummaryPeriod period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        SummaryPeriod.Range range = period.range(LocalDate.now(clock), from, to);
        ShopSummaryQuery.Summary s =
                summaryQuery.find(shopSession.shopId(), range.from(), range.to());
        return new SummaryResponse(
                range.from(), range.to(), s.revenue(), s.visitCount(), s.newCustomerCount());
    }
}
