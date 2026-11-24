package com.tuktaklab.keepsy.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.analytics.Attribution.Candidate;
import com.tuktaklab.keepsy.analytics.Attribution.SentAd;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** FR-VIS-04, FR-ANL-03, A8, E7. 케이스는 Keepsy #101 「완료 조건」 · 「검증 방법」을 따른다. */
class AttributionTest {

    private static final int DAYS = 14;

    /** A8 예시: 11/1 캠페인 A 발송, 11/8 캠페인 B 발송 (네일) */
    private static final SentAd A = new SentAd(1L, "NAIL", at(11, 1, 10));

    private static final SentAd B = new SentAd(2L, "NAIL", at(11, 8, 10));

    private static LocalDateTime at(int month, int day, int hour) {
        return LocalDateTime.of(2026, month, day, hour, 0);
    }

    private static Long nail(LocalDateTime time) {
        return Attribution.attribute("NAIL", time, List.of(A, B), Set.of(), DAYS).orElse(null);
    }

    @Test
    void 여러_발송을_받았으면_가장_최근_발송에_연결하고_귀속_기간이_지나면_연결하지_않는다() {
        assertThat(nail(at(11, 10, 15))).isEqualTo(2L);
        assertThat(nail(at(11, 20, 15))).isEqualTo(2L); // B에서 12일
        assertThat(nail(at(11, 22, 23))).isEqualTo(2L); // 발송일 + 14일까지
        assertThat(nail(at(11, 23, 0))).isNull();
        assertThat(nail(at(11, 30, 15))).isNull(); // B에서 22일
    }

    @Test
    void 발송_전에_만든_예약은_그_발송에_연결하지_않는다() {
        assertThat(nail(at(11, 8, 9))).isEqualTo(1L); // B 발송(10시) 전이라 A
        assertThat(nail(at(10, 31, 9))).isNull();
    }

    @Test
    void 같은_카테고리_발송만_귀속하고_한_예약의_두_카테고리는_각각_연결한다() {
        SentAd waxing = new SentAd(3L, "WAXING", at(11, 9, 10));
        List<SentAd> sends = List.of(A, B, waxing);
        LocalDateTime reservedAt = at(11, 10, 15);

        assertThat(Attribution.attribute("NAIL", reservedAt, sends, Set.of(), DAYS)).contains(2L);
        assertThat(Attribution.attribute("WAXING", reservedAt, sends, Set.of(), DAYS)).contains(3L);
        assertThat(Attribution.attribute("LASH", reservedAt, sends, Set.of(), DAYS)).isEmpty();
    }

    @Test
    void 이미_연결된_발송이면_첫_예약이_아니라서_연결하지_않는다() {
        assertThat(Attribution.attribute("NAIL", at(11, 12, 15), List.of(A, B), Set.of(2L), DAYS))
                .isEmpty();
    }

    @Test
    void 고객_없는_예약은_고객을_연결할_때_예약_생성_시각을_기준으로_계산한다() {
        LocalDateTime createdAt = at(11, 20, 15); // B에서 12일
        // 고객 연결은 11/25에 했지만 기준은 생성 시각이라 귀속된다
        assertThat(Attribution.attribute("NAIL", createdAt, List.of(A, B), Set.of(), DAYS))
                .contains(2L);
    }

    @Test
    void 귀속_예약이_취소_노쇼되면_기간_안의_같은_카테고리_다음_예약이_이어받는다() {
        List<Candidate> candidates =
                List.of(
                        new Candidate(30L, "NAIL", at(11, 18, 12)),
                        new Candidate(20L, "NAIL", at(11, 15, 12)),
                        new Candidate(40L, "WAXING", at(11, 12, 12)),
                        new Candidate(50L, "NAIL", at(11, 23, 12)));

        assertThat(Attribution.successor(B, candidates, DAYS)).contains(20L);
    }

    @Test
    void 이어받을_예약이_없으면_승계하지_않는다() {
        List<Candidate> candidates =
                List.of(
                        new Candidate(10L, "NAIL", at(11, 7, 12)), // 발송 전
                        new Candidate(50L, "NAIL", at(11, 23, 12)), // 기간 밖
                        new Candidate(40L, "WAXING", at(11, 12, 12))); // 다른 카테고리

        assertThat(Attribution.successor(B, candidates, DAYS)).isEmpty();
        assertThat(Attribution.successor(B, List.of(), DAYS)).isEmpty();
    }

    @Test
    void 귀속_기간은_0보다_커야_한다() {
        assertThatThrownBy(
                        () -> Attribution.attribute("NAIL", at(11, 10, 0), List.of(), Set.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
