package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.Date;
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

/** 예측 스냅샷 · 적중 기록 (Keepsy #73 완료 조건, F23). 실제 MySQL에서 돌고 모의 데이터만 쓴다. */
@SpringBootTest
class PredictionSnapshotTest {

    /** 젤 21~28일: 방문 1회면 다음 기간은 +21 ~ +28일 */
    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    @Autowired PredictionAppService service;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel;
    private long retouch;
    private long removal;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        gel = data.menu(shop, 60);
        retouch = menu("EXCLUDE");
        removal = menu("END_SIGNAL");
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private List<Map<String, Object>> snapshots() {
        return jdbc.queryForList(
                "SELECT start_date, end_date, basis_visit_count, actual_visit_date, hit"
                        + " FROM prediction_snapshot WHERE customer_id = ? ORDER BY id",
                customer);
    }

    @Test
    void 첫_예측은_스냅샷을_만들고_같은_기준의_재계산은_새로_만들지_않는다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        service.recalculate(shop, customer);

        assertThat(snapshots()).hasSize(1);
        assertThat(snapshots().get(0))
                .containsEntry("start_date", Date.valueOf(D.plusDays(21)))
                .containsEntry("end_date", Date.valueOf(D.plusDays(28)))
                .containsEntry("basis_visit_count", 1)
                .containsEntry("hit", null);
    }

    @Test
    void 기간_안_다음_방문은_적중으로_기록하고_새_스냅샷을_만든다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        visit(D.plusDays(25), gel);
        service.recalculate(shop, customer);

        List<Map<String, Object>> rows = snapshots();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("actual_visit_date", Date.valueOf(D.plusDays(25)))
                .containsEntry("hit", true);
        assertThat(rows.get(1)).containsEntry("basis_visit_count", 2).containsEntry("hit", null);
    }

    @Test
    void 기간_밖_방문은_빗나감이고_주기_계산_제외_방문은_다음_방문으로_보지_않는다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        visit(D.plusDays(10), retouch); // 제외 방문: 기준이 바뀌지 않아 평가하지 않는다
        service.recalculate(shop, customer);
        assertThat(snapshots()).hasSize(1);
        assertThat(snapshots().get(0)).containsEntry("hit", null);

        visit(D.plusDays(40), gel);
        service.recalculate(shop, customer);

        assertThat(snapshots().get(0))
                .containsEntry("actual_visit_date", Date.valueOf(D.plusDays(40)))
                .containsEntry("hit", false);
    }

    @Test
    void 종료_신호_방문은_평가만_하고_새_스냅샷은_만들지_않는다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        visit(D.plusDays(22), removal);
        service.recalculate(shop, customer);

        List<Map<String, Object>> rows = snapshots();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("hit", true);
    }

    @Test
    void 고객을_삭제해도_스냅샷은_남는다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        jdbc.update(
                "UPDATE customer SET status = 'DELETED', name = NULL, phone = NULL WHERE id = ?",
                customer);

        assertThat(snapshots()).hasSize(1);
    }

    private long menu(String attribute) {
        return insert(
                "service_menu",
                "shop_id",
                shop,
                "category",
                "NAIL",
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

    private void visit(LocalDate date, long menuId) {
        long visitId =
                insert(
                        "visit",
                        "shop_id",
                        shop,
                        "customer_id",
                        customer,
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
                "NAIL",
                "service_menu_id",
                menuId,
                "staff_id",
                staff);
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
