package com.tuktaklab.keepsy.prediction;

import static com.tuktaklab.keepsy.prediction.NextAction.CHECK_IN;
import static com.tuktaklab.keepsy.prediction.NextAction.REQUEST_CONSENT;
import static com.tuktaklab.keepsy.prediction.NextAction.REVISIT;
import static com.tuktaklab.keepsy.prediction.NextAction.WIN_BACK;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.CHURN_SIGNAL;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.DUE;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.LONG_ABSENT;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.NO_PREDICTION;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FR-PRD-06, F19. 케이스는 Keepsy #77 「완료 조건」을 따른다. */
class NextActionTest {

    @Test
    void 동의_고객은_상태별_템플릿_용도() {
        assertThat(NextAction.suggest(DUE, true, false)).contains(REVISIT);
        assertThat(NextAction.suggest(CHURN_SIGNAL, true, false)).contains(CHECK_IN);
        assertThat(NextAction.suggest(LONG_ABSENT, true, false)).contains(WIN_BACK);
    }

    @Test
    void 동의를_받은_적_없는_고객은_동의_요청() {
        for (PredictionStatus s : new PredictionStatus[] {DUE, CHURN_SIGNAL, LONG_ABSENT}) {
            assertThat(NextAction.suggest(s, false, false)).contains(REQUEST_CONSENT);
        }
    }

    @Test
    void 동의를_철회한_고객은_제안_없음() {
        for (PredictionStatus s : new PredictionStatus[] {DUE, CHURN_SIGNAL, LONG_ABSENT}) {
            assertThat(NextAction.suggest(s, false, true)).isEmpty();
        }
    }

    @Test
    void 예정과_예측_없음은_동의와_상관없이_제안_없음() {
        for (PredictionStatus s : new PredictionStatus[] {SCHEDULED, NO_PREDICTION}) {
            assertThat(NextAction.suggest(s, true, false)).isEmpty();
            assertThat(NextAction.suggest(s, false, false)).isEmpty();
            assertThat(NextAction.suggest(s, false, true)).isEmpty();
        }
    }
}
