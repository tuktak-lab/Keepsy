package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 방문 삭제 API의 권한 (CUSTOMER_EDIT, 취소할 결제가 있으면 PAYMENT_EDIT도) · 응답 (Keepsy #71, CONTRACTS 6장). 되돌리기 규칙
 * 자체는 VisitDeleteAndPaymentTest
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisitDeleteControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;

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

    private long reserve(Long customerId, int hour) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(gel)),
                false);
    }

    /** CUSTOMER_EDIT · PAYMENT_EDIT는 기본 PIN 잠금이다. 둘 중 하나만 공용으로 풀어 조건부 검사를 본다 */
    private void lockOnly(String features) {
        jdbc.update("UPDATE shop SET pin_locked_features = ? WHERE id = ?", features, shop);
    }

    private ResultActions deleteVisit(long visitId, long shopId, long worker, boolean pin)
            throws Exception {
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
        return mvc.perform(delete("/api/visits/{id}", visitId).session(session).with(csrf()));
    }

    /** 이 매장 접근 로그 중 그 고객 · 행동의 기록 스탭들 */
    private List<Long> loggedStaff(String action) {
        return jdbc.queryForList(
                "SELECT staff_id FROM access_log WHERE shop_id = ? AND target_type = 'CUSTOMER'"
                        + " AND target_id = ? AND action = ? ORDER BY id",
                Long.class,
                shop,
                customer,
                action);
    }

    @Test
    void 방문_수정_삭제는_그_고객의_수정_삭제로_접근_로그에_남고_실패하면_남지_않는다() throws Exception {
        long pinStaff = data.staff(shop, "ACTIVE", true);
        long visitId = visits.completeWithoutPayment(shop, reserve(customer, 11), null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);

        // PIN 없이: 403이고 남지 않는다
        mvc.perform(
                        patch("/api/visits/{id}", visitId)
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"services\":[{\"serviceMenuId\":%d,\"staffId\":%d}]}"
                                                .formatted(gel, staff)))
                .andExpect(status().isForbidden());
        assertThat(loggedStaff("UPDATE")).isEmpty();

        // CUSTOMER_EDIT는 기본 PIN이라 PIN 인증 스탭으로 남는다 (ARCHITECTURE 6장 「기록에 남는 사람」)
        shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        mvc.perform(
                        patch("/api/visits/{id}", visitId)
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"services\":[{\"serviceMenuId\":%d,\"staffId\":%d}]}"
                                                .formatted(gel, staff)))
                .andExpect(status().isNoContent());
        assertThat(loggedStaff("UPDATE")).containsExactly(pinStaff);

        mvc.perform(delete("/api/visits/{id}", visitId).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(loggedStaff("DELETE")).containsExactly(pinStaff);

        // 없는 방문(이미 지움): 404이고 더 남지 않는다
        mvc.perform(delete("/api/visits/{id}", visitId).session(session).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(loggedStaff("DELETE")).hasSize(1);
    }

    @Test
    void 공용으로_풀면_현재_근무자로_남는다() throws Exception {
        long visitId = visits.completeWithoutPayment(shop, reserve(customer, 11), null);
        lockOnly("PAYMENT_EDIT"); // CUSTOMER_EDIT 공용

        deleteVisit(visitId, shop, staff, false).andExpect(status().isNoContent());
        assertThat(loggedStaff("DELETE")).containsExactly(staff);
    }

    private int visitCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM visit WHERE shop_id = ?", Integer.class, shop);
    }

    @Test
    void 결제가_있으면_PAYMENT_EDIT도_필요하고_PIN이_있으면_204로_되돌린다() throws Exception {
        long r = reserve(customer, 11);
        long visitId =
                visits.close(
                        shop, r, new Closing(null, 50000, PaymentMethod.CARD, null, false), staff);
        lockOnly("PAYMENT_EDIT");

        deleteVisit(visitId, shop, staff, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        assertThat(visitCount()).isEqualTo(1);

        deleteVisit(visitId, shop, staff, true).andExpect(status().isNoContent());
        assertThat(visitCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, r))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void 결제가_없으면_PAYMENT_EDIT는_보지_않는다() throws Exception {
        long visitId = visits.completeWithoutPayment(shop, reserve(customer, 11), null);
        lockOnly("PAYMENT_EDIT");

        deleteVisit(visitId, shop, staff, false).andExpect(status().isNoContent());
        assertThat(visitCount()).isZero();
    }

    @Test
    void CUSTOMER_EDIT는_기본_PIN이고_고객_없는_방문은_conflict_다른_매장은_not_found() throws Exception {
        long noCustomer =
                visits.close(
                        shop,
                        reserve(null, 11),
                        new Closing(null, 0, PaymentMethod.CASH, null, true),
                        staff);
        long withCustomer = visits.completeWithoutPayment(shop, reserve(customer, 14), null);

        deleteVisit(withCustomer, shop, staff, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        deleteVisit(noCustomer, shop, staff, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);
        deleteVisit(withCustomer, otherShop, otherStaff, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(visitCount()).isEqualTo(2);
    }
}
