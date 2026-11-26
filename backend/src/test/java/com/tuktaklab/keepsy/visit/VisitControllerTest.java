package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 완료 처리 · 시술 마감 카드 API의 권한 · 요청 검증 · 응답 모양 (CONTRACTS 6장). 규칙 자체는 VisitAppServiceTest ·
 * VisitClosingTest
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisitControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;

    private TestShopData data;
    private long shop;
    private long staff;
    private long gel;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long reserve(Long customerId, int hour, boolean consult) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        consult,
                        null,
                        List.of(gel)),
                false);
    }

    /** 매장 로그인 + 현재 근무자 (세션을 직접 채운다) */
    private MockHttpSession session(long shopId, Long worker) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        return session;
    }

    private MockHttpServletRequestBuilder postJson(String path, long id, String json) {
        MockHttpServletRequestBuilder b =
                post("/api/reservations/{id}" + path, id)
                        .session(session(shop, staff))
                        .with(csrf());
        return json == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void 완료는_본문_없이도_되고_visitId를_준다() throws Exception {
        long r = reserve(customer, 11, false);

        mvc.perform(postJson("/complete", r, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").isNumber());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, r))
                .isEqualTo("COMPLETED");
    }

    @Test
    void 상담_후_진행_예약은_실제_시술_없이_완료하면_invalid_보내면_완료() throws Exception {
        long r = reserve(customer, 11, true);

        mvc.perform(postJson("/complete", r, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(postJson("/complete", r, "{\"serviceMenuIds\": [" + gel + "]}"))
                .andExpect(status().isOk());
    }

    @Test
    void 고객_없는_예약은_완료_처리하면_conflict() throws Exception {
        long r = reserve(null, 11, false);

        mvc.perform(postJson("/complete", r, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 마감_카드는_visitId를_주고_결제_수단이_없거나_메모가_길면_invalid() throws Exception {
        long r = reserve(customer, 11, false);

        mvc.perform(postJson("/closing", r, "{\"amount\": 50000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        postJson(
                                "/closing",
                                r,
                                "{\"amount\": 50000, \"method\": \"CARD\", \"memo\": \"%s\"}"
                                        .formatted("가".repeat(1001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        String ok =
                """
                {"services": [{"serviceMenuId": %d, "staffId": %d, "cycleExcluded": true}],
                 "amount": 50000, "method": "CARD", "memo": "다음엔 누드톤"}\
                """
                        .formatted(gel, staff);
        mvc.perform(postJson("/closing", r, ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitId").isNumber());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT staff_id FROM visit_note WHERE shop_id = ?",
                                Long.class,
                                shop))
                .isEqualTo(staff);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_excluded FROM visit_service WHERE shop_id = ?",
                                Boolean.class,
                                shop))
                .isTrue();
    }

    @Test
    void 고객_없는_예약은_noCustomer_없이_마감하면_conflict_있으면_마감() throws Exception {
        long r = reserve(null, 11, false);

        mvc.perform(postJson("/closing", r, "{\"amount\": 0, \"method\": \"CASH\"}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        postJson(
                                "/closing",
                                r,
                                "{\"amount\": 0, \"method\": \"CASH\", \"noCustomer\": true}"))
                .andExpect(status().isOk());
    }

    @Test
    void 근무자가_없으면_worker_required_다른_매장_예약은_not_found() throws Exception {
        long r = reserve(customer, 11, false);

        mvc.perform(
                        post("/api/reservations/{id}/complete", r)
                                .session(session(shop, null))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);
        mvc.perform(
                        post("/api/reservations/{id}/complete", r)
                                .session(session(otherShop, otherStaff))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }
}
