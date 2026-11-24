package com.tuktaklab.keepsy.search;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.search.query.CustomerSearchQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상단 검색창의 고객 결과 (CONTRACTS 4 · 6장 「성과 · 대시보드 · 검색」 [제안]). 기능 검색은 {@code DASHBOARD_SEARCH}지만 고객 결과는
 * "고객 조회" 설정을 따른다 (명세서 2장, FR-SCH-05)
 */
@RestController
@RequiredArgsConstructor
class CustomerSearchController {

    private final CustomerSearchService service;
    private final ShopSession shopSession;

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/search/customers")
    PageResponse<CustomerSearchQuery.Item> customers(
            @RequestParam(required = false) @NotBlank(message = "검색어를 입력해 주세요") String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.customers(shopSession.shopId(), q, page, size);
    }
}
