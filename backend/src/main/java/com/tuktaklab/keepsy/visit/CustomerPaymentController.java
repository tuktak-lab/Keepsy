package com.tuktaklab.keepsy.visit;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
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
 * 고객 결제 금액 이력 (FR-ANL-04, CONTRACTS 6장 「고객 (CUS)」). 경로는 고객 아래지만 결제 데이터라 visit 패키지에 둔다 (ARCHITECTURE
 * 4장). 공용인 고객 상세 안에서도 이 영역은 "매출·성과 분석 조회" 설정을 따른다 (명세서 2장)
 */
@RestController
@RequiredArgsConstructor
class CustomerPaymentController {

    private final VisitAppService service;
    private final ShopSession shopSession;
    private final AccessLogger accessLogger;

    @RequireFeature(FeatureKey.SALES_ANALYTICS)
    @GetMapping("/api/customers/{customerId}/payments")
    PageResponse<VisitAppService.PaymentItem> list(
            @PathVariable long customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<VisitAppService.PaymentItem> payments =
                service.customerPayments(shopSession.shopId(), customerId, page, size);
        // 그 고객의 개인정보 조회 (NFR-06, CONTRACTS 6장). 다른 매장 · 삭제된 고객은 위에서 404라 남지 않는다
        accessLogger.record(
                FeatureKey.SALES_ANALYTICS, AccessLogger.CUSTOMER, customerId, Action.VIEW);
        return payments;
    }
}
