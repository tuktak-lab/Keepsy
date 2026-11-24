package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.PredictionAccuracyQuery;
import com.tuktaklab.keepsy.analytics.query.ShopSummaryQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매장 기본 지표 · 예측 정확도 (CONTRACTS 6장 「성과 · 대시보드 · 검색」, FR-ANL-05 · 06 · 07). 권한은 {@code
 * SALES_ANALYTICS}
 */
@RestController
@RequestMapping("/api/analytics")
@RequireFeature(FeatureKey.SALES_ANALYTICS)
@RequiredArgsConstructor
class AnalyticsController {

    private final ShopSummaryQuery summaryQuery;
    private final PredictionAccuracyQuery accuracyQuery;
    private final ShopSession shopSession;
    private final Clock clock;

    record SummaryResponse(
            LocalDate from, LocalDate to, long revenue, long visitCount, long newCustomerCount) {}

    record AccuracyResponse(
            LocalDate from,
            LocalDate to,
            long evaluatedCount,
            long hitCount,
            BigDecimal averageErrorDays) {}

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

    /** 끝난 예측만 평가한다 (E9, F29). 기간 값은 매장 기본 지표와 같다 (FR-ANL-06) */
    @GetMapping("/prediction-accuracy")
    AccuracyResponse predictionAccuracy(
            @RequestParam SummaryPeriod period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        SummaryPeriod.Range range = period.range(today, from, to);
        PredictionAccuracyQuery.Accuracy a =
                accuracyQuery.find(shopSession.shopId(), range.from(), range.to(), today);
        return new AccuracyResponse(
                range.from(), range.to(), a.evaluatedCount(), a.hitCount(), a.averageErrorDays());
    }
}
