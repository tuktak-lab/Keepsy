package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 예측 API (CONTRACTS 4 · 6장 [제안]). 고객 상세 화면의 예측 영역도 "인사이트" 설정을 따른다 (A3 보완) */
@RestController
@RequiredArgsConstructor
class PredictionController {

    private final CustomerPredictionService customerPredictionService;
    private final InsightService insightService;
    private final ShopSession shopSession;

    record CustomerPredictionsResponse(List<CustomerPredictionService.Item> predictions) {}

    @RequireFeature(FeatureKey.INSIGHT)
    @GetMapping("/api/customers/{customerId}/predictions")
    CustomerPredictionsResponse forCustomer(@PathVariable long customerId) {
        return new CustomerPredictionsResponse(
                customerPredictionService.forCustomer(shopSession.shopId(), customerId));
    }

    @RequireFeature(FeatureKey.INSIGHT)
    @GetMapping("/api/insights")
    PageResponse<InsightService.Item> insights(
            @RequestParam(required = false) PredictionStatus status,
            @RequestParam(required = false)
                    @Pattern(
                            regexp = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC",
                            message = "시술 카테고리 값이 아닙니다")
                    String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return insightService.list(shopSession.shopId(), status, category, page, size);
    }

    /** null이거나 빠지면 표시를 지운다 (CONTRACTS 6장, F55) */
    record HandlingRequest(HandlingMark mark) {}

    /** 처리 표시 (CONTRACTS 6장, FR-PRD-07) */
    @RequireFeature(FeatureKey.INSIGHT)
    @PutMapping("/api/customers/{customerId}/predictions/{category}/handling")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void mark(
            @PathVariable long customerId,
            @PathVariable
                    @Pattern(
                            regexp = "NAIL|LASH|WAXING|SEMI_PERMANENT|ETC",
                            message = "시술 카테고리 값이 아닙니다")
                    String category,
            @RequestBody HandlingRequest r) {
        customerPredictionService.mark(shopSession.shopId(), customerId, category, r.mark());
    }
}
