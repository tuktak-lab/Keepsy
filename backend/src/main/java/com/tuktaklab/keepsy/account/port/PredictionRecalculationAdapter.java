package com.tuktaklab.keepsy.account.port;

import com.tuktaklab.keepsy.prediction.PredictionAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** prediction 도메인이 공개한 {@link PredictionAppService}로 다시 계산한다 (#72) */
@Component
@RequiredArgsConstructor
class PredictionRecalculationAdapter implements PredictionRecalculation {

    private final PredictionAppService predictions;

    @Override
    public void categoryDefaultChanged(long shopId, String category) {
        predictions.recalculateForCategoryDefault(shopId, category);
    }
}
