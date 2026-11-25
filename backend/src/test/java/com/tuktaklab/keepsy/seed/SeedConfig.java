package com.tuktaklab.keepsy.seed;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 시드 생성 컨텍스트 (#335). 업무 코드 · 운영 Clock은 그대로 두고, 이 테스트 컨텍스트에서만 시계와 난수를 바꾼다 ({@code CampaignSendTest}의
 * MutableClock 방식).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SeedGenerator.class)
class SeedConfig {

    @Bean
    @Primary
    SeedClock seedClock() {
        return new SeedClock();
    }

    /** 대조군 배정 · 가상 발송 실패 판정이 쓰는 난수를 고정한다 */
    @Bean
    @Primary
    SeedRandom seedRandom() {
        return new SeedRandom();
    }

    /** 생성기가 이벤트 시각으로 옮기는 서울 시계 */
    static class SeedClock extends Clock {
        private volatile Instant now = Instant.EPOCH;

        void at(LocalDateTime seoul) {
            now = seoul.atZone(TimeConfig.ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return TimeConfig.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 실행마다 {@code seed.random}으로 되돌리는 난수 */
    static class SeedRandom implements RandomGenerator {
        private SplittableRandom random = new SplittableRandom(0);

        void reset(long seed) {
            random = new SplittableRandom(seed);
        }

        @Override
        public long nextLong() {
            return random.nextLong();
        }
    }
}
