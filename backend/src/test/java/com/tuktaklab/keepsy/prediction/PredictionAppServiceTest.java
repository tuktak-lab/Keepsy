package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
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
 * 예측 저장 · 재계산 (Keepsy #72 완료 조건). 계산 규칙 자체는 {@link NextVisitCalculatorTest}가 맡고, 여기서는 방문을 모아 저장하는
 * 흐름을 실제 MySQL에서 확인한다. 모의 데이터만 쓴다.
 */
@SpringBootTest
class PredictionAppServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    @Autowired PredictionAppService service;
    @Autowired CustomerPredictionRepository predictions;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel; // NAIL 21~28일 일반
    private long retouch; // NAIL 주기 계산 제외
    private long removal; // NAIL 종료 신호
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        gel = data.menu(shop, 60);
        retouch = menu("NAIL", "EXCLUDE");
        removal = menu("NAIL", "END_SIGNAL");
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 일반_방문_3회는_간격_평균과_범위로_저장한다() {
        visit(customer, D, "NAIL", gel);
        visit(customer, D.plusDays(28), "NAIL", gel);
        visit(customer, D.plusDays(56), "NAIL", gel);

        service.recalculate(shop, customer);

        CustomerPrediction p = only(customer);
        assertThat(p.getCategory()).isEqualTo("NAIL");
        assertThat(p.getBasisVisitCount()).isEqualTo(3);
        assertThat(p.getAvgIntervalDays()).isEqualTo(28);
        assertThat(p.getLastServiceDate()).isEqualTo(D.plusDays(56));
        // 간격 28 · 28 → 평균 28 ± 표준편차 0 → 최소 ±3일 (FR-PRD-01)
        assertThat(p.getStartDate()).isEqualTo(D.plusDays(56 + 25));
        assertThat(p.getEndDate()).isEqualTo(D.plusDays(56 + 31));
        assertThat(p.isEndSignal()).isFalse();
    }

    @Test
    void 방문이_모두_주기_계산_제외면_행은_남기고_예측_없음() {
        visit(customer, D, "NAIL", retouch);

        service.recalculate(shop, customer);

        CustomerPrediction p = only(customer);
        assertThat(p.prediction()).isEmpty();
        assertThat(p.getBasisVisitCount()).isZero();
        assertThat(p.getStartDate()).isNull();
    }

    @Test
    void 종료_신호_방문_뒤에는_그날이_시작일이자_종료일() {
        visit(customer, D, "NAIL", gel);
        visit(customer, D.plusDays(30), "NAIL", removal);

        service.recalculate(shop, customer);

        CustomerPrediction p = only(customer);
        assertThat(p.isEndSignal()).isTrue();
        assertThat(p.getStartDate()).isEqualTo(D.plusDays(30));
        assertThat(p.getEndDate()).isEqualTo(D.plusDays(30));
    }

    @Test
    void 메뉴_없는_가져온_방문은_카테고리_기본_주기() {
        visit(customer, D, "LASH", null); // 모의 매장 LASH 기본 주기 30~60일

        service.recalculate(shop, customer);

        CustomerPrediction p = only(customer);
        assertThat(p.getStartDate()).isEqualTo(D.plusDays(30));
        assertThat(p.getEndDate()).isEqualTo(D.plusDays(60));
    }

    @Test
    void 재계산은_같은_행을_갱신하고_처리_표시는_건드리지_않는다() {
        visit(customer, D, "NAIL", gel);
        service.recalculate(shop, customer);
        long id = only(customer).getId();
        jdbc.update(
                "UPDATE customer_prediction SET handling_mark = 'CONTACTED', handling_base_visit_date = ? WHERE id = ?",
                D,
                id);

        // 기준일 전 과거 방문: 근거는 늘지만 마지막 시술일은 그대로라 F2상 해제되지 않는다
        // (기준일 뒤 방문은 해제된다. HandlingMarkTest)
        visit(customer, D.minusDays(25), "NAIL", gel);
        service.recalculate(shop, customer);

        CustomerPrediction p = only(customer);
        assertThat(p.getId()).isEqualTo(id);
        assertThat(p.getBasisVisitCount()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT handling_mark FROM customer_prediction WHERE id = ?",
                                String.class,
                                id))
                .isEqualTo("CONTACTED");
    }

    @Test
    void 카테고리_방문이_모두_지워지면_그_행을_지운다() {
        long visitId = visit(customer, D, "NAIL", gel);
        visit(customer, D, "LASH", null);
        service.recalculate(shop, customer);
        assertThat(predictions.findByShopIdAndCustomerIdIn(shop, List.of(customer))).hasSize(2);

        jdbc.update("DELETE FROM visit_service WHERE visit_id = ? AND category = 'NAIL'", visitId);
        service.recalculate(shop, customer);

        assertThat(predictions.findByShopIdAndCustomerIdIn(shop, List.of(customer)))
                .extracting(CustomerPrediction::getCategory)
                .containsExactly("LASH");
    }

    @Test
    void 메뉴_예측_속성을_바꾸면_그_메뉴를_받은_고객을_다시_계산한다() {
        long other = data.customer(shop, false, null);
        visit(customer, D, "NAIL", gel);
        visit(other, D, "NAIL", gel);
        service.recalculate(shop, List.of(customer, other));
        assertThat(only(customer).prediction()).isPresent();

        jdbc.update("UPDATE service_menu SET prediction_attribute = 'EXCLUDE' WHERE id = ?", gel);
        service.recalculateForMenu(shop, gel);

        assertThat(only(customer).prediction()).isEmpty();
        assertThat(only(other).prediction()).isEmpty();
    }

    @Test
    void 다른_매장의_방문은_읽지_않는다() {
        long otherShop = data.shop();
        long stranger = data.customer(otherShop, false, null);
        visit(stranger, D, "NAIL", gel);

        service.recalculate(shop, stranger);

        assertThat(predictions.findByShopIdAndCustomerIdIn(shop, List.of(stranger))).isEmpty();
    }

    private CustomerPrediction only(long customerId) {
        List<CustomerPrediction> rows =
                predictions.findByShopIdAndCustomerIdIn(shop, List.of(customerId));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long menu(String category, String attribute) {
        return insert(
                "service_menu",
                "shop_id",
                shop,
                "category",
                category,
                "name",
                "모의 " + attribute,
                "price",
                10000,
                "duration_minutes",
                30,
                "cycle_min_days",
                21,
                "cycle_max_days",
                28,
                "prediction_attribute",
                attribute);
    }

    /** 방문 하나(같은 고객 · 같은 날이면 기존 방문)에 시술 하나. menuId가 null이면 메뉴 없는 가져온 방문 */
    private long visit(long customerId, LocalDate date, String category, Long menuId) {
        List<Long> found =
                jdbc.queryForList(
                        "SELECT id FROM visit WHERE customer_id = ? AND visit_date = ?",
                        Long.class,
                        customerId,
                        date);
        long visitId =
                found.isEmpty()
                        ? insert(
                                "visit",
                                "shop_id",
                                jdbc.queryForObject(
                                        "SELECT shop_id FROM customer WHERE id = ?",
                                        Long.class,
                                        customerId),
                                "customer_id",
                                customerId,
                                "visit_date",
                                date,
                                "source",
                                menuId == null ? "IMPORT" : "NORMAL")
                        : found.get(0);
        insert(
                "visit_service",
                "shop_id",
                jdbc.queryForObject("SELECT shop_id FROM visit WHERE id = ?", Long.class, visitId),
                "visit_id",
                visitId,
                "category",
                category,
                "service_menu_id",
                menuId,
                "staff_id",
                menuId == null ? null : staff);
        return visitId;
    }

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
