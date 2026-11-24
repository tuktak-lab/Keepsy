package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.CategorySummary;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.Detail;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.MenuCount;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.PreferenceTag;
import com.tuktaklab.keepsy.customer.query.CustomerDetailQuery.VisitSummary;
import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** 고객 상세 · 방문 요약 (Keepsy #36 · #37 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. */
@SpringBootTest
class CustomerDetailTest {

    @Autowired CustomerService service;
    @Autowired JdbcTemplate jdbc;

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
    void 마스킹한_번호_주의사항_노쇼_횟수_예약_제한_취향을_담는다() {
        long customer = data.customer(shop, true, "노쇼 반복");
        jdbc.update(
                "UPDATE customer SET service_caution = '젤 리무버 주의', health_caution = '아세톤 알레르기',"
                        + " preference_memo = '조용한 시술 선호' WHERE id = ?",
                customer);
        reservation(customer, "NO_SHOW");
        reservation(customer, "NO_SHOW");
        reservation(customer, "CANCELED");
        tag(customer, "NAIL", "누드톤");
        tag(customer, "LASH", "C컬");
        tag(customer, "NAIL", "숏");

        Detail d = service.detail(shop, customer);

        assertThat(d.phone()).matches("010-\\*{4}-\\d{4}");
        assertThat(d.serviceCaution()).isEqualTo("젤 리무버 주의");
        assertThat(d.healthCaution()).isEqualTo("아세톤 알레르기");
        assertThat(d.noShowCount()).isEqualTo(2);
        assertThat(d.bookingRestricted()).isTrue();
        assertThat(d.bookingRestrictionReason()).isEqualTo("노쇼 반복");
        assertThat(d.preferenceTags())
                .containsExactly(
                        new PreferenceTag("LASH", "C컬"),
                        new PreferenceTag("NAIL", "누드톤"),
                        new PreferenceTag("NAIL", "숏"));
        assertThat(d.preferenceMemo()).isEqualTo("조용한 시술 선호");
    }

    @Test
    void 비어_있는_고객은_null과_0과_빈_배열() {
        Detail d = service.detail(shop, data.customer(shop, false, "남은 사유"));

        assertThat(d.serviceCaution()).isNull();
        assertThat(d.healthCaution()).isNull();
        assertThat(d.noShowCount()).isZero();
        assertThat(d.bookingRestricted()).isFalse();
        assertThat(d.bookingRestrictionReason()).isNull();
        assertThat(d.preferenceTags()).isEmpty();
        assertThat(d.gender()).isNull();
        assertThat(d.visitSummary())
                .isEqualTo(new VisitSummary(0, 0, null, null, List.of(), List.of()));
    }

    /**
     * Keepsy #37 완료 조건.
     *
     * <pre>
     * 08-01 가져온 방문: 네일(메뉴 없음)
     * 09-01 · 09-25 젤
     * 10-20 젤 + 속눈썹 연장 (방문 1건 · 시술 2건, C6)
     * 11-01 속눈썹 연장
     * 11-10 젤 (주기 계산 제외)
     * 예측: 네일 근거 방문 4회 · 평균 27일, 속눈썹 근거 방문 2회 · 평균(기본 주기 최대) 42일
     * </pre>
     */
    @Test
    void 방문_요약은_제외_방문과_가져온_방문을_세고_평균_간격은_근거_3회_이상인_예측_값() {
        long customer = data.customer(shop, false, null);
        long gel = data.menu(shop, 60);
        long lash =
                insert(
                        "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                                + " cycle_min_days, cycle_max_days) VALUES (?, 'LASH', '연장', 60000, 90, 28, 42)",
                        shop);
        visit(customer, "2026-08-01", "IMPORT", new Object[] {null, "NAIL", false});
        visit(customer, "2026-09-01", "NORMAL", new Object[] {gel, "NAIL", false});
        visit(customer, "2026-09-25", "NORMAL", new Object[] {gel, "NAIL", false});
        visit(
                customer,
                "2026-10-20",
                "NORMAL",
                new Object[] {gel, "NAIL", false},
                new Object[] {lash, "LASH", false});
        visit(customer, "2026-11-01", "NORMAL", new Object[] {lash, "LASH", false});
        visit(customer, "2026-11-10", "NORMAL", new Object[] {gel, "NAIL", true});
        prediction(customer, "NAIL", 4, 27);
        prediction(customer, "LASH", 2, 42);

        VisitSummary s = service.detail(shop, customer).visitSummary();

        assertThat(s.visitCount()).isEqualTo(6);
        assertThat(s.serviceCount()).isEqualTo(7);
        assertThat(s.firstVisitDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(s.lastVisitDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(s.categories())
                .containsExactly(
                        new CategorySummary("NAIL", 5, 27), new CategorySummary("LASH", 2, null));
        assertThat(s.menus())
                .containsExactly(
                        new MenuCount(gel, "젤", "NAIL", 4),
                        new MenuCount(lash, "연장", "LASH", 2),
                        new MenuCount(null, null, "NAIL", 1));
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() {
        long customer = data.customer(shop, false, null);
        long otherShop = data.shop();
        assertNotFound(otherShop, customer);

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        assertNotFound(shop, customer);
    }

    private void assertNotFound(long shopId, long customerId) {
        assertThatThrownBy(() -> service.detail(shopId, customerId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private void reservation(long customer, String status) {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 10, 0);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'STORE', ?)",
                shop,
                customer,
                staff,
                start,
                start.plusHours(1),
                status,
                start.minusDays(1));
    }

    /**
     * @param services 시술마다 {메뉴 ID(가져온 방문이면 null), 카테고리, 주기 계산 제외}
     */
    private void visit(long customer, String date, String source, Object[]... services) {
        long visit =
                insert(
                        "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, ?)",
                        shop,
                        customer,
                        LocalDate.parse(date),
                        source);
        for (Object[] s : services) {
            jdbc.update(
                    "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category,"
                            + " cycle_excluded) VALUES (?, ?, ?, ?, ?)",
                    shop,
                    visit,
                    s[0],
                    s[1],
                    s[2]);
        }
    }

    private void prediction(long customer, String category, int basisVisits, int avgInterval) {
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category, basis_visit_count,"
                        + " avg_interval_days) VALUES (?, ?, ?, ?, ?)",
                shop,
                customer,
                category,
                basisVisits,
                avgInterval);
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }

    private void tag(long customer, String category, String tag) {
        jdbc.update(
                "INSERT INTO preference_tag (shop_id, customer_id, category, tag) VALUES (?, ?, ?, ?)",
                shop,
                customer,
                category,
                tag);
    }
}
