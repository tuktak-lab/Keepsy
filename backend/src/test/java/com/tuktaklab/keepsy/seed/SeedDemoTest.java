package com.tuktaklab.keepsy.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.seed.SeedGenerator.Options;
import com.tuktaklab.keepsy.seed.SeedGenerator.Report;
import com.tuktaklab.keepsy.seed.SeedGenerator.Scale;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 데모 DB에 C17 시드를 적재한다 (#335). {@code ./gradlew seedDemo}로만 돈다(태그 seed는 기본 test에서 빠짐).
 *
 * <ul>
 *   <li>{@code seed.scale}: small | demo(기본) | perf
 *   <li>{@code seed.random}: 고정 난수 (기본 20261211)
 *   <li>{@code seed.until}: 기준일 = 시연일 (기본 오늘)
 *   <li>{@code seed.recreate}: true면 데모 매장 행만 지우고 다시 만든다
 * </ul>
 */
@Tag("seed")
@SpringBootTest
@Import(SeedConfig.class)
class SeedDemoTest {

    @Autowired SeedGenerator generator;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 데모_DB에_시드를_적재한다() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("일반 개발 DB에서는 실행하지 않는다. ./gradlew seedDemo로 데모 DB에 적재한다")
                .endsWith("_demo");
        Options options =
                new Options(
                        Scale.valueOf(
                                System.getProperty("seed.scale", "demo").toUpperCase(Locale.ROOT)),
                        Long.getLong("seed.random", 20261211L),
                        LocalDate.parse(
                                System.getProperty(
                                        "seed.until", LocalDate.now(TimeConfig.ZONE).toString())),
                        Boolean.getBoolean("seed.recreate"));

        Report report = generator.run(options);

        System.out.printf(
                "[seed] %s · 소요 %ds · %s%n",
                options, report.elapsed().toSeconds(), report.aggregates());
        if (options.scale() == Scale.PERF) {
            // C17: 고객 약 3천 · 방문 약 1.9만 (±5%)
            assertThat((long) report.aggregates().get("staff")).isEqualTo(5);
            assertThat((long) report.aggregates().get("customers")).isBetween(2850L, 3150L);
            assertThat((long) report.aggregates().get("visits")).isBetween(18050L, 19950L);
        }
    }
}
