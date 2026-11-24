package com.tuktaklab.keepsy.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.prediction.PredictionAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/**
 * POST · PATCH /api/service-menus 시술 메뉴 추가 · 수정 (Keepsy #29, CONTRACTS 6장 SET, FR-SET-02 ·
 * FR-VIS-05, F31). 실제 MySQL에서 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceMenuWriteTest {

    private static final String NEW_MENU =
            "{\"category\":\"LASH\",\"name\":\" 속눈썹 연장 \",\"price\":60000,"
                    + "\"durationMinutes\":%d,\"cycleMinDays\":30,\"cycleMaxDays\":60}";

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 1);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PredictionAppService predictions;

    private TestShopData data;
    private long shop;
    private long owner;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private MockHttpSession session(boolean ownerPin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, owner);
        if (ownerPin) {
            shopSession.setPin(session, owner, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private ResultActions postMenu(String body) throws Exception {
        return mvc.perform(
                post("/api/service-menus")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions patchMenu(long menuId, String body) throws Exception {
        return mvc.perform(
                patch("/api/service-menus/{menuId}", menuId)
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void 추가하면_201과_id이고_예측_속성은_기본_NORMAL() throws Exception {
        postMenu(NEW_MENU.formatted(90))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM service_menu WHERE shop_id = ?", shop);
        assertThat(row)
                .containsEntry("category", "LASH")
                .containsEntry("name", "속눈썹 연장")
                .containsEntry("price", 60000)
                .containsEntry("duration_minutes", 90)
                .containsEntry("cycle_min_days", 30)
                .containsEntry("cycle_max_days", 60)
                .containsEntry("prediction_attribute", "NORMAL");
    }

    @Test
    void 추가_규칙을_어기면_invalid_400과_칸별_사유이고_저장하지_않는다() throws Exception {
        postMenu(NEW_MENU.formatted(45))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("durationMinutes"));
        for (String body :
                List.of(
                        NEW_MENU.formatted(90)
                                .replace("\"cycleMinDays\":30", "\"cycleMinDays\":61"),
                        NEW_MENU.formatted(90).replace("LASH", "HAIR"),
                        NEW_MENU.formatted(90).replace("\" 속눈썹 연장 \"", "\" \""),
                        NEW_MENU.formatted(90).replace("60000", "-1"),
                        NEW_MENU.formatted(0),
                        NEW_MENU.formatted(90)
                                .replace("}", ",\"predictionAttribute\":\"SKIP\"}"))) {
            postMenu(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"))
                    .andExpect(jsonPath("$.error.details[0].field").isString());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM service_menu WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 주기를_바꾸면_그_메뉴를_받은_고객만_예측을_다시_계산한다() throws Exception {
        long menu = data.menu(shop, 60); // NAIL 21~28일
        long otherMenu = data.menu(shop, 60);
        long customer = data.customer(shop, false, null);
        long other = data.customer(shop, false, null);
        visit(customer, menu);
        visit(other, otherMenu);
        predictions.recalculate(shop, List.of(customer, other));

        patchMenu(menu, "{\"cycleMinDays\":30,\"cycleMaxDays\":40}")
                .andExpect(status().isNoContent());

        // 방문 1회: 메뉴 기본 주기 범위 그대로 (FR-PRD-01)
        assertThat(prediction(customer))
                .containsEntry("start_date", VISIT_DATE.plusDays(30).toString())
                .containsEntry("end_date", VISIT_DATE.plusDays(40).toString());
        assertThat(prediction(other))
                .containsEntry("start_date", VISIT_DATE.plusDays(21).toString())
                .containsEntry("end_date", VISIT_DATE.plusDays(28).toString());
    }

    @Test
    void 예측_속성만_보내_주기_계산_제외로_바꾸면_다른_칸은_그대로이고_예측은_없음이_된다() throws Exception {
        long menu = data.menu(shop, 60);
        long customer = data.customer(shop, false, null);
        visit(customer, menu);
        predictions.recalculate(shop, List.of(customer));

        patchMenu(menu, "{\"predictionAttribute\":\"EXCLUDE\"}").andExpect(status().isNoContent());

        assertThat(jdbc.queryForMap("SELECT * FROM service_menu WHERE id = ?", menu))
                .containsEntry("prediction_attribute", "EXCLUDE")
                .containsEntry("name", "젤")
                .containsEntry("price", 50000)
                .containsEntry("cycle_min_days", 21);
        // 그 카테고리 방문이 모두 주기 계산 제외 → 예측 없음 (FR-PRD-01 ①, FR-VIS-05)
        assertThat(prediction(customer).get("start_date")).isNull();
    }

    @Test
    void 카테고리를_보내거나_바꾼_뒤_최소가_최대보다_크면_invalid_400이고_바뀌지_않는다() throws Exception {
        long menu = data.menu(shop, 60);

        patchMenu(menu, "{\"category\":\"LASH\",\"name\":\"속눈썹\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("category"));
        patchMenu(menu, "{\"cycleMaxDays\":20}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("cycleMinDays"));

        assertThat(jdbc.queryForMap("SELECT * FROM service_menu WHERE id = ?", menu))
                .containsEntry("category", "NAIL")
                .containsEntry("name", "젤")
                .containsEntry("cycle_max_days", 28);
    }

    @Test
    void 다른_매장이나_없는_메뉴는_not_found_404() throws Exception {
        long otherShopMenu = data.menu(data.shop(), 60);

        patchMenu(otherShopMenu, "{\"price\":1000}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT price FROM service_menu WHERE id = ?",
                                Integer.class,
                                otherShopMenu))
                .isEqualTo(50000);
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required_403() throws Exception {
        long menu = data.menu(shop, 60);
        mvc.perform(
                        post("/api/service-menus")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(NEW_MENU.formatted(90)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        mvc.perform(
                        patch("/api/service-menus/{menuId}", menu)
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"predictionAttribute\":\"EXCLUDE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    /** 메뉴를 받은 일반 방문 하나 */
    private void visit(long customerId, long menuId) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                VISIT_DATE);
        long visitId =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE shop_id = ? AND customer_id = ? AND visit_date = ?",
                        Long.class,
                        shop,
                        customerId,
                        VISIT_DATE);
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category) VALUES (?, ?, ?, 'NAIL')",
                shop,
                visitId,
                menuId);
    }

    private Map<String, Object> prediction(long customerId) {
        return jdbc.queryForMap(
                "SELECT CAST(start_date AS CHAR) start_date, CAST(end_date AS CHAR) end_date FROM customer_prediction"
                        + " WHERE customer_id = ? AND category = 'NAIL'",
                customerId);
    }
}
