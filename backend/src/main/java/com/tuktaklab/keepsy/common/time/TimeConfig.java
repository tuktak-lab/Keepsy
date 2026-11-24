package com.tuktaklab.keepsy.common.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간대는 Asia/Seoul 하나 (NFR-12, ARCHITECTURE 5장 「구현 메모」). DB · JDBC 시간대는 application.yaml에 있다.
 *
 * <ul>
 *   <li>"오늘" · "지금"은 {@link Clock}을 주입받아 {@code LocalDate.now(clock)}로 구한다. 테스트는 {@code
 *       Clock.fixed(...)}로 날짜를 고정한다 (FR-PRD-04 예측 상태는 조회 시 오늘 날짜로 계산)
 *   <li>엔티티는 {@code LocalDateTime} · {@code LocalDate}로 저장하고, 응답 DTO의 시각은 {@link #offset}으로 바꿔
 *       {@code 2026-11-23T14:30:00+09:00}처럼 내보낸다 (CONTRACTS 3장)
 * </ul>
 */
@Configuration
public class TimeConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    static {
        // JVM 기본 시간대. 테스트에서 main()을 거치지 않아도 이 설정이 읽히면 적용된다
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }

    @Bean
    Clock clock() {
        return Clock.system(ZONE);
    }

    /** 저장한 서울 시각을 응답용 오프셋 시각으로. null이면 null */
    public static OffsetDateTime offset(LocalDateTime seoulTime) {
        return seoulTime == null ? null : seoulTime.atZone(ZONE).toOffsetDateTime();
    }
}
