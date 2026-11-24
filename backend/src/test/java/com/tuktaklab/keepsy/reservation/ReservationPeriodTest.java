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

/** 기간 예약 · 주간 캘린더 · 일간 보기 (Keepsy #58 완료 조건, CONTRACTS 6장). 실제 MySQL에서 돈다. */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationPeriodTest {

    /** 월요일 (F10) */
    private static final LocalDate MON = LocalDate.of(2099, 1, 5);

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

    private long reserve(Long customerId, LocalDate day, int hour, boolean consult) {
        return service.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        day.atTime(hour, 0),
                        null,
                        ReservationChannel.NAVER,
                        consult,
                        null,
                        List.of(gel)),
                false);
    }

    private ResultActions period(long shopId, long worker, String from, String to)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        var req = get("/api/reservations").session(session);
        if (from != null) {
            req.param("from", from);
        }
        if (to != null) {
            req.param("to", to);
        }
        return mvc.perform(req);
    }

    @Test
    void 한_주_예약을_시작_시각_순으로_주고_to를_포함한다() throws Exception {
        long sun = reserve(customer, MON.plusDays(6), 10, false);
        long monLate = reserve(null, MON, 15, true);
        long monEarly = reserve(customer, MON, 11, false);
        reserve(customer, MON.plusDays(7), 11, false); // 다음 주 월요일은 빠진다

        period(shop, staff, MON.toString(), MON.plusDays(6).toString())
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.reservations[*].id",
                                contains((int) monEarly, (int) monLate, (int) sun)))
                .andExpect(jsonPath("$.reservations[0].startAt").value("2099-01-05T11:00:00+09:00"))
                .andExpect(jsonPath("$.reservations[0].minutes").value(60))
                .andExpect(jsonPath("$.reservations[0].staff.id").value(staff))
                .andExpect(jsonPath("$.reservations[0].customer.id").value(customer))
                .andExpect(jsonPath("$.reservations[0].customer.name").value("모의 고객"))
                .andExpect(jsonPath("$.reservations[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reservations[1].customer").doesNotExist())
                .andExpect(jsonPath("$.reservations[1].channel").value("NAVER"))
                .andExpect(jsonPath("$.reservations[1].consultFirst").value(true))
                .andExpect(jsonPath("$.reservations[1].services[0].serviceMenuId").value(gel))
                .andExpect(jsonPath("$.reservations[1].services[0].name").value("젤"));
    }

    @Test
    void 일간은_from과_to가_같고_취소된_예약도_상태와_함께_준다() throws Exception {
        long r = reserve(customer, MON, 11, false);
        service.cancel(shop, r);
        reserve(customer, MON.plusDays(1), 11, false);

        period(shop, staff, MON.toString(), MON.toString())
                .andExpect(jsonPath("$.reservations.length()").value(1))
                .andExpect(jsonPath("$.reservations[0].status").value("CANCELED"));
    }

    @Test
    void 다른_매장_예약은_빠진다() throws Exception {
        reserve(customer, MON, 11, false);
        long otherShop = data.shop();

        period(otherShop, data.staff(otherShop, "ACTIVE", false), MON.toString(), MON.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(0));
    }

    @Test
    void 기간이_7일을_넘거나_뒤집히거나_없으면_invalid() throws Exception {
        for (String[] bad :
                new String[][] {
                    {MON.toString(), MON.plusDays(7).toString()},
                    {MON.plusDays(1).toString(), MON.toString()},
                    {MON.toString(), null},
                    {"2099-13-01", MON.toString()}
                }) {
            period(shop, staff, bad[0], bad[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }
}
