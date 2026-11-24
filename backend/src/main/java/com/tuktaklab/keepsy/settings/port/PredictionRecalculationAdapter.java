package com.tuktaklab.keepsy.settings.port;

import com.tuktaklab.keepsy.prediction.PredictionAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** prediction 도메인이 공개한 {@link PredictionAppService}로 그 메뉴를 받은 고객 전체를 다시 계산한다 (#72) */
@Component("settingsPredictionRecalculationAdapter")
@RequiredArgsConstructor
class PredictionRecalculationAdapter implements PredictionRecalculation {

    private final PredictionAppService predictions;

    @Override
    public void menuChanged(long shopId, long menuId) {
        predictions.recalculateForMenu(shopId, menuId);
    }
}
