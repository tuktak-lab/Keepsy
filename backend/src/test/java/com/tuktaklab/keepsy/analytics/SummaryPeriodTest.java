package com.tuktaklab.keepsy.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.analytics.SummaryPeriod.Range;
import com.tuktaklab.keepsy.common.error.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 기간 필터 다섯 가지 (FR-ANL-06, F10 · F24) */
class SummaryPeriodTest {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 3, 1);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 3, 4);

    private static Range range(SummaryPeriod p, LocalDate today) {
        return p.range(today, null, null);
    }

    @Test
    void 오늘은_하루() {
        assertThat(range(SummaryPeriod.TODAY, WEDNESDAY))
                .isEqualTo(new Range(WEDNESDAY, WEDNESDAY));
    }

    @Test
    void 이번_주는_월요일부터_일요일_일요일이면_그_주_월요일부터() {
        assertThat(range(SummaryPeriod.THIS_WEEK, WEDNESDAY))
                .isEqualTo(new Range(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8)));
        assertThat(range(SummaryPeriod.THIS_WEEK, SUNDAY))
                .isEqualTo(new Range(LocalDate.of(2026, 2, 23), SUNDAY));
    }

    @Test
    void 이번_달과_지난달은_1일부터_말일_해를_넘긴다() {
        assertThat(range(SummaryPeriod.THIS_MONTH, WEDNESDAY))
                .isEqualTo(new Range(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
        assertThat(range(SummaryPeriod.LAST_MONTH, WEDNESDAY))
                .isEqualTo(new Range(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertThat(range(SummaryPeriod.LAST_MONTH, LocalDate.of(2026, 1, 15)))
                .isEqualTo(new Range(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)));
    }

    @Test
    void 직접_지정은_그대로_날짜가_없거나_거꾸로면_invalid() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        assertThat(SummaryPeriod.CUSTOM.range(WEDNESDAY, from, WEDNESDAY))
                .isEqualTo(new Range(from, WEDNESDAY));
        assertThat(SummaryPeriod.CUSTOM.range(WEDNESDAY, from, from))
                .isEqualTo(new Range(from, from));

        assertThatThrownBy(() -> SummaryPeriod.CUSTOM.range(WEDNESDAY, from, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SummaryPeriod.CUSTOM.range(WEDNESDAY, WEDNESDAY, from))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 직접_지정이_아니면_from_to를_읽지_않는다() {
        assertThat(SummaryPeriod.TODAY.range(WEDNESDAY, WEDNESDAY, SUNDAY))
                .isEqualTo(new Range(WEDNESDAY, WEDNESDAY));
    }
}
