package com.tuktaklab.keepsy.analytics;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** 매장 기본 지표의 기간 필터 (FR-ANL-06, 결정 F24). 이번 주는 월요일 시작 (F10). 값만으로 정해지는 규칙이라 오늘 날짜를 받아 단위 테스트한다. */
public enum SummaryPeriod {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    CUSTOM;

    /** 기간, 둘 다 포함 */
    public record Range(LocalDate from, LocalDate to) {}

    /**
     * @param today Asia/Seoul 오늘
     * @param from {@code CUSTOM}일 때만 읽는다
     * @param to {@code CUSTOM}일 때만 읽는다
     * @throws ApiException {@code CUSTOM}인데 날짜가 없거나 {@code from} > {@code to}이면 {@code invalid}
     */
    public Range range(LocalDate today, LocalDate from, LocalDate to) {
        return switch (this) {
            case TODAY -> new Range(today, today);
            case THIS_WEEK -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Range(monday, monday.plusDays(6));
            }
            case THIS_MONTH -> month(today);
            case LAST_MONTH -> month(today.minusMonths(1));
            case CUSTOM -> {
                if (from == null || to == null || from.isAfter(to)) {
                    throw new ApiException(ErrorCode.INVALID, "기간을 확인해 주세요");
                }
                yield new Range(from, to);
            }
        };
    }

    private static Range month(LocalDate day) {
        return new Range(day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()));
    }
}
