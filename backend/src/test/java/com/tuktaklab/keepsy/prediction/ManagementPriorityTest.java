package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.prediction.ManagementPriority.PriorityKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-PRD-09, E6. 케이스는 Keepsy #76 「완료 조건」을 따른다. */
class ManagementPriorityTest {

    private static final LocalDate END = LocalDate.of(2026, 3, 1);
    private static final int CAP = 120;

    /** 예측 종료일 END, 평균 간격 28일 → 이탈 신호 기간 28일 */
    private static final NextVisitPrediction NAIL =
            new NextVisitPrediction(END.minusDays(28), 3, 28, END.minusDays(7), END, false);

    @Test
    void 경과_비율은_주기_도래면_0_이하_이탈_신호면_0에서_1() {
        assertThat(ManagementPriority.elapsedRatio(NAIL, CAP, END.minusDays(7))).isEqualTo(-0.25);
        assertThat(ManagementPriority.elapsedRatio(NAIL, CAP, END)).isZero();
        assertThat(ManagementPriority.elapsedRatio(NAIL, CAP, END.plusDays(14))).isEqualTo(0.5);
        assertThat(ManagementPriority.elapsedRatio(NAIL, CAP, END.plusDays(28))).isEqualTo(1.0);
    }

    @Test
    void 주기가_다른_시술도_같은_기준으로_비교된다() {
        // 네일: 이탈 신호 기간 28일 중 14일 경과 = 0.5, 반영구: 상한 120일 중 90일 경과 = 0.75
        NextVisitPrediction semiPermanent =
                new NextVisitPrediction(END.minusDays(400), 3, 500, END.minusDays(60), END, false);

        double nail = ManagementPriority.elapsedRatio(NAIL, CAP, END.plusDays(14));
        double semi = ManagementPriority.elapsedRatio(semiPermanent, CAP, END.plusDays(90));

        assertThat(semi).isEqualTo(0.75);
        assertThat(semi).isGreaterThan(nail);
    }

    @Test
    void 종료_신호는_종료_신호_방문일을_종료일로_본다() {
        NextVisitPrediction endSignal = new NextVisitPrediction(END, 3, 20, END, END, true);

        assertThat(ManagementPriority.elapsedRatio(endSignal, CAP, END.plusDays(5))).isEqualTo(0.25);
    }

    @Test
    void 경과_비율_높은_순_같으면_수신_동의_먼저_같으면_결제_합계_높은_순() {
        PriorityKey nearChurnEnd = new PriorityKey(0.9, false, 0);
        PriorityKey consentRich = new PriorityKey(0.5, true, 300_000);
        PriorityKey consentPoor = new PriorityKey(0.5, true, 50_000);
        PriorityKey noConsentRich = new PriorityKey(0.5, false, 900_000);
        PriorityKey due = new PriorityKey(-0.2, true, 1_000_000);

        List<PriorityKey> rows = new ArrayList<>(List.of(due, noConsentRich, consentPoor, nearChurnEnd, consentRich));
        rows.sort(ManagementPriority.ORDER);

        assertThat(rows).containsExactly(nearChurnEnd, consentRich, consentPoor, noConsentRich, due);
    }
}
