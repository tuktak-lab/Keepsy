package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.CampaignResultQuery;
import com.tuktaklab.keepsy.analytics.query.PredictionAccuracyQuery;
import com.tuktaklab.keepsy.analytics.query.ShopSummaryQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매장 기본 지표 · 예측 정확도 · 캠페인 성과 (CONTRACTS 6장 「성과 · 대시보드 · 검색」, FR-ANL-01 · 02 · 05 · 06 · 07). 권한은
 * {@code SALES_ANALYTICS}
 */
@RestController
@RequestMapping("/api/analytics")
@RequireFeature(FeatureKey.SALES_ANALYTICS)
@RequiredArgsConstructor
class AnalyticsController {

    private final ShopSummaryQuery summaryQuery;
    private final PredictionAccuracyQuery accuracyQuery;
    private final CampaignResultQuery campaignResultQuery;
    private final ShopSession shopSession;
    private final Clock clock;

    record SummaryResponse(
            LocalDate from,
            LocalDate to,
            long revenue,
            long visitCount,
            long newCustomerCount,
            List<ShopSummaryQuery.CycleRevisit> cycleRevisits) {}

    record AccuracyResponse(
            LocalDate from,
            LocalDate to,
            long evaluatedCount,
            long hitCount,
            BigDecimal averageErrorDays) {}

    /** 3절 페이지 응답에 계산 기간과 기간 전체 합계를 더한다 (CONTRACTS 6장, F65) */
    record CampaignResultsResponse(
            List<CampaignResultQuery.CampaignResult> items,
            int page,
            int size,
            long totalElements,
            LocalDate from,
            LocalDate to,
            CampaignResultQuery.CampaignTotal total) {}

    /** 주기 내 재방문은 결과가 정해진(이탈 신호 기간이 끝난) 예측만 센다 (F58) */
    @GetMapping("/summary")
    SummaryResponse summary(
            @RequestParam SummaryPeriod period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        SummaryPeriod.Range range = period.range(today, from, to);
        long shopId = shopSession.shopId();
        ShopSummaryQuery.Summary s = summaryQuery.find(shopId, range.from(), range.to());
        return new SummaryResponse(
                range.from(),
                range.to(),
                s.revenue(),
                s.visitCount(),
                s.newCustomerCount(),
                summaryQuery.cycleRevisits(shopId, range.from(), range.to(), today));
    }

    /** 성과 분석 화면의 캠페인 성과를 한 번에 (FR-ANL-01 · 02, 결정 F65). 기간 값은 매장 기본 지표와 같다 */
    @GetMapping("/campaigns")
    CampaignResultsResponse campaigns(
            @RequestParam SummaryPeriod period,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        SummaryPeriod.Range range = period.range(LocalDate.now(clock), from, to);
        CampaignResultQuery.CampaignResults r =
                campaignResultQuery.campaignResults(
                        shopSession.shopId(), range.from(), range.to(), page, size);
        return new CampaignResultsResponse(
                r.items(), page, size, r.totalElements(), range.from(), range.to(), r.total());
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
