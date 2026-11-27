package com.tuktaklab.keepsy.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tuktaklab.keepsy.analytics.query.CampaignResultQuery;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** {@code customers}는 {@code customerIds}를 보냈을 때만 있다 (CONTRACTS 6장, FR-ANL-04) */
    record RevenueResponse(
            long revenue,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<CampaignResultQuery.CustomerRevenue> customers) {}

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
    RevenueResponse revenue(
            @PathVariable long campaignId,
            @RequestParam(required = false) @Size(max = 100) List<Long> customerIds) {
        long shopId = shopSession.shopId();
        long revenue = ShopScope.orNotFound(query.revenue(shopId, campaignId));
        return new RevenueResponse(
                revenue,
                customerIds == null
                        ? null
                        : ShopScope.orNotFound(
                                query.customerRevenue(shopId, campaignId, customerIds)));
    }
}
