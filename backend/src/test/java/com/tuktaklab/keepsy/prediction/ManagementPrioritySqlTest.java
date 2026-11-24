package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * {@link ManagementPrioritySql} 정렬 (Keepsy #76 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 이탈 신호 기간 상한은 매장
 * 기본값 120일이다.
 */
@SpringBootTest
class ManagementPrioritySqlTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);
    private static final LocalDateTime SIX_MONTHS_AGO = TODAY.minusMonths(6).atStartOfDay();

    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 경과_비율_높은_순_같으면_수신_동의_먼저_같으면_최근_6개월_결제_합계_순() {
        // 경과 비율 0.75로 같은 행: 동의 · 결제 합계로 가른다
        long g = row("NAIL", 21, 28);
        consent(g, true);
        pay(g, 50_000, SIX_MONTHS_AGO, false); // 6개월 경계 당일 00:00은 든다
        pay(g, 20_000, TODAY.minusDays(3).atTime(12, 0), false);
        pay(g, 900_000, SIX_MONTHS_AGO.minusDays(1), false); // 6개월 밖
        pay(g, 900_000, TODAY.minusDays(3).atTime(12, 0), true); // 취소 (F6)
        long f = row("NAIL", 21, 28);
        consent(f, true);
        pay(f, 60_000, TODAY.minusDays(10).atTime(12, 0), false);
        long j = row("NAIL", 21, 28);
        consent(j, true);
        pay(j, 900_000, SIX_MONTHS_AGO.minusDays(1), false);
        pay(j, 900_000, TODAY.minusDays(1).atTime(12, 0), true);
        long h = row("NAIL", 21, 28);
        consent(h, true);
        consent(h, false); // 최신 행이 철회
        pay(h, 999_999, TODAY.minusDays(1).atTime(12, 0), false);
        long e = row("NAIL", 21, 28);

        // 주기가 다른 시술도 경과 비율로 비교한다
        long d = row("LASH", 60, 300); // 평균 300일은 상한 120일로 잘려 0.5 (안 자르면 0.2)
        long a = row("NAIL", 10, 28); // 0.357
        long b = row("SEMI_PERMANENT", 30, 120); // 0.25: 지난 날은 더 많아도 아래
        long c = row("NAIL", -3, 28); // 주기 도래: 0 이하
        long i = customer();
        prediction(i, "ETC", null, null); // 예측 없음은 맨 뒤

        assertThat(order()).containsExactly(g, f, j, h, e, d, a, b, c, i);
    }

    private List<Long> order() {
        return named.queryForList(
                "SELECT c.id FROM customer_prediction cp"
                        + " JOIN customer c ON c.id = cp.customer_id"
                        + " JOIN shop s ON s.id = c.shop_id"
                        + " WHERE c.shop_id = :shopId ORDER BY "
                        + ManagementPrioritySql.ORDER_BY,
                Map.of("shopId", shop, "today", TODAY),
                Long.class);
    }

    /** 고객 하나와 예측 한 행. 종료일 = 오늘 − elapsedDays */
    private long row(String category, int elapsedDays, int avgIntervalDays) {
        long customer = customer();
        prediction(customer, category, TODAY.minusDays(elapsedDays), avgIntervalDays);
        return customer;
    }

    private long customer() {
        return data.customer(shop, false, null);
    }

    private void prediction(long customerId, String category, LocalDate end, Integer avg) {
        insert(
                "customer_prediction",
                "shop_id",
                shop,
                "customer_id",
                customerId,
                "category",
                category,
                "last_service_date",
                end == null ? null : end.minusDays(30),
                "avg_interval_days",
                avg,
                "start_date",
                end == null ? null : end.minusDays(6),
                "end_date",
                end);
    }

    private void consent(long customerId, boolean agreed) {
        insert(
                "consent",
                "shop_id",
                shop,
                "customer_id",
                customerId,
                "item",
                "MARKETING",
                "agreed",
                agreed,
                "recorded_at",
                LocalDateTime.now(TimeConfig.ZONE),
                "staff_id",
                staff,
                "method",
                "TABLET",
                "withdrawal_path",
                agreed ? null : "DIRECT");
    }

    private void pay(long customerId, int amount, LocalDateTime paidAt, boolean canceled) {
        long reservation =
                insert(
                        "reservation",
                        "shop_id",
                        shop,
                        "customer_id",
                        customerId,
                        "staff_id",
                        staff,
                        "start_at",
                        paidAt,
                        "end_at",
                        paidAt.plusHours(1),
                        "status",
                        "COMPLETED",
                        "channel",
                        "STORE",
                        "created_at",
                        paidAt);
        insert(
                "payment",
                "shop_id",
                shop,
                "customer_id",
                customerId,
                "reservation_id",
                reservation,
                "amount",
                amount,
                "method",
                "CARD",
                "paid_at",
                paidAt,
                "canceled",
                canceled);
    }

    /** 칼럼 · 값을 번갈아 받아 넣고 생성된 ID를 돌려준다 */
    private long insert(String table, Object... columnsAndValues) {
        Map<String, Object> row = new HashMap<>();
        for (int k = 0; k < columnsAndValues.length; k += 2) {
            row.put((String) columnsAndValues[k], columnsAndValues[k + 1]);
        }
        return new SimpleJdbcInsert(jdbc)
                .withTableName(table)
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(row)
                .longValue();
    }
}
