package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.account.Shop.CategoryCycle;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** 카테고리 기본 주기 규칙 (FR-SET-02, C16). DB 없이 돈다. */
class ShopCategoryCycleTest {

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");

    @Test
    void 가입한_매장은_C16_기본값을_카테고리_순서대로_가진다() {
        assertThat(shop.categoryCycles())
                .containsExactly(
                        new CategoryCycle("NAIL", 21, 28),
                        new CategoryCycle("LASH", 30, 60),
                        new CategoryCycle("WAXING", 28, 42),
                        new CategoryCycle("SEMI_PERMANENT", 365, 730),
                        new CategoryCycle("ETC", 28, 56));
    }

    @Test
    void 값이_바뀔_때만_true를_돌려준다() {
        assertThat(shop.changeCategoryCycle("LASH", 30, 60)).isFalse();
        assertThat(shop.changeCategoryCycle("LASH", 35, 70)).isTrue();
        assertThat(shop.categoryCycles().get(1)).isEqualTo(new CategoryCycle("LASH", 35, 70));
    }

    @Test
    void 최소가_1일보다_작거나_최대보다_크거나_모르는_카테고리면_invalid() {
        for (Runnable bad :
                new Runnable[] {
                    () -> shop.changeCategoryCycle("NAIL", 0, 10),
                    () -> shop.changeCategoryCycle("NAIL", 30, 20),
                    () -> shop.changeCategoryCycle("HAIR", 10, 20)
                }) {
            assertThatThrownBy(bad::run)
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID);
        }
        assertThat(shop.categoryCycles().get(0)).isEqualTo(new CategoryCycle("NAIL", 21, 28));
    }
}
