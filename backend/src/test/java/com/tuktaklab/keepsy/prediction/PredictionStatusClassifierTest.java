package com.tuktaklab.keepsy.prediction;

import static com.tuktaklab.keepsy.prediction.PredictionStatus.CHURN_SIGNAL;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.DUE;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.LONG_ABSENT;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.NO_PREDICTION;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FR-PRD-04, FR-SET-03. 케이스는 Keepsy #75 「완료 조건」을 따른다. */
class PredictionStatusClassifierTest {

    private static final LocalDate LAST = LocalDate.of(2026, 3, 1);
    private static final int CAP = 120;

    /** 마지막 방문 + 21~28일, 평균 간격 28일 → 이탈 신호 기간 28일 */
    private static final NextVisitPrediction NAIL =
            new NextVisitPrediction(LAST, 1, 28, LAST.plusDays(21), LAST.plusDays(28), false);

    private static Optional<PredictionStatus> statusOn(NextVisitPrediction p, int day) {
        return PredictionStatusClassifier.classify(Optional.of(p), false, CAP, LAST.plusDays(day));
    }

    @Test
    void 시작일_전은_예정_시작일부터_종료일까지는_주기_도래() {
        assertThat(statusOn(NAIL, 20)).contains(SCHEDULED);
        assertThat(statusOn(NAIL, 21)).contains(DUE);
        assertThat(statusOn(NAIL, 28)).contains(DUE);
    }

    @Test
    void 종료일_다음_날부터_이탈_신호_기간이_끝나면_장기_미방문() {
        assertThat(statusOn(NAIL, 29)).contains(CHURN_SIGNAL);
        assertThat(statusOn(NAIL, 28 + 28)).contains(CHURN_SIGNAL);
        assertThat(statusOn(NAIL, 28 + 29)).contains(LONG_ABSENT);
    }

    @Test
    void 이탈_신호_기간은_매장_상한으로_자른다() {
        NextVisitPrediction semiPermanent =
                new NextVisitPrediction(
                        LAST, 3, 400, LAST.plusDays(365), LAST.plusDays(430), false);

        assertThat(PredictionStatusClassifier.churnPeriodDays(semiPermanent, CAP)).isEqualTo(120);
        assertThat(statusOn(semiPermanent, 430 + 120)).contains(CHURN_SIGNAL);
        assertThat(statusOn(semiPermanent, 430 + 121)).contains(LONG_ABSENT);
    }

    @Test
    void 종료_신호는_방문_당일부터_바로_이탈_신호이고_방문일이_종료일() {
        NextVisitPrediction endSignal = new NextVisitPrediction(LAST, 3, 25, LAST, LAST, true);

        assertThat(statusOn(endSignal, 0)).contains(CHURN_SIGNAL);
        assertThat(statusOn(endSignal, 25)).contains(CHURN_SIGNAL);
        assertThat(statusOn(endSignal, 26)).contains(LONG_ABSENT);
    }

    @Test
    void 다음_예약에_해당_카테고리가_있으면_관리_대상에서_빠진다() {
        assertThat(
                        PredictionStatusClassifier.classify(
                                Optional.of(NAIL), true, CAP, LAST.plusDays(40)))
                .isEmpty();
    }

    @Test
    void 예측_없음은_분류하지_않고_예측_없음() {
        assertThat(PredictionStatusClassifier.classify(Optional.empty(), false, CAP, LAST))
                .contains(NO_PREDICTION);
    }

    @Test
    void 계산_결과를_그대로_받아_분류한다() {
        // 간격 20, 30 → 평균 25 → 마지막 방문 + 20~30, 이탈 신호 기간 25
        Optional<NextVisitPrediction> p =
                NextVisitCalculator.calculate(
                        List.of(
                                new CategoryVisit(
                                        LAST.minusDays(50),
                                        List.of(
                                                new VisitedMenu(
                                                        PredictionAttribute.NORMAL, false, null))),
                                new CategoryVisit(
                                        LAST.minusDays(30),
                                        List.of(
                                                new VisitedMenu(
                                                        PredictionAttribute.NORMAL, false, null))),
                                new CategoryVisit(
                                        LAST,
                                        List.of(
                                                new VisitedMenu(
                                                        PredictionAttribute.NORMAL, false, null)))),
                        new CycleRange(21, 28));

        assertThat(PredictionStatusClassifier.classify(p, false, CAP, LAST.plusDays(30 + 25)))
                .contains(CHURN_SIGNAL);
        assertThat(PredictionStatusClassifier.classify(p, false, CAP, LAST.plusDays(30 + 26)))
                .contains(LONG_ABSENT);
    }

    @Test
    void 이탈_신호_기간은_최소_1일이고_상한은_0보다_커야_한다() {
        NextVisitPrediction zero = new NextVisitPrediction(LAST, 3, 0, LAST, LAST, false);

        assertThat(PredictionStatusClassifier.churnPeriodDays(zero, CAP)).isEqualTo(1);
        assertThatThrownBy(() -> PredictionStatusClassifier.churnPeriodDays(NAIL, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
