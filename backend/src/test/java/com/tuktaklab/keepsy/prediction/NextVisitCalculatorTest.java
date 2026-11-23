package com.tuktaklab.keepsy.prediction;

import static com.tuktaklab.keepsy.prediction.PredictionAttribute.END_SIGNAL;
import static com.tuktaklab.keepsy.prediction.PredictionAttribute.EXCLUDE;
import static com.tuktaklab.keepsy.prediction.PredictionAttribute.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-PRD-01 계산 규칙. 케이스는 Keepsy #72 「검증 방법」과 DEVELOPMENT 7장 우선 대상을 따른다. */
class NextVisitCalculatorTest {

    private static final LocalDate DAY0 = LocalDate.of(2026, 1, 1);
    private static final CycleRange NAIL = new CycleRange(21, 28);

    private static CategoryVisit visit(int day, VisitedMenu... menus) {
        return new CategoryVisit(DAY0.plusDays(day), List.of(menus));
    }

    private static VisitedMenu menu(PredictionAttribute attribute, CycleRange cycle) {
        return new VisitedMenu(attribute, false, cycle);
    }

    private static VisitedMenu nail() {
        return menu(NORMAL, NAIL);
    }

    private static NextVisitPrediction calc(CategoryVisit... visits) {
        return NextVisitCalculator.calculate(List.of(visits), NAIL).orElseThrow();
    }

    @Test
    void 방문_1회는_마지막_시술_기본_주기_범위_그대로() {
        NextVisitPrediction p = calc(visit(0, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(21));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(28));
        assertThat(p.visitCount()).isEqualTo(1);
        assertThat(p.averageIntervalDays()).isEqualTo(28); // 2회 이하면 기본 주기 최대값
        assertThat(p.endSignal()).isFalse();
    }

    @Test
    void 방문_2회는_실제_간격과_기본_주기_중간값의_평균_범위는_기본_주기_폭() {
        // 간격 20, 기본 주기 21~28(중간 24.5) → 중심 22.25, 폭 7 → 18.75~25.75 → 시작 내림 18, 종료 올림 26
        NextVisitPrediction p = calc(visit(0, nail()), visit(20, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(20 + 18));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(20 + 26));
        assertThat(p.visitCount()).isEqualTo(2);
        assertThat(p.averageIntervalDays()).isEqualTo(28);
    }

    @Test
    void 방문_3회_이상은_간격_평균_더하기_빼기_모집단_표준편차() {
        // 간격 20, 30 → 평균 25, 모집단 표준편차 5 → 20~30
        NextVisitPrediction p = calc(visit(0, nail()), visit(20, nail()), visit(50, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(50 + 20));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(50 + 30));
        assertThat(p.visitCount()).isEqualTo(3);
        assertThat(p.averageIntervalDays()).isEqualTo(25);
    }

    @Test
    void 표준편차가_3일보다_작으면_최소_3일() {
        // 간격 24, 24 → 표준편차 0 → ±3 → 21~27
        NextVisitPrediction p = calc(visit(0, nail()), visit(24, nail()), visit(48, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(48 + 21));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(48 + 27));
    }

    @Test
    void 소수_범위는_시작_내림_종료_올림() {
        // 간격 20, 30, 23 → 평균 24.33, 표준편차 4.19 → 20.14~28.52 → 20~29
        NextVisitPrediction p = calc(visit(0, nail()), visit(20, nail()), visit(50, nail()), visit(73, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(73 + 20));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(73 + 29));
        assertThat(p.averageIntervalDays()).isEqualTo(24);
    }

    @Test
    void 방문_6회_이상이면_최근_5개_간격만_쓴다() {
        // 첫 간격 100은 버리고 20 × 5 → 평균 20, 표준편차 0 → 17~23
        NextVisitPrediction p = calc(
                visit(0, nail()), visit(100, nail()), visit(120, nail()),
                visit(140, nail()), visit(160, nail()), visit(180, nail()), visit(200, nail()));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(200 + 17));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(200 + 23));
        assertThat(p.visitCount()).isEqualTo(7);
        assertThat(p.averageIntervalDays()).isEqualTo(20);
    }

    @Test
    void 방문_순서가_섞여_들어와도_날짜순으로_계산한다() {
        NextVisitPrediction p = calc(visit(50, nail()), visit(0, nail()), visit(20, nail()));

        assertThat(p.lastVisitDate()).isEqualTo(DAY0.plusDays(50));
        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(50 + 20));
    }

    @Test
    void 주기_계산_제외_방문은_간격과_마지막_방문일과_방문_수에서_뺀다() {
        // 제외 방문(10일째)을 빼면 0, 30 → 2회, 간격 30 → (60+63-28)/4=23.75→23, (60+84-21)/4=30.75→31
        NextVisitPrediction p = calc(
                visit(0, nail()), visit(10, menu(EXCLUDE, new CycleRange(28, 42))), visit(30, nail()));

        assertThat(p.visitCount()).isEqualTo(2);
        assertThat(p.lastVisitDate()).isEqualTo(DAY0.plusDays(30));
        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(30 + 23));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(30 + 31));
    }

    @Test
    void 방문별_주기_계산_제외_체크도_제외_방문으로_본다() {
        NextVisitPrediction p = calc(visit(0, nail()), visit(10, new VisitedMenu(NORMAL, true, NAIL)));

        assertThat(p.visitCount()).isEqualTo(1);
        assertThat(p.lastVisitDate()).isEqualTo(DAY0);
    }

    @Test
    void 모든_방문이_주기_계산_제외면_예측_없음() {
        assertThat(NextVisitCalculator.calculate(
                List.of(visit(0, menu(EXCLUDE, NAIL)), visit(30, new VisitedMenu(NORMAL, true, NAIL))),
                NAIL)).isEmpty();
    }

    @Test
    void 방문이_없으면_예측_없음() {
        assertThat(NextVisitCalculator.calculate(List.of(), NAIL)).isEmpty();
    }

    @Test
    void 종료_신호_방문이_마지막이면_새_예측_없이_시작일_종료일이_그_방문일() {
        NextVisitPrediction p = calc(visit(0, nail()), visit(25, nail()), visit(50, menu(END_SIGNAL, NAIL)));

        assertThat(p.endSignal()).isTrue();
        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(50));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(50));
        assertThat(p.visitCount()).isEqualTo(3); // 종료 신호 방문은 방문 수에 들어간다
        assertThat(p.averageIntervalDays()).isEqualTo(25);
    }

    @Test
    void 종료_신호와_주기_계산_제외가_섞인_방문도_종료_신호() {
        NextVisitPrediction p = calc(visit(0, nail()), visit(30, menu(END_SIGNAL, NAIL), menu(EXCLUDE, NAIL)));

        assertThat(p.endSignal()).isTrue();
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(30));
    }

    @Test
    void 일반_시술과_종료_신호가_같은_방문이면_일반_방문() {
        // 예: 젤 제거 + 젤네일
        NextVisitPrediction p = calc(visit(0, menu(END_SIGNAL, NAIL), nail()));

        assertThat(p.endSignal()).isFalse();
        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(21));
    }

    @Test
    void 한_방문에_메뉴가_여럿이면_주기_계산_제외가_아닌_메뉴_중_가장_긴_주기() {
        NextVisitPrediction p = calc(visit(0,
                menu(NORMAL, NAIL), menu(NORMAL, new CycleRange(30, 40)), menu(EXCLUDE, new CycleRange(100, 200))));

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(30));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(40));
        assertThat(p.averageIntervalDays()).isEqualTo(40);
    }

    @Test
    void 메뉴_없는_가져온_방문은_카테고리_기본_주기() {
        CycleRange waxingDefault = new CycleRange(28, 42);
        NextVisitPrediction p = NextVisitCalculator.calculate(
                List.of(new CategoryVisit(DAY0, List.of(VisitedMenu.imported()))), waxingDefault).orElseThrow();

        assertThat(p.startDate()).isEqualTo(DAY0.plusDays(28));
        assertThat(p.endDate()).isEqualTo(DAY0.plusDays(42));
    }

    @Test
    void 기본_주기는_0보다_크고_최소가_최대보다_클_수_없다() {
        assertThatThrownBy(() -> new CycleRange(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CycleRange(30, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
