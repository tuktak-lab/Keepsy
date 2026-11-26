package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
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

/**
 * 결제 수정 · 취소 API의 권한 · 요청 검증 · 응답 (Keepsy #68 · #69, CONTRACTS 6장). 규칙 자체는
 * VisitDeleteAndPaymentTest
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;

    private TestShopData data;
    private long shop;
    private long staff;
    private long payment;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        long menu = data.menu(shop, 60);
        long customer = data.customer(shop, false, null);
        long r =
                reservations.create(
                        shop,
                        new NewReservation(
                                customer,
                                staff,
                                LocalDate.of(2026, 11, 3).atTime(11, 0),
                                null,
                                null,
                                false,
                                null,
                                List.of(menu)),
                        false);
        long visitId =
                visits.close(
                        shop, r, new Closing(null, 50000, PaymentMethod.CARD, null, false), staff);
        payment =
                jdbc.queryForObject(
                        "SELECT id FROM payment WHERE visit_id = ?", Long.class, visitId);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** 결제 수정은 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (FR-ACC-08) */
    private MockHttpSession session(long shopId, long worker, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, worker, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap(
                "SELECT amount, method, canceled FROM payment WHERE id = ?", payment);
    }

    @Test
    void 수정하면_204이고_금액과_결제_수단이_바뀐다_값이_없으면_invalid() throws Exception {
        MockHttpSession s = session(shop, staff, true);

        mvc.perform(
                        patch("/api/payments/{id}", payment)
                                .session(s)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\": 45000, \"method\": \"CASH\"}"))
                .andExpect(status().isNoContent());
        assertThat(row()).containsEntry("amount", 45000).containsEntry("method", "CASH");

        mvc.perform(
                        patch("/api/payments/{id}", payment)
                                .session(s)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\": -1, \"method\": \"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 취소하면_204_다시_취소하거나_수정하면_conflict() throws Exception {
        MockHttpSession s = session(shop, staff, true);

        mvc.perform(post("/api/payments/{id}/cancel", payment).session(s).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(row()).containsEntry("canceled", true);

        mvc.perform(post("/api/payments/{id}/cancel", payment).session(s).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        mvc.perform(
                        patch("/api/payments/{id}", payment)
                                .session(s)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\": 1, \"method\": \"CARD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void PIN_인증이_없으면_pin_required_다른_매장_결제는_not_found() throws Exception {
        mvc.perform(
                        post("/api/payments/{id}/cancel", payment)
                                .session(session(shop, staff, false))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);
        mvc.perform(
                        post("/api/payments/{id}/cancel", payment)
                                .session(session(otherShop, otherStaff, true))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(row()).containsEntry("canceled", false);
    }
}
