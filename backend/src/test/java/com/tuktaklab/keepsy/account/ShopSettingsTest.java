package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.account.Shop.Settings;
import org.junit.jupiter.api.Test;

/** 매장 설정값 (Keepsy #33, FR-SET-03 · 04 · 06). DB 없이 돈다. */
class ShopSettingsTest {

    @Test
    void 가입한_매장은_V1과_같은_기본값이다() {
        // 칸을 매핑하면 가입 INSERT에 들어가 V1 DB 기본값이 쓰이지 않으므로 create가 같은 값을 넣어야 한다
        assertThat(Shop.create("owner@example.com", "hash", "테스트 네일").settings())
                .isEqualTo(new Settings(120, 14, 500, 7, 0, 3));
    }

    @Test
    void 통째로_바꾼다() {
        Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");
        shop.changeSettings(new Settings(90, 7, 1000, 0, 30, 5));

        assertThat(shop.settings()).isEqualTo(new Settings(90, 7, 1000, 0, 30, 5));
        assertThat(shop.getRetentionYears()).isEqualTo(5);
    }
}
