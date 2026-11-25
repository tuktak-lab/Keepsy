package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 예약 제한 켜기 · 끄기 (Keepsy #53 완료 조건, FR-CUS-14, C8, 결정 F53). 실제 MySQL에서 돌고 모의 데이터만 쓴다. 제한 고객의 예약 등록 ·
 * 고객 연결 검사 자체는 {@code ReservationAppServiceTest} · {@code ReservationControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingRestrictionTest {

    private static final LocalDate DAY = LocalDate.of(2099, 1, 6);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;

    private TestShopData data;
    private long shop;
    private long owner;
    private long staff;
    private long menu;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        staff = data.staff(shop, "ACTIVE", false);
        menu = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 켜고_사유를_바꾸고_끄면_상세에_바로_보이고_접근_로그를_남긴다() throws Exception {
        restrict("{\"restricted\":true,\"reason\":\"  노쇼 3회  \"}", true)
                .andExpect(status().isNoContent());
        assertThat(state()).isEqualTo(Map.of("restricted", true, "reason", "노쇼 3회"));
        detail().andExpect(jsonPath("$.bookingRestricted").value(true))
                .andExpect(jsonPath("$.bookingRestrictionReason").value("노쇼 3회"));

        // 같은 요청을 다시 보내도 되고, 켠 채 사유만 바꿀 수 있다
        restrict("{\"restricted\":true,\"reason\":\"노쇼 3회\"}", true)
                .andExpect(status().isNoContent());
        restrict("{\"restricted\":true,\"reason\":\"노쇼 4회\"}", true)
                .andExpect(status().isNoContent());
        assertThat(state()).isEqualTo(Map.of("restricted", true, "reason", "노쇼 4회"));

        // 끄면 보낸 사유는 무시하고 비운다
        restrict("{\"restricted\":false,\"reason\":\"무시\"}", true)
                .andExpect(status().isNoContent());
        assertThat(state()).containsEntry("restricted", false).containsEntry("reason", null);
        detail().andExpect(jsonPath("$.bookingRestricted").value(false))
                .andExpect(jsonPath("$.bookingRestrictionReason").doesNotExist());

        assertThat(
                        jdbc.queryForList(
                                "SELECT staff_id FROM access_log WHERE shop_id = ? AND target_id = ?"
                                        + " AND action = 'UPDATE'",
                                Long.class,
                                shop,
                                customer))
                .hasSize(4)
                .containsOnly(owner);
    }

    @Test
    void 사유는_200자까지이고_규칙을_어기면_invalid() throws Exception {
        restrict("{\"restricted\":true,\"reason\":\"%s\"}".formatted("가".repeat(200)), true)
                .andExpect(status().isNoContent());
        for (String body :
                List.of(
                        "{\"restricted\":true,\"reason\":\"%s\"}".formatted("가".repeat(201)),
                        "{\"restricted\":true,\"reason\":\"   \"}",
                        "{\"restricted\":true}",
                        "{\"reason\":\"노쇼\"}")) {
            restrict(body, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        assertThat(state()).containsEntry("reason", "가".repeat(200));
    }

    @Test
    void 원장_PIN이_아니면_owner_pin_required_다른_매장_삭제된_고객은_not_found() throws Exception {
        String on = "{\"restricted\":true,\"reason\":\"노쇼\"}";
        restrict(on, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));

        long other = data.customer(data.shop(), false, null);
        mvc.perform(
                        request(put("/api/customers/" + other + "/booking-restriction"), true)
                                .content(on))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        restrict(on, true).andExpect(status().isNotFound());
    }

    @Test
    void 제한을_걸어도_기존_확정_예약은_그대로고_PIN_없이_수정되며_새_등록만_원장_PIN() throws Exception {
        long booked = reservations.create(shop, reservation(11), false);
        restrict("{\"restricted\":true,\"reason\":\"노쇼 3회\"}", true)
                .andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?",
                                String.class,
                                booked))
                .isEqualTo("CONFIRMED");
        mvc.perform(
                        request(patch("/api/reservations/" + booked), false)
                                .content(
                                        "{\"staffId\":%d,\"startAt\":\"%sT13:00:00+09:00\",\"serviceMenuIds\":[%d]}"
                                                .formatted(staff, DAY, menu)))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> reservations.create(shop, reservation(15), false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("예약 제한 고객입니다. 사유: 노쇼 3회");
        assertThat(reservations.create(shop, reservation(15), true)).isPositive();
    }

    private NewReservation reservation(int hour) {
        return new NewReservation(
                customer, staff, DAY.atTime(hour, 0), null, null, false, null, List.of(menu));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        return (Map<String, Object>)
                (Map<?, ?>)
                        jdbc.queryForMap(
                                "SELECT reservation_restricted AS restricted,"
                                        + " reservation_restrict_reason AS reason FROM customer WHERE id = ?",
                                customer);
    }

    private ResultActions restrict(String body, boolean ownerPin) throws Exception {
        return mvc.perform(
                request(put("/api/customers/" + customer + "/booking-restriction"), ownerPin)
                        .content(body));
    }

    private ResultActions detail() throws Exception {
        return mvc.perform(request(get("/api/customers/" + customer), false));
    }

    private MockHttpServletRequestBuilder request(
            MockHttpServletRequestBuilder r, boolean ownerPin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (ownerPin) {
            shopSession.setPin(session, owner, LocalDateTime.now().plusMinutes(5));
        }
        return r.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }
}
