package com.tuktaklab.keepsy.visit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import com.tuktaklab.keepsy.visit.Closing.Item;
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
import org.springframework.test.web.servlet.ResultActions;

/** 시술 마감 카드 조회 (Keepsy #64 완료 조건 · 검증 방법, CONTRACTS 6장). 실제 MySQL에서 돈다. */
@SpringBootTest
@AutoConfigureMockMvc
class ClosingCardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        staff2 = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long reserve(Long customerId, boolean consult) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(11, 0),
                        null,
                        null,
                        consult,
                        null,
                        List.of(gel)),
                false);
    }

    private void consent(String item, boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at) VALUES (?, ?, ?, ?, ?)",
                shop,
                customer,
                item,
                agreed,
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private ResultActions card(long reservationId, long shopId, long worker) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        return mvc.perform(get("/api/reservations/{id}/closing", reservationId).session(session));
    }

    @Test
    void 확정_예약은_예정_시술을_예약_담당_스탭으로_채우고_동의_받기_대상을_준다() throws Exception {
        long r = reserve(customer, false);
        consent("PHOTO", true);
        consent("MARKETING", true);
        consent("MARKETING", false);

        card(r, shop, staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.servicesConfirmRequired").value(false))
                .andExpect(jsonPath("$.visitId").doesNotExist())
                .andExpect(jsonPath("$.customer.id").value(customer))
                .andExpect(jsonPath("$.services[0].serviceMenuId").value(gel))
                .andExpect(jsonPath("$.services[0].price").value(50000))
                .andExpect(jsonPath("$.services[0].predictionAttribute").value("NORMAL"))
                .andExpect(jsonPath("$.services[0].staff.id").value(staff))
                .andExpect(jsonPath("$.services[0].cycleExcluded").value(false))
                .andExpect(jsonPath("$.photoAllowed").value(true))
                .andExpect(
                        jsonPath(
                                "$.consentsToRequest",
                                contains("MARKETING", "NIGHT_AD", "HEALTH")));
    }

    @Test
    void 상담_후_진행_확정_예약은_실제_시술_확인이_필요하다() throws Exception {
        card(reserve(customer, true), shop, staff)
                .andExpect(jsonPath("$.servicesConfirmRequired").value(true));
    }

    @Test
    void 완료된_예약은_기록된_실제_시술과_방문_ID를_준다() throws Exception {
        long r = reserve(customer, true);
        long visitId =
                visits.close(
                        shop,
                        r,
                        new Closing(
                                List.of(new Item(gel, staff2, true)),
                                50000,
                                PaymentMethod.CARD,
                                null,
                                false),
                        staff);

        card(r, shop, staff)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.servicesConfirmRequired").value(false))
                .andExpect(jsonPath("$.visitId").value(visitId))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].staff.id").value(staff2))
                .andExpect(jsonPath("$.services[0].cycleExcluded").value(true));
    }

    @Test
    void 고객_없는_예약은_고객이_null이고_동의_받기_대상이_없다() throws Exception {
        card(reserve(null, false), shop, staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.photoAllowed").value(false))
                .andExpect(jsonPath("$.consentsToRequest", empty()));
    }

    @Test
    void 취소된_예약은_conflict_다른_매장_예약은_not_found() throws Exception {
        long r = reserve(customer, false);
        reservations.cancel(shop, r);
        card(r, shop, staff)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        long otherShop = data.shop();
        card(reserve(customer, false), otherShop, data.staff(otherShop, "ACTIVE", false))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }
}
