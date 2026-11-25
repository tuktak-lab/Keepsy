package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 수신 거부 처리 안내의 야간 보류 (Keepsy #99, FR-CMP-07 · 11, F48). DB 없이 돈다. */
class UnsubscribeNoticeTimeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    @Test
    void 낮에는_바로_보낸다() {
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(8, 0))).isNull();
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(14, 30))).isNull();
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(20, 59, 59))).isNull();
    }

    @Test
    void 밤_21시부터는_다음날_08시() {
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(21, 0)))
                .isEqualTo(DAY.plusDays(1).atTime(8, 0));
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(23, 59)))
                .isEqualTo(DAY.plusDays(1).atTime(8, 0));
    }

    @Test
    void 새벽_08시_전에는_그날_08시() {
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(0, 0)))
                .isEqualTo(DAY.atTime(8, 0));
        assertThat(UnsubscribeNoticeService.sendAfter(DAY.atTime(7, 59, 59)))
                .isEqualTo(DAY.atTime(8, 0));
    }
}
