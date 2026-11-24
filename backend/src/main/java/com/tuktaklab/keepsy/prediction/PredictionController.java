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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
}
