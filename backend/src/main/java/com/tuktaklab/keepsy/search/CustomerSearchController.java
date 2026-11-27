package com.tuktaklab.keepsy.search;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.search.query.CustomerSearchQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상단 검색창의 고객 결과와 결과 없는 검색어 (CONTRACTS 4 · 6장 「성과 · 대시보드 · 검색」 [제안]). 기능 검색은 {@code
 * DASHBOARD_SEARCH}지만 고객 결과는 "고객 조회" 설정을 따른다 (명세서 2장, FR-SCH-05)
 */
@RestController
@RequiredArgsConstructor
class CustomerSearchController {

    private final CustomerSearchService service;
    private final ShopSession shopSession;
    private final AccessLogger accessLogger;

    /** 길이는 search_miss_log.keyword VARCHAR(100)에 맞춘다 (NFR-08) */
    record MissRequest(@NotBlank @Size(max = 100) String keyword) {}

    @RequireFeature(FeatureKey.CUSTOMER_VIEW)
    @GetMapping("/api/search/customers")
    PageResponse<CustomerSearchQuery.Item> customers(
            @RequestParam(required = false) @NotBlank(message = "검색어를 입력해 주세요") String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageResponse<CustomerSearchQuery.Item> result =
                service.customers(shopSession.shopId(), q, page, size);
        // 고객 목록과 같은 기준: 돌려준 페이지의 고객만 조회로 남기고 검색어는 남기지 않는다 (NFR-06, CONTRACTS 1장).
        // 화면은 이 API를 부르지 않지만(F74) 호출할 수 있는 API라 기록한다
        accessLogger.recordAll(
                FeatureKey.CUSTOMER_VIEW,
                AccessLogger.CUSTOMER,
                result.items().stream().map(CustomerSearchQuery.Item::customerId).toList(),
                Action.VIEW);
        return result;
    }

    /** 결과 없는 검색어 저장 (FR-SCH-06). 저장했든 안 했든 204 (F28) */
    @RequireFeature(FeatureKey.DASHBOARD_SEARCH)
    @PostMapping("/api/search/misses")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void miss(@Valid @RequestBody MissRequest req) {
        service.recordMiss(shopSession.shopId(), req.keyword());
    }
}
