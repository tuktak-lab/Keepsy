package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 처리 표시 (Keepsy #78 완료 조건 · 검증 방법, FR-PRD-07, 결정 F2 · F55). 실제 MySQL에서 돌고 모의 데이터만 쓴다.
 *
 * <pre>
 * a · b: 네일 방문 2회(마지막 = base), 예측 있음. 속눈썹은 방문 없음(예측 없음)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HandlingMarkTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PredictionAppService predictions;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long staff;
    private long menu;
    private long a;
    private long b;
    private LocalDate base;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        menu = data.menu(shop, 60); // NAIL 21~28일
        base = LocalDate.now(clock).minusDays(40);
        a = data.customer(shop, false, null);
        b = data.customer(shop, false, null);
        for (long c : List.of(a, b)) {
            visit(c, base.minusDays(25), "NAIL", false);
            visit(c, base, "NAIL", false);
            predictions.recalculate(shop, c);
        }
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 표시하면_시각과_기준일을_쓰고_연락함은_인사이트에_보이고_관리_종료는_숨기고_지우면_다시_보인다() throws Exception {
        mark(a, "NAIL", "{\"mark\":\"CONTACTED\"}").andExpect(status().isNoContent());
        assertThat(handling(a, "NAIL"))
                .containsEntry("handling_mark", "CONTACTED")
                .containsEntry("handling_base_visit_date", java.sql.Date.valueOf(base));
        assertThat(handling(a, "NAIL").get("handling_marked_at")).isNotNull();
        insights()
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(
                        jsonPath("$.items[?(@.customerId == %d)].handlingMark".formatted(a))
                                .value(contains("CONTACTED")))
                .andExpect(
                        jsonPath("$.items[?(@.customerId == %d)].handlingMarkedAt".formatted(a))
                                .isNotEmpty());

        mark(a, "NAIL", "{\"mark\":\"CLOSED\"}").andExpect(status().isNoContent());
        // 페이지 · 전체 건수도 뺀 뒤 기준
        insights()
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[*].customerId").value(contains((int) b)));
        // 고객 상세(고객별 예측)에는 관리 종료도 나온다
        mvc.perform(request(get("/api/customers/" + a + "/predictions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].category").value("NAIL"))
                .andExpect(jsonPath("$.predictions[0].handlingMark").value("CLOSED"))
                .andExpect(jsonPath("$.predictions[0].handlingMarkedAt").isNotEmpty());

        mark(a, "NAIL", "{}").andExpect(status().isNoContent());
        assertThat(handling(a, "NAIL").values()).containsOnlyNulls();
        insights().andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(request(get("/api/customers/" + a + "/predictions")))
                .andExpect(jsonPath("$.predictions[0].handlingMark").doesNotExist());
    }

    @Test
    void 기준일_뒤_유효_방문만_자동_해제한다() throws Exception {
        mark(a, "NAIL", "{\"mark\":\"CLOSED\"}").andExpect(status().isNoContent());

        // 예측만 다시 계산
        predictions.recalculateForMenu(shop, menu);
        // 기준일 전 과거 방문을 나중에 등록
        visit(a, base.minusDays(10), "NAIL", false);
        // 주기 계산 제외 방문
        visit(a, base.plusDays(5), "NAIL", true);
        // 다른 카테고리 방문
        visit(a, base.plusDays(7), "LASH", false);
        // 같은 날 방문 (C6: 그날 방문에 시술이 더해짐)
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, category) SELECT ?, id, 'NAIL'"
                        + " FROM visit WHERE customer_id = ? AND visit_date = ?",
                shop,
                a,
                base);
        predictions.recalculate(shop, a);
        assertThat(handling(a, "NAIL")).containsEntry("handling_mark", "CLOSED");

        // 기준일 뒤 일반 방문 → 해제
        visit(a, base.plusDays(10), "NAIL", false);
        predictions.recalculate(shop, a);
        assertThat(handling(a, "NAIL").values()).containsOnlyNulls();
        insights().andExpect(jsonPath("$.totalElements").value(3)); // a 네일 · a 속눈썹 · b 네일
    }

    @Test
    void 예측_없는_카테고리_다른_매장_삭제된_고객은_not_found이고_값이_틀리면_invalid() throws Exception {
        mark(a, "LASH", "{\"mark\":\"CONTACTED\"}").andExpect(status().isNotFound());
        long other = data.customer(data.shop(), false, null);
        mark(other, "NAIL", "{\"mark\":\"CONTACTED\"}").andExpect(status().isNotFound());
        mark(a, "FOO", "{\"mark\":\"CONTACTED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mark(a, "NAIL", "{\"mark\":\"DONE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", a);
        mark(a, "NAIL", "{\"mark\":\"CONTACTED\"}").andExpect(status().isNotFound());
    }

    @Test
    void 관리_종료여도_캠페인_대상_고르기에는_영향이_없다() throws Exception {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method)"
                        + " VALUES (?, ?, 'MARKETING', TRUE, ?, ?, 'TABLET')",
                shop,
                a,
                LocalDateTime.now(clock),
                staff);
        mark(a, "NAIL", "{\"mark\":\"CLOSED\"}").andExpect(status().isNoContent());

        MockHttpSession session = session();
        shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        mvc.perform(
                        post("/api/campaigns/targets")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"category\":\"NAIL\",\"customerIds\":[%d]}"
                                                .formatted(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCount").value(1));
    }

    private void visit(long customerId, LocalDate date, String category, boolean excluded) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                date);
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category, cycle_excluded)"
                        + " SELECT ?, id, ?, ?, ? FROM visit WHERE customer_id = ? AND visit_date = ?",
                shop,
                category.equals("NAIL") ? menu : null,
                category,
                excluded,
                customerId,
                date);
    }

    private Map<String, Object> handling(long customerId, String category) {
        return new HashMap<>(
                jdbc.queryForMap(
                        "SELECT handling_mark, handling_marked_at, handling_base_visit_date"
                                + " FROM customer_prediction WHERE customer_id = ? AND category = ?",
                        customerId,
                        category));
    }

    private ResultActions mark(long customerId, String category, String body) throws Exception {
        return mvc.perform(
                request(
                                put(
                                        "/api/customers/%d/predictions/%s/handling"
                                                .formatted(customerId, category)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions insights() throws Exception {
        return mvc.perform(request(get("/api/insights")));
    }

    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder r) {
        return r.session(session()).with(csrf());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return session;
    }
}
