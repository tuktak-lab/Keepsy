package com.tuktaklab.keepsy.prediction;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 고객 카테고리별 예측 조회 (Keepsy #74 완료 조건 · 검증 방법, CONTRACTS 6장). 방문을 넣고 실제 재계산(#72)으로 예측을 만든 뒤 API로 읽는다.
 * 실제 MySQL, 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPredictionTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);

    @Autowired MockMvc mvc;
    @Autowired ShopSession shopSession;
    @Autowired PredictionAppService recalc;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel; // NAIL 21~28일
    private long lash; // LASH 30~60일
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        gel = data.menu(shop, 60);
        lash = menu("LASH", "NORMAL");
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 카테고리_두_개는_두_행이고_3회_이상은_평균과_방문_수_1회는_시술_평균_주기_기준() throws Exception {
        // NAIL 간격 28 · 28 → 마지막 방문 28일 전 + 25~31일 = 오늘이 기간 안 (주기 도래)
        visit(customer, TODAY.minusDays(84), gel);
        visit(customer, TODAY.minusDays(56), gel);
        visit(customer, TODAY.minusDays(28), gel);
        // LASH 1회 → 기본 주기 30~60일, 시작일이 아직 오지 않음 (예정)
        visit(customer, TODAY.minusDays(10), lash);
        recalc.recalculate(shop, customer);

        predictions(customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions.length()").value(2))
                .andExpect(jsonPath("$.predictions[0].category").value("NAIL"))
                .andExpect(jsonPath("$.predictions[0].status").value("DUE"))
                .andExpect(jsonPath("$.predictions[0].averageIntervalDays").value(28))
                .andExpect(jsonPath("$.predictions[0].basisVisitCount").value(3))
                .andExpect(jsonPath("$.predictions[0].defaultCycleBased").value(false))
                .andExpect(
                        jsonPath("$.predictions[0].lastServiceDate")
                                .value(TODAY.minusDays(28).toString()))
                .andExpect(
                        jsonPath("$.predictions[0].startDate").value(TODAY.minusDays(3).toString()))
                .andExpect(jsonPath("$.predictions[0].endDate").value(TODAY.plusDays(3).toString()))
                .andExpect(jsonPath("$.predictions[0].endSignal").value(false))
                .andExpect(jsonPath("$.predictions[1].category").value("LASH"))
                .andExpect(jsonPath("$.predictions[1].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.predictions[1].basisVisitCount").value(1))
                .andExpect(jsonPath("$.predictions[1].defaultCycleBased").value(true))
                .andExpect(jsonPath("$.predictions[1].averageIntervalDays").value(60))
                .andExpect(
                        jsonPath("$.predictions[1].startDate")
                                .value(TODAY.plusDays(20).toString()));
    }

    @Test
    void 방문_2회도_시술_평균_주기_기준() throws Exception {
        visit(customer, TODAY.minusDays(40), gel);
        visit(customer, TODAY.minusDays(15), gel);
        recalc.recalculate(shop, customer);

        predictions(customer)
                .andExpect(jsonPath("$.predictions[0].basisVisitCount").value(2))
                .andExpect(jsonPath("$.predictions[0].defaultCycleBased").value(true));
    }

    @Test
    void 방문이_모두_주기_계산_제외면_예측_없음() throws Exception {
        visit(customer, TODAY.minusDays(5), menu("NAIL", "EXCLUDE"));
        recalc.recalculate(shop, customer);

        predictions(customer)
                .andExpect(jsonPath("$.predictions.length()").value(1))
                .andExpect(jsonPath("$.predictions[0].status").value("NO_PREDICTION"))
                .andExpect(jsonPath("$.predictions[0].basisVisitCount").value(0))
                .andExpect(jsonPath("$.predictions[0].defaultCycleBased").value(false))
                .andExpect(jsonPath("$.predictions[0].startDate").value(nullValue()))
                .andExpect(jsonPath("$.predictions[0].averageIntervalDays").value(nullValue()));
    }

    @Test
    void 다음_예약에_그_카테고리가_있으면_상태는_null() throws Exception {
        visit(customer, TODAY.minusDays(10), lash);
        visit(customer, TODAY.minusDays(30), gel);
        recalc.recalculate(shop, customer);
        reserve(customer, lash, TODAY.atTime(23, 0));

        predictions(customer)
                .andExpect(jsonPath("$.predictions[0].category").value("NAIL"))
                .andExpect(jsonPath("$.predictions[0].status").isNotEmpty())
                .andExpect(jsonPath("$.predictions[1].category").value("LASH"))
                .andExpect(jsonPath("$.predictions[1].status").value(nullValue()));
    }

    @Test
    void 시작_시각이_지난_오늘_미완료_예약도_빼고_취소_노쇼가_되면_돌아오고_지난_예약은_보지_않는다() throws Exception {
        visit(customer, TODAY.minusDays(10), lash);
        recalc.recalculate(shop, customer);
        long today = reserve(customer, lash, TODAY.atStartOfDay());
        predictions(customer).andExpect(jsonPath("$.predictions[0].status").value(nullValue()));

        for (String s : List.of("CANCELED", "NO_SHOW")) {
            jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", s, today);
            predictions(customer).andExpect(jsonPath("$.predictions[0].status").value("SCHEDULED"));
        }

        // 마감 안 된 어제 확정 예약은 다음 예약이 아니다 (오늘 이후만, A11)
        reserve(customer, lash, TODAY.minusDays(1).atTime(10, 0));
        predictions(customer).andExpect(jsonPath("$.predictions[0].status").value("SCHEDULED"));
    }

    @Test
    void 방문이_없으면_빈_배열() throws Exception {
        predictions(customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions.length()").value(0));
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        long other = data.customer(data.shop(), false, null);
        predictions(other)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        predictions(customer).andExpect(status().isNotFound());
    }

    @Test
    void 인사이트를_PIN으로_잠그면_pin_required() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'INSIGHT' WHERE id = ?", shop);

        predictions(customer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private ResultActions predictions(long customerId) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(
                get("/api/customers/{customerId}/predictions", customerId).session(session));
    }

    private long menu(String category, String attribute) {
        return insert(
                "service_menu",
                "shop_id",
                shop,
                "category",
                category,
                "name",
                "모의 메뉴",
                "price",
                40000,
                "duration_minutes",
                60,
                "cycle_min_days",
                30,
                "cycle_max_days",
                60,
                "prediction_attribute",
                attribute);
    }

    private void visit(long customerId, LocalDate date, long menuId) {
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
                jdbc.queryForObject(
                        "SELECT category FROM service_menu WHERE id = ?", String.class, menuId),
                "service_menu_id",
                menuId,
                "staff_id",
                staff);
    }

    /** 확정 예약 30분. 오늘이면 아직 완료되지 않은 오늘 예약 */
    private long reserve(long customerId, long menuId, LocalDateTime startAt) {
        long reservationId =
                insert(
                        "reservation",
                        "shop_id",
                        shop,
                        "customer_id",
                        customerId,
                        "staff_id",
                        staff,
                        "start_at",
                        startAt,
                        "end_at",
                        startAt.plusMinutes(30),
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
                reservationId,
                "service_menu_id",
                menuId);
        return reservationId;
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
