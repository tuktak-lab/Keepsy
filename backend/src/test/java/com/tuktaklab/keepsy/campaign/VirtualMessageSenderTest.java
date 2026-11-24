package com.tuktaklab.keepsy.campaign;

import static com.tuktaklab.keepsy.campaign.VirtualMessageSender.INVALID_PHONE;
import static com.tuktaklab.keepsy.campaign.VirtualMessageSender.RANDOM_FAILURE;
import static com.tuktaklab.keepsy.campaign.VirtualMessageSender.failureReason;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** 가상 발송 결과 판정 (FR-CMP-10, F17). 난수는 고정 값을 주입해 재현한다. 모의 번호만 쓴다. */
class VirtualMessageSenderTest {

    /** nextInt(100)이 항상 value인 난수 생성기 */
    private static RandomGenerator fixed(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    @Test
    void 번호_형식이_틀리면_비율과_상관없이_실패() {
        assertThat(failureReason("010-123", 0, fixed(99))).isEqualTo(INVALID_PHONE);
        assertThat(failureReason("01000000000", 0, fixed(99))).isEqualTo(INVALID_PHONE);
        assertThat(failureReason(null, 0, fixed(99))).isEqualTo(INVALID_PHONE);
    }

    @Test
    void 비율_0이면_항상_성공_100이면_항상_실패() {
        assertThat(failureReason("010-0000-0001", 0, fixed(0))).isNull();
        assertThat(failureReason("010-0000-0001", 100, fixed(99))).isEqualTo(RANDOM_FAILURE);
    }

    @Test
    void 뽑은_값이_비율보다_작을_때만_무작위_실패() {
        assertThat(failureReason("010-0000-0001", 30, fixed(29))).isEqualTo(RANDOM_FAILURE);
        assertThat(failureReason("010-0000-0001", 30, fixed(30))).isNull();
    }
}
