package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
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

/**
 * PUT /api/shop 매장 정보 · 영업시간 수정 (Keepsy #27, CONTRACTS 6장 SET, FR-SET-01 · 07, C7, F12, F39). 실제
 * MySQL에서 돈다. 모의 데이터만 쓴다. 테스트 매장은 매일 10:00~19:00.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopUpdateTest {

    /** 다음 주 월요일 (지금 이후 예약만 보므로 앞날) */
    private static final LocalDate MON =
            LocalDate.now().plusDays(1).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;

    private TestShopData data;
    private long shop;
    private long owner;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        staff = data.staff(shop, "ACTIVE", false);
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

    /** 월요일만 {@code mondayClose}로 닫고 나머지는 10:00~19:00, 일요일은 sundayOff면 정기 휴무 */
    private static String body(String mondayClose, boolean sundayOff, String phone) {
        StringBuilder hours = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            String close = d == DayOfWeek.MONDAY ? mondayClose : "19:00";
            hours.append(hours.isEmpty() ? "" : ",")
                    .append(
                            d == DayOfWeek.SUNDAY && sundayOff
                                    ? "{\"dayOfWeek\":\"SUNDAY\",\"open\":null,\"close\":null}"
                                    : "{\"dayOfWeek\":\"%s\",\"open\":\"10:00\",\"close\":\"%s\"}"
                                            .formatted(d, close));
        }
        return ("{\"name\":\" 모의 네일 \",\"phone\":%s,\"senderNumber\":\"1588-0000\","
                        + "\"unsubscribeNumber\":\"\",\"openingHours\":[%s]}")
                .formatted(phone == null ? "null" : "\"" + phone + "\"", hours);
    }

    private ResultActions update(String body) throws Exception {
        return mvc.perform(
                put("/api/shop")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void 월요일_종료를_19시에서_17시로_당기면_17시_이후_확정_예약만_목록이고_상태는_그대로_예약_시간에_바로_반영된다() throws Exception {
        long customer = data.customer(shop, false, null);
        long late = reservation(customer, MON.atTime(17, 0), "CONFIRMED");
        long overlapping = reservation(null, MON.atTime(16, 30), "CONFIRMED");
        reservation(customer, MON.atTime(15, 0), "CONFIRMED"); // 16:00에 끝나 안쪽
        reservation(customer, MON.atTime(18, 0), "CANCELED");
        reservation(customer, MON.atTime(17, 30), "NO_SHOW");
        assertThat(reservations.availableTimes(shop, owner, MON, 60)).contains(LocalTime.of(17, 0));

        update(body("17:00", false, "02-000-0000"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.pushedReservations[*].id")
                                .value(contains((int) overlapping, (int) late)))
                .andExpect(
                        jsonPath("$.pushedReservations[0].startAt")
                                .value(MON.atTime(16, 30) + ":00+09:00"))
                .andExpect(jsonPath("$.pushedReservations[0].customerName").value(nullValue()));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, late))
                .isEqualTo("CONFIRMED");
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT name, phone, sender_number, unsubscribe_number,"
                                + " CAST(mon_close_time AS CHAR) mon_close FROM shop WHERE id = ?",
                        shop);
        assertThat(row)
                .containsEntry("name", "모의 네일")
                .containsEntry("phone", "02-000-0000")
                .containsEntry("sender_number", "1588-0000")
                .containsEntry("unsubscribe_number", null)
                .containsEntry("mon_close", "17:00:00");
        // 바꾼 영업시간은 바로 예약 가능 시간에 쓰인다 (FR-RSV-04)
        assertThat(reservations.availableTimes(shop, owner, MON, 60))
                .doesNotContain(LocalTime.of(16, 30), LocalTime.of(17, 0));
    }

    @Test
    void 일요일을_정기_휴무로_바꾸면_일요일_확정_예약이_목록에_나온다() throws Exception {
        long sunday = reservation(null, MON.minusDays(1).atTime(12, 0), "CONFIRMED");
        reservation(null, MON.atTime(12, 0), "CONFIRMED");

        update(body("19:00", true, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushedReservations[*].id").value(contains((int) sunday)));
        assertThat(reservations.availableTimes(shop, owner, MON.minusDays(1), 60)).isEmpty();
    }

    @Test
    void 번호_형식_요일_시각_단위가_틀리면_invalid_400과_칸별_사유이고_바뀌지_않는다() throws Exception {
        update(body("17:00", false, "010-12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("phone"));
        update(body("17:15", false, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("openingHours.MONDAY"));
        update(body("09:00", false, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("openingHours.MONDAY"));
        update(body("17:00", false, null).replace("\"SUNDAY\"", "\"MONDAY\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("openingHours"));

        assertThat(jdbc.queryForObject("SELECT name FROM shop WHERE id = ?", String.class, shop))
                .isEqualTo("모의 매장");
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required() throws Exception {
        mvc.perform(
                        put("/api/shop")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("17:00", false, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    private long reservation(Long customerId, LocalDateTime startAt, String status) {
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customerId,
                staff,
                startAt,
                startAt.plusHours(1),
                status,
                LocalDateTime.of(2026, 1, 1, 0, 0));
        return jdbc.queryForObject(
                "SELECT id FROM reservation WHERE shop_id = ? AND start_at = ? AND status = ?",
                Long.class,
                shop,
                startAt,
                status);
    }
}
