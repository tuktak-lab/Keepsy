package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 기능별 공용/PIN 설정 (CONTRACTS 4 · 6장 ACC [제안]). shop 표라 account가 맡는다 (F21). 지금 값은 GET /api/session */
@RestController
@RequiredArgsConstructor
class FeatureLockController {

    private final ShopSettingsService settings;
    private final ShopSession shopSession;

    /** 키 · 값 검사는 Shop.changeFeatureLocks가 칸별 사유와 함께 한다 */
    record LocksRequest(@NotNull(message = "바꿀 기능을 보내 주세요") Map<String, String> features) {}

    /** 보낸 키만 공용/PIN을 바꾼다 (FR-ACC-05, F34). 원장 PIN */
    @RequireFeature(FeatureKey.OWNER_PIN)
    @PutMapping("/api/feature-locks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@Valid @RequestBody LocksRequest req) {
        settings.updateFeatureLocks(shopSession.shopId(), req.features());
    }
}
