package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 고객 결제 금액 이력 (Keepsy #106 완료 조건 · 검증 방법, CONTRACTS 6장 「고객 (CUS)」). 실제 마감 · 취소 · 방문 삭제로 결제를 만든 뒤
 * API로 읽는다. 실제 MySQL, 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPaymentTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;

    private TestShopData data;
    private long shop;
    private long staff;
    private long menu;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        menu = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 최신순으로_금액과_취소_여부를_주고_취소된_결제도_포함한다() throws Exception {
        long r1 = reserve(customer, LocalDate.of(2026, 12, 1));
        long visit1 = close(r1, 50000, PaymentMethod.CARD);
        close(r1, 10000, PaymentMethod.CASH); // 완료된 예약에 결제 추가 (E4)
        long r2 = reserve(customer, LocalDate.of(2026, 12, 2));
        long visit2 = close(r2, 30000, PaymentMethod.TRANSFER);
        close(
                reserve(data.customer(shop, false, null), LocalDate.of(2026, 12, 3)),
                1,
                PaymentMethod.ETC);

        visits.cancelPayment(shop, paymentOf(visit1, 10000));
        visits.deleteVisit(shop, visit2); // 마감 되돌리기: 결제 취소 표시, 방문 연결 해제 (F8)

        mvc.perform(get("/api/customers/{id}/payments", customer).session(session(shop, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].amount").value(30000))
                .andExpect(jsonPath("$.items[0].method").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].canceled").value(true))
                .andExpect(jsonPath("$.items[0].reservationId").value(r2))
                .andExpect(jsonPath("$.items[0].visitId").value(nullValue()))
                .andExpect(jsonPath("$.items[1].amount").value(10000))
                .andExpect(jsonPath("$.items[1].canceled").value(true))
                .andExpect(jsonPath("$.items[1].visitId").value(visit1))
                .andExpect(jsonPath("$.items[2].amount").value(50000))
                .andExpect(jsonPath("$.items[2].method").value("CARD"))
                .andExpect(jsonPath("$.items[2].canceled").value(false))
                .andExpect(jsonPath("$.items[2].reservationId").value(r1))
                .andExpect(jsonPath("$.items[2].paymentId").isNumber())
                .andExpect(jsonPath("$.items[2].paidAt").value(endsWith("+09:00")));

        mvc.perform(
                        get("/api/customers/{id}/payments", customer)
                                .param("page", "1")
                                .param("size", "2")
                                .session(session(shop, true)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(50000))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void 조회는_접근_로그에_남고_not_found는_남지_않는다() throws Exception {
        mvc.perform(get("/api/customers/{id}/payments", customer).session(session(shop, true)))
                .andExpect(status().isOk());
        long other = data.customer(data.shop(), false, null);
        mvc.perform(get("/api/customers/{id}/payments", other).session(session(shop, true)))
                .andExpect(status().isNotFound());

        assertThat(viewLogs(customer)).isEqualTo(1);
        assertThat(viewLogs(other)).isZero();
    }

    private int viewLogs(long customerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_log WHERE shop_id = ? AND staff_id = ?"
                        + " AND target_type = 'CUSTOMER' AND target_id = ? AND action = 'VIEW'",
                Integer.class,
                shop,
                staff,
                customerId);
    }

    @Test
    void 결제가_없으면_빈_목록() throws Exception {
        mvc.perform(get("/api/customers/{id}/payments", customer).session(session(shop, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void 매출_성과_분석이_PIN_잠금이면_PIN_인증_없이_pin_required() throws Exception {
        mvc.perform(get("/api/customers/{id}/payments", customer).session(session(shop, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found_size가_범위_밖이면_invalid() throws Exception {
        long other = data.customer(data.shop(), false, null);
        mvc.perform(get("/api/customers/{id}/payments", other).session(session(shop, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        mvc.perform(get("/api/customers/{id}/payments", customer).session(session(shop, true)))
                .andExpect(status().isNotFound());

        mvc.perform(
                        get("/api/customers/{id}/payments", customer)
                                .param("size", "101")
                                .session(session(shop, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    /** 매출 · 성과 분석 조회는 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (명세서 2장, FR-ACC-08) */
    private MockHttpSession session(long shopId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private long reserve(long customerId, LocalDate date) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        date.atTime(11, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(menu)),
                false);
    }

    private long close(long reservationId, int amount, PaymentMethod method) {
        return visits.close(
                shop, reservationId, new Closing(null, amount, method, null, false), staff);
    }

    private long paymentOf(long visitId, int amount) {
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE visit_id = ? AND amount = ?",
                Long.class,
                visitId,
                amount);
    }
}
