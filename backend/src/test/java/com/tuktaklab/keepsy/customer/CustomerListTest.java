package com.tuktaklab.keepsy.customer;

import static com.tuktaklab.keepsy.prediction.PredictionStatus.CHURN_SIGNAL;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.DUE;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.LONG_ABSENT;
import static com.tuktaklab.keepsy.prediction.PredictionStatus.NO_PREDICTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import com.tuktaklab.keepsy.customer.query.CustomerListQuery.Filter;
import com.tuktaklab.keepsy.prediction.PredictionStatus;
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
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 고객 목록 · 검색 · 필터 (Keepsy #35 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 이름 · 가짜 번호만 쓴다.
 *
 * <pre>
 * 김민지 010-0000-1234: 방문 2회(예전 NAIL 스탭1, 최근 LASH 스탭2), 마케팅 동의 후 철회. 예측 NAIL 주기 도래, LASH 이탈 신호
 * 이서연 010-0000-5678: 방문 1회(NAIL 스탭1), 마케팅 동의. 예측 NAIL 장기 미방문, LASH 주기 도래지만 내일 LASH 예약이 있어 빠짐
 * 박민수 010-0000-9999: 방문 없음, 동의 없음. 예측 ETC 예측 없음
 * 삭제된 고객 1명, 다른 매장의 김민지 010-0000-1234
 * </pre>
 */
@SpringBootTest
class CustomerListTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);
    private static final Filter NONE = filter(null, null, null, null, null, null);

    @Autowired CustomerService service;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff1;
    private long staff2;
    private long minji;
    private long seoyeon;
    private long minsu;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff1 = data.staff(shop, "ACTIVE", true);
        staff2 = data.staff(shop, "ACTIVE", false);
        long lashMenu =
                insert(
                        "service_menu",
                        "shop_id",
                        shop,
                        "category",
                        "LASH",
                        "name",
                        "속눈썹",
                        "price",
                        40000,
                        "duration_minutes",
                        60,
                        "cycle_min_days",
                        30,
                        "cycle_max_days",
                        60);

        minji = customer(shop, "김민지", "010-0000-1234", "ACTIVE");
        seoyeon = customer(shop, "이서연", "010-0000-5678", "ACTIVE");
        minsu = customer(shop, "박민수", "010-0000-9999", "ACTIVE");
        customer(shop, null, null, "DELETED");
        long otherShop = data.shop();
        customer(otherShop, "김민지", "010-0000-1234", "ACTIVE");

        visit(minji, TODAY.minusDays(60), "NAIL", staff1);
        visit(minji, TODAY.minusDays(20), "LASH", staff2);
        visit(seoyeon, TODAY.minusDays(70), "NAIL", staff1);

        consent(minji, true);
        consent(minji, false);
        consent(seoyeon, true);

        prediction(minji, "NAIL", TODAY.minusDays(5), TODAY.plusDays(2), 28);
        prediction(minji, "LASH", TODAY.minusDays(15), TODAY.minusDays(10), 30);
        prediction(seoyeon, "NAIL", TODAY.minusDays(40), TODAY.minusDays(35), 7);
        prediction(seoyeon, "LASH", TODAY.minusDays(1), TODAY.plusDays(5), 30);
        prediction(minsu, "ETC", null, null, null);

        long tomorrow =
                insert(
                        "reservation",
                        "shop_id",
                        shop,
                        "customer_id",
                        seoyeon,
                        "staff_id",
                        staff1,
                        "start_at",
                        TODAY.plusDays(1).atTime(10, 0),
                        "end_at",
                        TODAY.plusDays(1).atTime(11, 0),
                        "status",
                        "CONFIRMED",
                        "channel",
                        "STORE",
                        "created_at",
                        LocalDateTime.now(TimeConfig.ZONE));
        insert(
                "reservation_service",
                "shop_id",
                shop,
                "reservation_id",
                tomorrow,
                "service_menu_id",
                lashMenu);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 검색은_이름_포함_또는_뒷자리_4자리이고_번호는_마스킹() {
        assertThat(ids(list("민지", NONE))).containsExactly(minji);
        assertThat(ids(list("1234", NONE))).containsExactly(minji);
        assertThat(list("1234", NONE).items().get(0).phone()).isEqualTo("010-****-1234");
    }

    @Test
    void 삭제된_고객과_다른_매장_고객은_빠지고_최근_등록순() {
        assertThat(ids(list(null, NONE))).containsExactly(minsu, seoyeon, minji);
    }

    @Test
    void 방문_횟수_범위와_카테고리() {
        assertThat(ids(list(null, filter(1, null, null, null, null, null))))
                .containsExactly(seoyeon, minji);
        assertThat(ids(list(null, filter(null, 0, null, null, null, null)))).containsExactly(minsu);
        assertThat(ids(list(null, filter(2, 2, null, null, null, null)))).containsExactly(minji);
        assertThat(ids(list(null, filter(null, null, "NAIL", null, null, null))))
                .containsExactly(seoyeon, minji);
        assertThat(ids(list(null, filter(null, null, "LASH", null, null, null))))
                .containsExactly(minji);
    }

    @Test
    void 담당_스탭은_가장_최근_방문만_본다() {
        assertThat(ids(list(null, filter(null, null, null, null, staff1, null))))
                .containsExactly(seoyeon);
        assertThat(ids(list(null, filter(null, null, null, null, staff2, null))))
                .containsExactly(minji);
    }

    @Test
    void 마케팅_동의는_최신_행_기준() {
        assertThat(ids(list(null, filter(null, null, null, null, null, true))))
                .containsExactly(seoyeon);
        assertThat(ids(list(null, filter(null, null, null, null, null, false))))
                .containsExactly(minsu, minji);
        assertThat(list("이서연", NONE).items().get(0).marketingConsent()).isTrue();
    }

    @Test
    void 예측_상태는_오늘_기준이고_다음_예약이_있는_카테고리는_빠진다() {
        assertThat(ids(list(null, status(DUE, null)))).containsExactly(minji);
        assertThat(ids(list(null, status(CHURN_SIGNAL, null)))).containsExactly(minji);
        assertThat(ids(list(null, status(LONG_ABSENT, null)))).containsExactly(seoyeon);
        assertThat(ids(list(null, status(NO_PREDICTION, null)))).containsExactly(minsu);
        assertThat(ids(list(null, status(DUE, "NAIL")))).containsExactly(minji);
        assertThat(ids(list(null, status(DUE, "LASH")))).isEmpty();
    }

    @Test
    void 페이지는_요청한_크기로_자르고_전체_건수를_준다() {
        for (int i = 0; i < 23; i++) {
            customer(shop, "모의" + i, "010-1111-" + String.format("%04d", i), "ACTIVE");
        }
        PageResponse<CustomerService.ListItem> first = service.list(shop, null, NONE, 0, 20);
        PageResponse<CustomerService.ListItem> second = service.list(shop, null, NONE, 1, 20);

        assertThat(first.items()).hasSize(20);
        assertThat(first.totalElements()).isEqualTo(26);
        assertThat(second.items()).hasSize(6);
    }

    @Test
    void 방문_횟수_범위가_뒤집히면_invalid() {
        assertThatThrownBy(() -> list(null, filter(3, 1, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID);
    }

    private PageResponse<CustomerService.ListItem> list(String q, Filter f) {
        return service.list(shop, q, f, 0, 20);
    }

    private static List<Long> ids(PageResponse<CustomerService.ListItem> page) {
        return page.items().stream().map(CustomerService.ListItem::customerId).toList();
    }

    private static Filter filter(
            Integer min,
            Integer max,
            String category,
            PredictionStatus status,
            Long staff,
            Boolean consent) {
        return new Filter(null, null, min, max, category, status, staff, consent);
    }

    private static Filter status(PredictionStatus s, String category) {
        return filter(null, null, category, s, null, null);
    }

    private long customer(long shopId, String name, String phone, String status) {
        return insert(
                "customer",
                "shop_id",
                shopId,
                "name",
                name,
                "phone",
                phone,
                "status",
                status,
                "registered_at",
                LocalDateTime.now(TimeConfig.ZONE));
    }

    private void visit(long customerId, LocalDate date, String category, long staffId) {
        long visitId =
                insert(
                        "visit",
                        "shop_id",
                        shop,
                        "customer_id",
                        customerId,
                        "visit_date",
                        date,
                        "source",
                        "NORMAL");
        insert(
                "visit_service",
                "shop_id",
                shop,
                "visit_id",
                visitId,
                "category",
                category,
                "staff_id",
                staffId);
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
                staff1,
                "method",
                "TABLET");
    }

    private void prediction(
            long customerId, String category, LocalDate start, LocalDate end, Integer avg) {
        insert(
                "customer_prediction",
                "shop_id",
                shop,
                "customer_id",
                customerId,
                "category",
                category,
                "last_service_date",
                start == null ? null : start.minusDays(20),
                "avg_interval_days",
                avg,
                "start_date",
                start,
                "end_date",
                end);
    }

    /** 칼럼 · 값을 번갈아 받아 넣고 생성된 ID를 돌려준다 */
    private long insert(String table, Object... columnsAndValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            row.put((String) columnsAndValues[i], columnsAndValues[i + 1]);
        }
        return new SimpleJdbcInsert(jdbc)
                .withTableName(table)
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(row)
                .longValue();
    }
}
