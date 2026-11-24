package com.tuktaklab.keepsy.prediction;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 예측 API (CONTRACTS 4 · 6장 [제안]). 고객 상세 화면의 예측 영역도 "인사이트" 설정을 따른다 (A3 보완) */
@RestController
@RequiredArgsConstructor
class PredictionController {

    private final CustomerPredictionService customerPredictionService;
    private final ShopSession shopSession;

    record CustomerPredictionsResponse(List<CustomerPredictionService.Item> predictions) {}

    @RequireFeature(FeatureKey.INSIGHT)
    @GetMapping("/api/customers/{customerId}/predictions")
    CustomerPredictionsResponse forCustomer(@PathVariable long customerId) {
        return new CustomerPredictionsResponse(
                customerPredictionService.forCustomer(shopSession.shopId(), customerId));
    }
}
