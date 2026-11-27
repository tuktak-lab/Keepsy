package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 매장 업종 규칙 (Keepsy #421, FR-SET-01, CONTRACTS 3절 「매장 업종」). DB 없이 돈다. */
class ShopIndustriesTest {

    private static final List<String> ALL =
            List.of("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC");

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");

    private void assertInvalid(List<String> values) {
        List<String> before = shop.industries();
        assertThatThrownBy(() -> shop.changeIndustries(values))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
        assertThat(shop.industries()).isEqualTo(before);
    }

    @Test
    void 업종을_고르지_않고_만들면_5개_모두_켜져_있다() {
        assertThat(shop.industries()).isEqualTo(ALL);
    }

    @Test
    void 가입_때_고른_업종만_켜지고_순서는_계약_3절_순서로_맞춘다() {
        Shop s =
                Shop.create(
                        "a@example.com",
                        "hash",
                        "네일",
                        "02-000-0000",
                        List.of("ETC", "NAIL", "WAXING"));

        assertThat(s.industries()).containsExactly("NAIL", "WAXING", "ETC");
    }

    @Test
    void 통째로_바꾼다() {
        shop.changeIndustries(List.of("SEMI_PERMANENT", "LASH"));

        assertThat(shop.industries()).containsExactly("LASH", "SEMI_PERMANENT");
    }

    @Test
    void 비었거나_null이면_invalid이고_그대로() {
        assertInvalid(List.of());
        assertInvalid(null);
    }

    @Test
    void 같은_값이_두_번이거나_5개_밖의_값이거나_null이_섞이면_invalid이고_그대로() {
        assertInvalid(List.of("NAIL", "NAIL"));
        assertInvalid(List.of("NAIL", "HAIR"));
        assertInvalid(List.of("nail"));
        assertInvalid(Arrays.asList("NAIL", null));
    }

    @Test
    void 가입_때_업종이_규칙을_어기면_매장을_만들지_못한다() {
        assertThatThrownBy(() -> Shop.create("a@example.com", "hash", "네일", null, List.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Shop.create("a@example.com", "hash", "네일", null, List.of("HAIR")))
                .isInstanceOf(ApiException.class);
    }
}
