package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 야간 발송 제한 규칙 (Keepsy #94, FR-CMP-07, C11, F52). DB 없이 돈다. */
class NightSendPolicyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    @Test
    void 야간은_21시_정각부터_08시_정각_전까지() {
        assertThat(NightSendPolicy.isNight(DAY.atTime(20, 59, 59))).isFalse();
        assertThat(NightSendPolicy.isNight(DAY.atTime(21, 0))).isTrue();
        assertThat(NightSendPolicy.isNight(DAY.atTime(0, 0))).isTrue();
        assertThat(NightSendPolicy.isNight(DAY.atTime(7, 59, 59))).isTrue();
        assertThat(NightSendPolicy.isNight(DAY.atTime(8, 0))).isFalse();
        assertThat(NightSendPolicy.isNight(DAY.atTime(14, 0))).isFalse();
    }

    @Test
    void 낮에는_언제나_허용하고_야간에는_광고이면서_보낼_고객_전원이_야간_광고_동의일_때만() {
        assertThat(NightSendPolicy.allows(DAY.atTime(14, 0), false, false)).isTrue();
        assertThat(NightSendPolicy.allows(DAY.atTime(22, 0), true, true)).isTrue();
        assertThat(NightSendPolicy.allows(DAY.atTime(22, 0), true, false)).isFalse();
        // 동의 재확인 안내는 야간 예외가 없다 (FR-CMP-13)
        assertThat(NightSendPolicy.allows(DAY.atTime(22, 0), false, true)).isFalse();
    }

    @Test
    void 다음_낮_시작은_밤이면_다음날_08시_새벽이면_그날_08시_낮이면_없음() {
        assertThat(NightSendPolicy.nextDaytime(DAY.atTime(21, 0)))
                .isEqualTo(DAY.plusDays(1).atTime(8, 0));
        assertThat(NightSendPolicy.nextDaytime(DAY.atTime(7, 30))).isEqualTo(DAY.atTime(8, 0));
        assertThat(NightSendPolicy.nextDaytime(DAY.atTime(8, 0))).isNull();
    }
}
