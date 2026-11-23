package com.tuktaklab.keepsy.campaign;

import static com.tuktaklab.keepsy.campaign.ExclusionReason.NO_CONSENT;
import static com.tuktaklab.keepsy.campaign.ExclusionReason.RESEND_LIMIT;
import static com.tuktaklab.keepsy.campaign.ExclusionReason.UNSUBSCRIBED;
import static com.tuktaklab.keepsy.campaign.MessageKind.AD;
import static com.tuktaklab.keepsy.campaign.MessageKind.CONSENT_RECONFIRM;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** FR-CMP-02 · 13, B2. 케이스는 Keepsy #88 「완료 조건」 · 「검증 방법」을 따른다. */
class ExclusionReasonTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 10, 0);
    private static final int DAYS = 7;
    private static final Optional<LocalDateTime> NO_AD = Optional.empty();

    private static Optional<ExclusionReason> ad(boolean consent, Optional<LocalDateTime> lastAd) {
        return ExclusionReason.of(AD, consent, false, lastAd, DAYS, NOW);
    }

    @Test
    void 동의했고_최근_광고가_없으면_보낸다() {
        assertThat(ad(true, NO_AD)).isEmpty();
    }

    @Test
    void 수신_거부와_미동의를_철회_경로로_구분한다() {
        assertThat(ExclusionReason.of(AD, false, true, NO_AD, DAYS, NOW)).contains(UNSUBSCRIBED);
        assertThat(ExclusionReason.of(AD, false, false, NO_AD, DAYS, NOW)).contains(NO_CONSENT);
    }

    @Test
    void 재발송_제한은_지금부터_N일_전보다_뒤에_받은_광고만() {
        assertThat(ad(true, Optional.of(NOW.minusDays(DAYS).plusSeconds(1))))
                .contains(RESEND_LIMIT);
        assertThat(ad(true, Optional.of(NOW.minusDays(DAYS)))).isEmpty();
    }

    @Test
    void 동의_사유가_재발송_제한보다_먼저() {
        Optional<LocalDateTime> recent = Optional.of(NOW.minusDays(1));
        assertThat(ExclusionReason.of(AD, false, true, recent, DAYS, NOW)).contains(UNSUBSCRIBED);
        assertThat(ExclusionReason.of(AD, false, false, recent, DAYS, NOW)).contains(NO_CONSENT);
    }

    @Test
    void 동의_재확인_안내에는_재발송_제한을_적용하지_않는다() {
        Optional<LocalDateTime> recent = Optional.of(NOW.minusDays(1));
        assertThat(ExclusionReason.of(CONSENT_RECONFIRM, true, false, recent, DAYS, NOW)).isEmpty();
    }
}
