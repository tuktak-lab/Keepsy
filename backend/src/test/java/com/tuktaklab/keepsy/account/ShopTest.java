package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 로그인 실패 잠금 규칙 (FR-ACC-02, C1). DB 없이 돈다. */
class ShopTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");

    @Test
    void 연속_5회_실패하면_15분_잠기고_횟수를_처음부터_센다() {
        for (int i = 0; i < 4; i++) {
            shop.loginFailed(NOW);
        }
        assertThat(shop.isLoginLocked(NOW)).isFalse();

        shop.loginFailed(NOW);

        assertThat(shop.isLoginLocked(NOW)).isTrue();
        assertThat(shop.isLoginLocked(NOW.plusMinutes(14).plusSeconds(59))).isTrue();
        assertThat(shop.isLoginLocked(NOW.plusMinutes(15))).isFalse();
        assertThat(shop.getLoginFailCount()).isZero();
    }

    @Test
    void PIN은_매장_단위로_5회_실패가_쌓이면_5분_막는다() {
        for (int i = 0; i < 4; i++) {
            shop.pinFailed(NOW);
        }
        assertThat(shop.isPinLocked(NOW)).isFalse();

        shop.pinFailed(NOW);

        assertThat(shop.isPinLocked(NOW.plusMinutes(4).plusSeconds(59))).isTrue();
        assertThat(shop.isPinLocked(NOW.plusMinutes(5))).isFalse();
        assertThat(shop.isLoginLocked(NOW)).isFalse();
    }

    @Test
    void 성공하면_실패_횟수가_0으로_돌아간다() {
        shop.loginFailed(NOW);
        shop.loginFailed(NOW);

        shop.loginSucceeded();
        for (int i = 0; i < 4; i++) {
            shop.loginFailed(NOW);
        }

        assertThat(shop.isLoginLocked(NOW)).isFalse();
    }
}
