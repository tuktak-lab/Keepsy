package com.tuktaklab.keepsy.prediction;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 고객 × 시술 카테고리별 다음 방문 기간 계산 (FR-PRD-01, 기획서 9장 「계산 규칙」). 값만으로 결과가 정해지는 규칙이라 DB · 스프링 없이 단위 테스트한다
 * (DEVELOPMENT 4장).
 *
 * <p>문서에 없던 세부는 2026-11-23 팀 결정을 따른다: 표준편차는 모집단(÷n), 예측 시작일은 내림 · 종료일은 올림(범위를 바깥으로 넓힘), 종료 신호면 시작일
 * = 종료일 = 방문일.
 */
public final class NextVisitCalculator {

    static final int RECENT_INTERVALS = 5;
    static final double MIN_DEVIATION_DAYS = 3.0;

    /** sqrt 같은 부동소수 계산이 정수 경계에서 하루 어긋나지 않게 하는 여유 */
    private static final double EPSILON = 1e-9;

    private NextVisitCalculator() {}

    /**
     * @param visits 한 고객의 한 카테고리 방문 (순서 무관)
     * @param categoryDefault 카테고리 기본 주기 (메뉴 없는 가져온 방문에 씀)
     * @return 다음 방문 기간. 방문이 없거나 모두 주기 계산 제외면 "예측 없음"으로 비어 있다
     */
    public static Optional<NextVisitPrediction> calculate(
            List<CategoryVisit> visits, CycleRange categoryDefault) {
        List<CategoryVisit> counted =
                visits.stream()
                        .filter(v -> kindOf(v) != Kind.EXCLUDED)
                        .sorted(Comparator.comparing(CategoryVisit::date))
                        .toList();
        if (counted.isEmpty()) {
            return Optional.empty();
        }

        CategoryVisit last = counted.getLast();
        LocalDate lastDate = last.date();
        int visitCount = counted.size();
        List<Long> recent = recentIntervals(counted);
        CycleRange baseCycle = baseCycleOf(last, categoryDefault);

        int averageInterval =
                visitCount >= 3 ? (int) Math.round(mean(recent)) : baseCycle.maxDays();

        if (kindOf(last) == Kind.END_SIGNAL) {
            return Optional.of(
                    new NextVisitPrediction(
                            lastDate, visitCount, averageInterval, lastDate, lastDate, true));
        }

        long startOffset;
        long endOffset;
        if (visitCount >= 3) {
            double mean = mean(recent);
            double deviation = Math.max(populationStdDev(recent, mean), MIN_DEVIATION_DAYS);
            startOffset = (long) Math.floor(mean - deviation + EPSILON);
            endOffset = (long) Math.ceil(mean + deviation - EPSILON);
        } else if (visitCount == 2) {
            // 중심 = (실제 간격 + 기본 주기 중간값) / 2, 범위 = 기본 주기 폭.
            // 시작 = (2·간격 + 3·최소 − 최대) / 4, 종료 = (2·간격 + 3·최대 − 최소) / 4 를 정수로 계산한다.
            long interval = recent.getFirst();
            int min = baseCycle.minDays();
            int max = baseCycle.maxDays();
            startOffset = Math.floorDiv(2 * interval + 3L * min - max, 4);
            endOffset = -Math.floorDiv(-(2 * interval + 3L * max - min), 4);
        } else {
            startOffset = baseCycle.minDays();
            endOffset = baseCycle.maxDays();
        }
        return Optional.of(
                new NextVisitPrediction(
                        lastDate,
                        visitCount,
                        averageInterval,
                        lastDate.plusDays(startOffset),
                        lastDate.plusDays(endOffset),
                        false));
    }

    private enum Kind {
        NORMAL,
        END_SIGNAL,
        EXCLUDED
    }

    /** 일반 시술이 하나라도 있으면 일반, 없고 종료 신호가 있으면 종료 신호, 모두 주기 계산 제외면 제외 (FR-PRD-01 ①②). */
    private static Kind kindOf(CategoryVisit visit) {
        List<VisitedMenu> counting =
                visit.menus().stream().filter(VisitedMenu::countsForCycle).toList();
        if (counting.stream().anyMatch(m -> m.attribute() == PredictionAttribute.NORMAL)) {
            return Kind.NORMAL;
        }
        if (counting.stream().anyMatch(m -> m.attribute() == PredictionAttribute.END_SIGNAL)) {
            return Kind.END_SIGNAL;
        }
        return Kind.EXCLUDED;
    }

    /** 마지막 방문의 주기 계산 제외가 아닌 메뉴 중 가장 긴 주기. 메뉴 없는 가져온 방문은 카테고리 기본 주기 (E5). */
    private static CycleRange baseCycleOf(CategoryVisit last, CycleRange categoryDefault) {
        return last.menus().stream()
                .filter(VisitedMenu::countsForCycle)
                .map(m -> m.cycle() != null ? m.cycle() : categoryDefault)
                .max(
                        Comparator.comparingInt(CycleRange::maxDays)
                                .thenComparingInt(CycleRange::minDays))
                .orElse(categoryDefault);
    }

    private static List<Long> recentIntervals(List<CategoryVisit> sorted) {
        int from = Math.max(1, sorted.size() - RECENT_INTERVALS);
        return IntStream.range(from, sorted.size())
                .mapToObj(i ->
                        ChronoUnit.DAYS.between(sorted.get(i - 1).date(), sorted.get(i).date()))
                .toList();
    }

    private static double mean(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static double populationStdDev(List<Long> values, double mean) {
        double variance =
                values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(variance);
    }
}
