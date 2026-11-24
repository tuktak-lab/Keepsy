package com.tuktaklab.keepsy.reservation;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;

/** 고객의 예약 목록 (Keepsy #38 완료 조건 · 검증 방법, CONTRACTS 6장). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerReservationsTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 5);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService service;

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

    @Test
    void 그_고객의_예약만_시작_시각이_늦은_순으로_주고_취소도_넣는다() throws Exception {
        long first = reserve(customer, DAY, 10, false);
        long second = reserve(customer, DAY.plusDays(1), 10, true);
        long third = reserve(customer, DAY.plusDays(2), 10, false);
        reserve(data.customer(shop, false, null), DAY.plusDays(3), 10, false);
        reserve(null, DAY.plusDays(4), 10, false);
        service.cancel(shop, second);

        list(shop, customer, null)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[*].id")
                                .value(contains((int) third, (int) second, (int) first)))
                .andExpect(jsonPath("$.items[1].status").value("CANCELED"))
                .andExpect(jsonPath("$.items[1].consultFirst").value(true))
                .andExpect(jsonPath("$.items[0].staff.id").value(staff))
                .andExpect(jsonPath("$.items[0].services[0].serviceMenuId").value(gel))
                .andExpect(jsonPath("$.items[0].customer.id").value(customer))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void 페이징한다() throws Exception {
        reserve(customer, DAY, 10, false);
        reserve(customer, DAY.plusDays(1), 10, false);
        long last = reserve(customer, DAY.plusDays(2), 10, false);

        list(shop, customer, "2")
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(last))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(
                        get("/api/customers/" + customer + "/reservations")
                                .session(session(shop))
                                .param("page", "1")
                                .param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1));
        list(shop, customer, "101").andExpect(status().isBadRequest());
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        list(data.shop(), customer, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        list(shop, customer, null).andExpect(status().isNotFound());
    }

    @Test
    void RESERVATION을_PIN으로_잠그면_pin_required() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'RESERVATION' WHERE id = ?", shop);

        list(shop, customer, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private long reserve(Long customerId, LocalDate day, int hour, boolean consult) {
        return service.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        day.atTime(hour, 0),
                        null,
                        ReservationChannel.PHONE,
                        consult,
                        null,
                        List.of(gel)),
                false);
    }

    private ResultActions list(long shopId, long customerId, String size) throws Exception {
        var req = get("/api/customers/" + customerId + "/reservations").session(session(shopId));
        if (size != null) {
            req.param("size", size);
        }
        return mvc.perform(req);
    }

    private MockHttpSession session(long shopId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return session;
    }
}
