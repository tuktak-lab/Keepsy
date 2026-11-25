package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuktaklab.keepsy.analytics.query.PredictionAccuracyQuery;
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
    @Autowired PredictionAccuracyQuery accuracy;

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
    void 이탈_신호_기간은_그때의_평균_간격과_매장_상한_중_작은_값으로_남긴다() {
        visit(D, gel);
        service.recalculate(shop, customer); // 방문 1회: 평균 간격 = 기본 주기 최대 28일, 상한 120일
        assertThat(churnSignalDays()).isEqualTo(28);

        jdbc.update("UPDATE shop SET churn_signal_cap_days = 10 WHERE id = ?", shop);
        service.recalculate(shop, customer); // 같은 기준으로 고쳐 쓸 때 다시 채운다 (F23, F57)
        assertThat(churnSignalDays()).isEqualTo(10);
    }

    private Integer churnSignalDays() {
        return jdbc.queryForObject(
                "SELECT churn_signal_days FROM prediction_snapshot WHERE customer_id = ?",
                Integer.class,
                customer);
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

    // 결정 F62 (Keepsy #339): 방문 삭제 · 수정으로 기준 방문일이 앞당겨지면 평가 전 최신 스냅샷은 지우고, 지운 방문으로 평가한 스냅샷은
    // 평가를 되돌린다. 그 밖의 평가된 스냅샷은 그대로 둔다

    @Test
    void 최신_방문을_지우면_평가_전_스냅샷은_지우고_그_방문으로_평가한_스냅샷은_되돌린다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        long b = visit(D.plusDays(25), gel);
        service.recalculate(shop, customer);

        deleteVisit(b);
        service.recalculate(shop, customer);

        List<Map<String, Object>> rows = snapshots();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("start_date", Date.valueOf(D.plusDays(21)))
                .containsEntry("basis_visit_count", 1)
                .containsEntry("actual_visit_date", null)
                .containsEntry("hit", null);
        // 정확도 분모도 기준 방문 A 하나 (F29: 다음 방문 없이 끝난 스냅샷은 빗나감)
        PredictionAccuracyQuery.Accuracy a = accuracy.find(shop, D, D.plusYears(1), D.plusYears(1));
        assertThat(a.evaluatedCount()).isEqualTo(1);
        assertThat(a.hitCount()).isZero();

        // 다시 방문하면 되돌린 스냅샷을 평가하고 새로 만든다 (F23 그대로)
        visit(D.plusDays(26), gel);
        service.recalculate(shop, customer);
        assertThat(snapshots()).hasSize(2);
        assertThat(snapshots().get(0))
                .containsEntry("actual_visit_date", Date.valueOf(D.plusDays(26)))
                .containsEntry("hit", true);
    }

    @Test
    void 과거_방문을_지워도_기준일이_같으면_평가된_스냅샷은_그대로_둔다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        long b = visit(D.plusDays(25), gel);
        service.recalculate(shop, customer);
        visit(D.plusDays(50), gel);
        service.recalculate(shop, customer);

        deleteVisit(b);
        service.recalculate(shop, customer);

        List<Map<String, Object>> rows = snapshots();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0))
                .containsEntry("actual_visit_date", Date.valueOf(D.plusDays(25)))
                .containsEntry("hit", true);
        assertThat(rows.get(1)).containsEntry("actual_visit_date", Date.valueOf(D.plusDays(50)));
        assertThat(rows.get(2)).containsEntry("basis_visit_count", 2).containsEntry("hit", null);
    }

    @Test
    void 같은_날_시술을_고쳐_기준일이_앞당겨져도_같게_처리한다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        long b = visit(D.plusDays(25), gel);
        insert( // 같은 날 리터치(주기 계산 제외)도 받음 (C6)
                "visit_service",
                "shop_id",
                shop,
                "visit_id",
                b,
                "category",
                "NAIL",
                "service_menu_id",
                retouch,
                "staff_id",
                staff);
        service.recalculate(shop, customer);

        // 방문 수정 (FR-VIS-03): 젤을 빼고 리터치만 남기면 B는 유효 방문이 아니다
        jdbc.update("DELETE FROM visit_service WHERE visit_id = ? AND service_menu_id = ?", b, gel);
        service.recalculate(shop, customer);

        List<Map<String, Object>> rows = snapshots();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("basis_visit_count", 1)
                .containsEntry("actual_visit_date", null)
                .containsEntry("hit", null);
    }

    @Test
    void 시술_카테고리를_바꿔_기준일이_앞당겨지면_그_카테고리만_되돌린다() {
        visit(D, gel);
        service.recalculate(shop, customer);
        long b = visit(D.plusDays(25), gel);
        service.recalculate(shop, customer);

        jdbc.update("UPDATE visit_service SET category = 'LASH' WHERE visit_id = ?", b);
        service.recalculate(shop, customer);

        List<Map<String, Object>> nail =
                jdbc.queryForList(
                        "SELECT actual_visit_date, hit FROM prediction_snapshot"
                                + " WHERE customer_id = ? AND category = 'NAIL'",
                        customer);
        assertThat(nail).hasSize(1);
        assertThat(nail.get(0)).containsEntry("actual_visit_date", null).containsEntry("hit", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM prediction_snapshot"
                                        + " WHERE customer_id = ? AND category = 'LASH'",
                                Integer.class,
                                customer))
                .isEqualTo(1);
    }

    @Test
    void 하나뿐인_방문을_지우면_평가_전_스냅샷도_지운다() {
        long a = visit(D, gel);
        service.recalculate(shop, customer);

        deleteVisit(a);
        service.recalculate(shop, customer);

        assertThat(snapshots()).isEmpty();
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

    /** 방문 삭제 (F8): 시술 행과 방문 행을 지운 뒤 재계산은 각 테스트가 부른다 */
    private void deleteVisit(long visitId) {
        jdbc.update("DELETE FROM visit_service WHERE visit_id = ?", visitId);
        jdbc.update("DELETE FROM visit WHERE id = ?", visitId);
    }

    private long visit(LocalDate date, long menuId) {
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
