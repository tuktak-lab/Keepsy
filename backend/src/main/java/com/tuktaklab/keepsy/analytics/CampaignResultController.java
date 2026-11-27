package com.tuktaklab.keepsy.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tuktaklab.keepsy.analytics.query.CampaignResultQuery;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.util.List;
import java.util.Map;
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

    /** {@code customers}는 {@code customerIds}를 보냈을 때만 (CONTRACTS 6장) */
    record RevenueResponse(
            long revenue,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<CustomerRevenue> customers) {}

    record CustomerRevenue(long customerId, long revenue) {}

    /** 캠페인 상세 한 페이지의 고객 수와 같다 */
    private static final int MAX_CUSTOMERS = 100;

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
            @PathVariable long campaignId, @RequestParam(required = false) String customerIds) {
        long shopId = shopSession.shopId();
        List<Long> ids = customerIds == null ? null : parseIds(customerIds);
        long total = ShopScope.orNotFound(query.revenue(shopId, campaignId));
        if (ids == null) {
            return new RevenueResponse(total, null);
        }
        // 고객별 연결 매출 (FR-ANL-04). 보낸 순서대로, 귀속 방문이 없으면 0
        Map<Long, Long> byCustomer = query.customerRevenues(shopId, campaignId, ids);
        return new RevenueResponse(
                total,
                ids.stream()
                        .map(id -> new CustomerRevenue(id, byCustomer.getOrDefault(id, 0L)))
                        .toList());
    }

    /** 쉼표로 구분한 양의 정수 ID, 중복은 한 번만, 1~100개 */
    private static List<Long> parseIds(String raw) {
        try {
            List<Long> ids =
                    java.util.Arrays.stream(raw.split(","))
                            .map(String::strip)
                            .map(Long::parseLong)
                            .distinct()
                            .toList();
            if (ids.isEmpty()
                    || ids.size() > MAX_CUSTOMERS
                    || ids.stream().anyMatch(id -> id < 1)) {
                throw new NumberFormatException();
            }
            return ids;
        } catch (NumberFormatException e) {
            throw new ApiException(
                    ErrorCode.INVALID, "고객 ID는 쉼표로 구분한 숫자 1~" + MAX_CUSTOMERS + "개입니다");
        }
    }
}
