package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
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

/** 카테고리 기본 주기 조회 · 수정 (Keepsy #30, CONTRACTS 6장 SET, FR-SET-02). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryCycleTest {

    private static final String DEFAULTS =
            "[{\"category\":\"NAIL\",\"minDays\":21,\"maxDays\":28},"
                    + "{\"category\":\"LASH\",\"minDays\":%d,\"maxDays\":%d},"
                    + "{\"category\":\"WAXING\",\"minDays\":28,\"maxDays\":42},"
                    + "{\"category\":\"SEMI_PERMANENT\",\"minDays\":365,\"maxDays\":730},"
                    + "{\"category\":\"ETC\",\"minDays\":28,\"maxDays\":56}]";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

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

    private ResultActions putCycles(String body) throws Exception {
        return mvc.perform(
                put("/api/category-cycles")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void 조회는_V1_기본값_5개를_카테고리_순서로_준다() throws Exception {
        mvc.perform(get("/api/category-cycles").session(session(true)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].category")
                                .value(contains("NAIL", "LASH", "WAXING", "SEMI_PERMANENT", "ETC")))
                .andExpect(jsonPath("$[1].minDays").value(30))
                .andExpect(jsonPath("$[1].maxDays").value(60))
                .andExpect(jsonPath("$[3].maxDays").value(730));
    }

    @Test
    void 바꾸면_204이고_그_카테고리에_메뉴_없는_방문이_있는_고객의_예측을_다시_계산한다() throws Exception {
        long customer = data.customer(shop, false, null);
        LocalDate visitDate = LocalDate.of(2026, 10, 1);
        importedVisit(customer, visitDate, "LASH");

        putCycles(DEFAULTS.formatted(35, 70)).andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT lash_cycle_min_days, lash_cycle_max_days FROM shop WHERE id = ?",
                                shop))
                .containsEntry("lash_cycle_min_days", 35)
                .containsEntry("lash_cycle_max_days", 70);
        // 가져온 방문 1회: 카테고리 기본 주기 범위 그대로 (FR-PRD-01, E5)
        Map<String, Object> p =
                jdbc.queryForMap(
                        "SELECT start_date, end_date FROM customer_prediction"
                                + " WHERE customer_id = ? AND category = 'LASH'",
                        customer);
        assertThat(p.get("start_date").toString()).isEqualTo(visitDate.plusDays(35).toString());
        assertThat(p.get("end_date").toString()).isEqualTo(visitDate.plusDays(70).toString());
    }

    @Test
    void 카테고리가_빠지거나_겹치거나_최소가_최대보다_크면_invalid_400이고_바뀌지_않는다() throws Exception {
        for (String body :
                List.of(
                        "[{\"category\":\"NAIL\",\"minDays\":21,\"maxDays\":28}]",
                        DEFAULTS.formatted(30, 60).replace("\"ETC\"", "\"NAIL\""),
                        DEFAULTS.formatted(70, 35),
                        DEFAULTS.formatted(0, 10),
                        DEFAULTS.replace("\"minDays\":%d", "\"minDays\":null").formatted(60))) {
            putCycles(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lash_cycle_min_days FROM shop WHERE id = ?",
                                Integer.class,
                                shop))
                .isEqualTo(30);
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required_403() throws Exception {
        mvc.perform(get("/api/category-cycles").session(session(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        mvc.perform(
                        put("/api/category-cycles")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(DEFAULTS.formatted(35, 70)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    /** CSV로 가져온 방문: 메뉴 없음 (FR-CUS-13) */
    private void importedVisit(long customerId, LocalDate date, String category) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'IMPORT')",
                shop,
                customerId,
                date);
        long visitId =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE shop_id = ? AND customer_id = ? AND visit_date = ?",
                        Long.class,
                        shop,
                        customerId,
                        date);
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, category) VALUES (?, ?, ?)",
                shop,
                visitId,
                category);
    }
}
