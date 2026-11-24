package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.CampaignResultQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.security.ShopSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 전환 퍼널 · 연결 매출 · 대조군 비교 (CONTRACTS 6장 「성과 · 대시보드 · 검색」, FR-ANL-01 · 02 · 09). 퍼널 · 대조군 비교는
 * {@code CAMPAIGN_VIEW}, 매출은 {@code SALES_ANALYTICS} (CONTRACTS 4장)
 */
@RestController
@RequestMapping("/api/campaigns/{campaignId}")
@RequiredArgsConstructor
class CampaignResultController {

    private final CampaignResultQuery query;
    private final ShopSession shopSession;

    record RevenueResponse(long revenue) {}

    /** 전환율은 화면이 앞 단계 수로 나눠 계산한다 (CONTRACTS 6장) */
    @GetMapping("/funnel")
    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    CampaignResultQuery.Funnel funnel(@PathVariable long campaignId) {
        return ShopScope.orNotFound(query.funnel(shopSession.shopId(), campaignId));
    }

    /** 재예약률은 화면이 나눠 계산하고, 대조군 고객이 0명이면 비교 대상이 아니다 (결정 F36) */
    @GetMapping("/control-comparison")
    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    CampaignResultQuery.ControlComparison controlComparison(@PathVariable long campaignId) {
        return ShopScope.orNotFound(query.controlComparison(shopSession.shopId(), campaignId));
    }

    @GetMapping("/revenue")
    @RequireFeature(FeatureKey.SALES_ANALYTICS)
    RevenueResponse revenue(@PathVariable long campaignId) {
        return new RevenueResponse(
                ShopScope.orNotFound(query.revenue(shopSession.shopId(), campaignId)));
    }
}
