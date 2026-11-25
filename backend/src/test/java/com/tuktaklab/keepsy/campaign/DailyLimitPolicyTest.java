package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 일 발송 한도 판정 (Keepsy #95, FR-CMP-12, F54). DB 없이 돈다. */
class DailyLimitPolicyTest {

    @Test
    void 오늘_건수와_이번_인원의_합이_한도_이하면_허용하고_넘으면_전체_거부() {
        assertThat(DailyLimitPolicy.allows(0, 500, 500)).isTrue();
        assertThat(DailyLimitPolicy.allows(498, 2, 500)).isTrue();
        assertThat(DailyLimitPolicy.allows(498, 3, 500)).isFalse();
        assertThat(DailyLimitPolicy.allows(0, 501, 500)).isFalse();
    }

    @Test
    void 보낼_고객이_없으면_한도를_넘었어도_막지_않는다() {
        assertThat(DailyLimitPolicy.allows(600, 0, 500)).isTrue();
    }
}
