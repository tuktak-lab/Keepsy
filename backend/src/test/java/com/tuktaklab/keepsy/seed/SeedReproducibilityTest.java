package com.tuktaklab.keepsy.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.seed.SeedGenerator.Options;
import com.tuktaklab.keepsy.seed.SeedGenerator.Report;
import com.tuktaklab.keepsy.seed.SeedGenerator.Scale;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 시드 생성기 (#335): 같은 seed면 같은 데이터. 테스트 DB에서 small 규모로 두 번 만들어 비교하고 지운다 */
@SpringBootTest
@Import(SeedConfig.class)
class SeedReproducibilityTest {

    @Autowired SeedGenerator generator;

    @Test
    void 같은_seed로_두_번_만들면_핵심_집계가_같고_C17_구성_요소가_들어_있다() {
        Options options = new Options(Scale.SMALL, 7L, LocalDate.of(2026, 6, 30), true);
        Report first = generator.run(options);
        Report second = generator.run(options); // 첫 번째 데모 매장 행만 지우고 다시 만든다
        try {
            Map<String, Object> a = second.aggregates();
            assertThat(a).isEqualTo(first.aggregates());

            assertThat((long) a.get("staff")).isEqualTo(5);
            assertThat((long) a.get("visitsWithoutCustomer")).isPositive();
            assertThat((long) a.get("campaigns")).isPositive();
            assertThat((long) a.get("controlTargets")).isPositive();
            assertThat((long) a.get("cycleExcluded")).isPositive();
            assertThat((String) a.get("visitServicesByCategory"))
                    .contains("NAIL=", "LASH=", "WAXING=", "SEMI_PERMANENT=");
            assertThat((String) a.get("reservationsByStatus"))
                    .contains("CANCELED=", "COMPLETED=", "CONFIRMED=", "NO_SHOW=");
            assertThat((String) a.get("reservationsByChannel"))
                    .contains("NAVER=", "KAKAO=", "PHONE=", "STORE=");
        } finally {
            generator.delete(second.shopId());
        }
    }
}
