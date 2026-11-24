package com.tuktaklab.keepsy.account;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.account.Shop.OpeningHours;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 매장 정보 · 영업시간 수정 규칙 (Keepsy #27, FR-SET-01 · 07, F39). DB 없이 돈다. */
class ShopInfoChangeTest {

    /** 2026-11-30은 월요일 */
    private static final LocalDate MON = LocalDate.of(2026, 11, 30);

    private final Shop shop = Shop.create("owner@example.com", "hash", "테스트 네일");

    /** 월~토 10:00~18:00, 일요일 정기 휴무 */
    private static List<OpeningHours> week() {
        return new ArrayList<>(
                Arrays.stream(DayOfWeek.values())
                        .map(d ->
                                d == SUNDAY
                                        ? new OpeningHours(d, null, null)
                                        : new OpeningHours(
                                                d, LocalTime.of(10, 0), LocalTime.of(18, 0)))
                        .toList());
    }

    private void assertInvalid(List<OpeningHours> hours) {
        assertThatThrownBy(() -> shop.changeInfo("새 이름", null, null, null, hours))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
    }

    @Test
    void 통째로_바꾸고_이름_공백은_빼고_빈_번호는_null() {
        shop.changeInfo("  모의 네일  ", "02-000-0000", " ", null, week());

        assertThat(shop.getName()).isEqualTo("모의 네일");
        assertThat(shop.getPhone()).isEqualTo("02-000-0000");
        assertThat(shop.getSenderNumber()).isNull();
        assertThat(shop.openingHours()).isEqualTo(week());
    }

    @Test
    void 요일이_빠지거나_겹치거나_하나만_비거나_30분_단위가_아니거나_시작이_늦으면_invalid이고_그대로() {
        List<OpeningHours> before = shop.openingHours();

        assertInvalid(week().subList(0, 6));
        List<OpeningHours> twice = week();
        twice.set(6, new OpeningHours(MONDAY, LocalTime.of(10, 0), LocalTime.of(18, 0)));
        assertInvalid(twice);
        List<OpeningHours> half = week();
        half.set(0, new OpeningHours(MONDAY, LocalTime.of(10, 0), null));
        assertInvalid(half);
        List<OpeningHours> offUnit = week();
        offUnit.set(0, new OpeningHours(MONDAY, LocalTime.of(10, 15), LocalTime.of(18, 0)));
        assertInvalid(offUnit);
        List<OpeningHours> reversed = week();
        reversed.set(0, new OpeningHours(MONDAY, LocalTime.of(18, 0), LocalTime.of(18, 0)));
        assertInvalid(reversed);

        assertThat(shop.getName()).isEqualTo("테스트 네일");
        assertThat(shop.openingHours()).isEqualTo(before);
    }

    @Test
    void 예약이_시작부터_종료까지_영업시간_안이어야_안으로_본다() {
        shop.changeInfo("모의 네일", null, null, null, week());

        assertThat(shop.withinOpeningHours(MON.atTime(10, 0), MON.atTime(18, 0))).isTrue();
        assertThat(shop.withinOpeningHours(MON.atTime(9, 30), MON.atTime(11, 0))).isFalse();
        assertThat(shop.withinOpeningHours(MON.atTime(17, 0), MON.atTime(18, 30))).isFalse();
        // 일요일 정기 휴무
        assertThat(
                        shop.withinOpeningHours(
                                MON.minusDays(1).atTime(12, 0), MON.minusDays(1).atTime(13, 0)))
                .isFalse();
        // 날을 넘기는 예약
        assertThat(shop.withinOpeningHours(MON.atTime(17, 0), MON.plusDays(1).atTime(0, 0)))
                .isFalse();
    }
}
