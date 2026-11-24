package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 기능별 공용/PIN 변경 규칙 (Keepsy #24, FR-ACC-05, F38). DB 없이 돈다. */
class ShopFeatureLockTest {

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");

    private void assertInvalid(Map<String, String> changes) {
        assertThatThrownBy(() -> shop.changeFeatureLocks(changes))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
    }

    @Test
    void 가입한_매장은_명세서_2장_기본값_PIN_6개() {
        assertThat(shop.lockedFeatures()).isEqualTo(Shop.DEFAULT_PIN_LOCKED);
    }

    @Test
    void 보낸_키만_바꾸고_고정_키는_같은_값이면_통과한다() {
        shop.changeFeatureLocks(
                Map.of(
                        "CUSTOMER_VIEW", "PIN",
                        "CONTACT_FULL", "PUBLIC",
                        "CONSENT", "PUBLIC",
                        "OWNER_PIN", "OWNER_PIN"));

        assertThat(shop.lockedFeatures())
                .contains(FeatureKey.CUSTOMER_VIEW, FeatureKey.CUSTOMER_EDIT)
                .doesNotContain(FeatureKey.CONTACT_FULL, FeatureKey.CONSENT)
                .hasSize(6);
    }

    @Test
    void 고정_키를_다른_값으로_모르는_키_값_빈_값이면_invalid이고_아무것도_바꾸지_않는다() {
        Map<String, String> partlyWrong = new HashMap<>();
        partlyWrong.put("CUSTOMER_VIEW", "PIN");
        partlyWrong.put("CONSENT", "PIN");
        assertInvalid(partlyWrong);
        assertInvalid(Map.of("DASHBOARD_SEARCH", "PIN"));
        assertInvalid(Map.of("OWNER_PIN", "PUBLIC"));
        assertInvalid(Map.of("CUSTOMER_VIEW", "OWNER_PIN"));
        assertInvalid(Map.of("NO_SUCH_KEY", "PIN"));
        assertInvalid(Map.of("CUSTOMER_VIEW", "LOCKED"));
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("CUSTOMER_VIEW", null);
        assertInvalid(nullValue);
        assertInvalid(Map.of());

        assertThat(shop.lockedFeatures()).isEqualTo(Shop.DEFAULT_PIN_LOCKED);
    }
}
