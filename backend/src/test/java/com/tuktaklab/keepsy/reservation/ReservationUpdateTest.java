package com.tuktaklab.keepsy.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** 예약 수정 (Keepsy #56 완료 조건, F22, CONTRACTS 6장). 실제 MySQL에서 돈다. */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationUpdateTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 6);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService service;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel;
    private long care;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        staff2 = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        care = data.menu(shop, 30);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long reserve(Long customerId, long staffId, int hour) {
        return service.create(
                shop,
                new NewReservation(
                        customerId,
                        staffId,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(gel)),
                false);
    }

    private ResultActions update(long id, long shopId, String body) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(
                patch("/api/reservations/{id}", id)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String body(long staffId, String time, String menus, String memo) {
        return """
        {"staffId": %d, "startAt": "%sT%s:00+09:00", "channel": "KAKAO", "consultFirst": true,
         "memo": %s, "serviceMenuIds": %s}\
        """
                .formatted(staffId, DAY, time, memo == null ? "null" : "\"" + memo + "\"", menus);
    }

    @Test
    void 수정하면_204이고_입력이_바뀌며_남는_예정_시술_행은_그대로다() throws Exception {
        long r = reserve(customer, staff, 11);
        long gelRow =
                jdbc.queryForObject(
                        "SELECT id FROM reservation_service WHERE reservation_id = ?",
                        Long.class,
                        r);

        update(r, shop, body(staff2, "14:00", "[" + gel + ", " + care + "]", "짧게"))
                .andExpect(status().isNoContent());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT staff_id, start_at, end_at, channel, consult_first, memo FROM reservation WHERE id = ?",
                        r);
        assertThat(row)
                .containsEntry("staff_id", staff2)
                .containsEntry("start_at", DAY.atTime(14, 0))
                .containsEntry("end_at", DAY.atTime(15, 30))
                .containsEntry("channel", "KAKAO")
                .containsEntry("consult_first", true)
                .containsEntry("memo", "짧게");
        assertThat(
                        jdbc.queryForList(
                                "SELECT id FROM reservation_service WHERE reservation_id = ? ORDER BY id",
                                Long.class,
                                r))
                .hasSize(2)
                .first()
                .isEqualTo(gelRow);
    }

    @Test
    void 자기_자신과의_겹침은_보지_않고_다른_예약과_겹치면_reservation_conflict() throws Exception {
        long r = reserve(customer, staff, 11);
        reserve(customer, staff, 13);

        update(r, shop, body(staff, "11:30", "[" + gel + "]", null))
                .andExpect(status().isNoContent());
        update(r, shop, body(staff, "12:30", "[" + gel + "]", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("reservation_conflict"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT start_at FROM reservation WHERE id = ?",
                                LocalDateTime.class,
                                r))
                .isEqualTo(DAY.atTime(11, 30));
    }

    @Test
    void 취소된_예약은_conflict_다른_매장은_not_found_고객_없는_예약의_메모는_invalid() throws Exception {
        long canceled = reserve(customer, staff, 11);
        service.cancel(shop, canceled);
        update(canceled, shop, body(staff, "15:00", "[" + gel + "]", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        long noCustomer = reserve(null, staff, 16);
        update(noCustomer, shop, body(staff, "16:00", "[" + gel + "]", "메모"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);
        update(noCustomer, otherShop, body(otherStaff, "16:00", "[" + gel + "]", null))
                .andExpect(status().isNotFound());
    }

    @Test
    void 비활성_스탭으로_바꾸거나_예정_시술이_없으면_invalid() throws Exception {
        long r = reserve(customer, staff, 11);
        long inactive = data.staff(shop, "INACTIVE", false);

        update(r, shop, body(inactive, "11:00", "[" + gel + "]", null))
                .andExpect(status().isBadRequest());
        update(r, shop, body(staff, "11:00", "[]", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }
}
