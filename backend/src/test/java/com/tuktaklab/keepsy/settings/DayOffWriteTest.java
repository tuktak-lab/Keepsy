package com.tuktaklab.keepsy.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
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
 * POST · DELETE /api/day-offs 휴무일 등록 · 삭제 (Keepsy #32, CONTRACTS 6장 SET, FR-SET-07, C7, F12, F32).
 * 실제 MySQL에서 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DayOffWriteTest {

    /** 지난 날짜 검사에 걸리지 않게 앞으로의 날 */
    private static final LocalDate DAY = LocalDate.now().plusDays(30);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;

    private TestShopData data;
    private long shop;
    private long owner;
    private long staff;
    private long other;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        staff = data.staff(shop, "ACTIVE", false);
        other = data.staff(shop, "ACTIVE", false);
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

    private ResultActions create(String body) throws Exception {
        return mvc.perform(
                post("/api/day-offs")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions remove(long id) throws Exception {
        return mvc.perform(delete("/api/day-offs/{id}", id).session(session(true)).with(csrf()));
    }

    @Test
    void 확정_예약이_있는_날_스탭_휴무를_넣으면_그_스탭_예약만_돌려주고_상태는_그대로_예약_시간이_빈다() throws Exception {
        long customer = data.customer(shop, false, null);
        long pushed = reservation(staff, customer, DAY.atTime(11, 0), "CONFIRMED");
        long noCustomer = reservation(staff, null, DAY.atTime(15, 0), "CONFIRMED");
        reservation(staff, null, DAY.atTime(13, 0), "CANCELED");
        reservation(other, null, DAY.atTime(11, 0), "CONFIRMED");
        assertThat(reservations.availableTimes(shop, staff, DAY, 30)).isNotEmpty();

        String body =
                create(
                                "{\"date\":\"%s\",\"staffId\":%d,\"reason\":\"  병원  \"}"
                                        .formatted(DAY, staff))
                        .andExpect(status().isCreated())
                        .andExpect(
                                jsonPath("$.pushedReservations[*].id")
                                        .value(contains((int) pushed, (int) noCustomer)))
                        .andExpect(
                                jsonPath("$.pushedReservations[0].startAt")
                                        .value(DAY.atTime(11, 0) + ":00+09:00"))
                        .andExpect(
                                jsonPath("$.pushedReservations[1].customerName").value(nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?",
                                String.class,
                                pushed))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT reason FROM day_off WHERE id = ?", String.class, id))
                .isEqualTo("병원");
        assertThat(reservations.availableTimes(shop, staff, DAY, 30)).isEmpty();
        assertThat(reservations.availableTimes(shop, other, DAY, 30)).isNotEmpty();

        // 삭제하면 그날 다시 예약할 수 있다
        remove(id).andExpect(status().isNoContent());
        assertThat(reservations.availableTimes(shop, staff, DAY, 30)).isNotEmpty();
    }

    @Test
    void 매장_휴무는_모든_스탭의_확정_예약을_돌려준다() throws Exception {
        long a = reservation(staff, null, DAY.atTime(11, 0), "CONFIRMED");
        long b = reservation(other, null, DAY.atTime(12, 0), "CONFIRMED");
        reservation(staff, null, DAY.plusDays(1).atTime(11, 0), "CONFIRMED");

        create("{\"date\":\"%s\"}".formatted(DAY))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.pushedReservations[*].id").value(contains((int) a, (int) b)));
        assertThat(reservations.availableTimes(shop, other, DAY, 30)).isEmpty();
    }

    @Test
    void 같은_날_같은_대상이면_duplicate_409_매장_휴무와_스탭_휴무는_따로_넣을_수_있다() throws Exception {
        create("{\"date\":\"%s\"}".formatted(DAY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pushedReservations").value(empty()));
        create("{\"date\":\"%s\",\"staffId\":%d}".formatted(DAY, staff))
                .andExpect(status().isCreated());

        create("{\"date\":\"%s\",\"staffId\":null}".formatted(DAY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
        create("{\"date\":\"%s\",\"staffId\":%d}".formatted(DAY, staff))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
        create("{\"date\":\"%s\",\"staffId\":%d}".formatted(DAY, other))
                .andExpect(status().isCreated());
    }

    @Test
    void 지난_날짜_비활성_스탭_긴_사유는_invalid_다른_매장_스탭은_not_found() throws Exception {
        long inactive = data.staff(shop, "INACTIVE", false);
        long otherShopStaff = data.staff(data.shop(), "ACTIVE", false);

        create("{\"date\":\"%s\"}".formatted(LocalDate.now().minusDays(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("date"));
        create("{\"date\":\"%s\",\"staffId\":%d}".formatted(DAY, inactive))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("staffId"));
        create("{\"date\":\"%s\",\"reason\":\"%s\"}".formatted(DAY, "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("reason"));
        create("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        create("{\"date\":\"%s\",\"staffId\":%d}".formatted(DAY, otherShopStaff))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM day_off WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 다른_매장_휴무일_삭제는_not_found_원장_PIN이_없으면_owner_pin_required() throws Exception {
        long otherShop = data.shop();
        jdbc.update("INSERT INTO day_off (shop_id, off_date) VALUES (?, ?)", otherShop, DAY);
        long otherDayOff =
                jdbc.queryForObject(
                        "SELECT id FROM day_off WHERE shop_id = ?", Long.class, otherShop);

        remove(otherDayOff)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM day_off WHERE id = ?",
                                Integer.class,
                                otherDayOff))
                .isOne();

        mvc.perform(
                        post("/api/day-offs")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"date\":\"%s\"}".formatted(DAY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        mvc.perform(delete("/api/day-offs/{id}", otherDayOff).session(session(false)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    private long reservation(long staffId, Long customerId, LocalDateTime startAt, String status) {
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customerId,
                staffId,
                startAt,
                startAt.plusHours(1),
                status,
                LocalDateTime.of(2026, 1, 1, 0, 0));
        return jdbc.queryForObject(
                "SELECT id FROM reservation WHERE shop_id = ? AND staff_id = ? AND start_at = ? AND status = ?",
                Long.class,
                shop,
                staffId,
                startAt,
                status);
    }
}
