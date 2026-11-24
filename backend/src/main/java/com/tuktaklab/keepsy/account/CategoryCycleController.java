package com.tuktaklab.keepsy.account;

import com.tuktaklab.keepsy.account.Shop.CategoryCycle;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.RequireFeature;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 기본 주기 (CONTRACTS 4 · 6장 SET [제안]). shop 표라 account가 맡는다 (F21). 원장 PIN */
@RestController
@RequiredArgsConstructor
@RequireFeature(FeatureKey.OWNER_PIN)
class CategoryCycleController {

    private final ShopSettingsService settings;
    private final ShopSession shopSession;

    record CycleItem(String category, Integer minDays, Integer maxDays) {}

    /** 카테고리 기본 주기 5개 (FR-SET-02, C16) */
    @GetMapping("/api/category-cycles")
    List<CycleItem> cycles() {
        return settings.categoryCycles(shopSession.shopId()).stream()
                .map(c -> new CycleItem(c.category(), c.minDays(), c.maxDays()))
                .toList();
    }

    /** 5개를 통째로 바꾼다. 바뀐 카테고리는 예측을 다시 계산한다 (FR-SET-02) */
    @PutMapping("/api/category-cycles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@RequestBody List<CycleItem> body) {
        List<CategoryCycle> cycles =
                body.stream()
                        .map(c -> {
                            if (c == null || c.minDays() == null || c.maxDays() == null) {
                                throw new ApiException(ErrorCode.INVALID, "최소 · 최대 주기를 입력해 주세요");
                            }
                            return new CategoryCycle(c.category(), c.minDays(), c.maxDays());
                        })
                        .toList();
        settings.updateCategoryCycles(shopSession.shopId(), cycles);
    }
}
