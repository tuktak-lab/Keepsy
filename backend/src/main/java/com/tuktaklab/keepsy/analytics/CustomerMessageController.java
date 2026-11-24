package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.analytics.query.CustomerMessageQuery;
import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopScope;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 수신 이력 · 캠페인 연결 기록 (FR-CUS-03, FR-ANL-04, CONTRACTS 6장 「고객 (CUS)」). 경로는 고객 아래지만 캠페인 → 예약 → 방문을
 * 잇는 성과 조회라 analytics 패키지에 둔다 (결제 금액 이력이 visit에 있는 것과 같은 이유, ARCHITECTURE 4장). 공용인 고객 상세 안에서도 이 영역은
 * "캠페인 목록·결과 조회" 설정을 따른다 (명세서 2장)
 */
@RestController
@RequiredArgsConstructor
class CustomerMessageController {

    private final CustomerMessageQuery query;
    private final ShopSession shopSession;
    private final AccessLogger accessLogger;

    @RequireFeature(FeatureKey.CAMPAIGN_VIEW)
    @GetMapping("/api/customers/{customerId}/messages")
    PageResponse<CustomerMessageQuery.Item> list(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<CustomerMessageQuery.Item> messages =
                ShopScope.orNotFound(query.messages(shopSession.shopId(), customerId, page, size));
        accessLogger.record(
                FeatureKey.CAMPAIGN_VIEW, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return messages;
    }
}
