package com.tuktaklab.keepsy.campaign;

import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄러 작업은 모두 발송 쪽이라 여기서 켠다 (ARCHITECTURE 7장 「스케줄러 작업」: 가상 발송 결과 갱신, 예약 발송, 야간 수신 거부 안내). */
@Configuration
@EnableScheduling
class CampaignSchedulingConfig {

    /** 가상 발송 무작위 실패 판정 (F17). 테스트는 비율 0% · 100%로 결과를 고정한다 */
    @Bean
    RandomGenerator virtualSendRandom() {
        return RandomGenerator.getDefault();
    }
}
