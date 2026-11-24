package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/** 스탭 등록 · 수정 (Keepsy #21, CONTRACTS 6장 ACC, F12). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffWriteTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long owner;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** 근무자 = 원장, 원장 PIN 인증 여부만 바꾼다 */
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
                post("/api/staff")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions update(long staffId, String body) throws Exception {
        return mvc.perform(
                patch("/api/staff/{id}", staffId)
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private long created(String body) throws Exception {
        String json = create(body).andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll("\\D", ""));
    }

    private Map<String, Object> row(long staffId) {
        return jdbc.queryForMap(
                "SELECT name, pin_hash, owner, locked_feature_allowed, regular_days_off, status"
                        + " FROM staff WHERE id = ?",
                staffId);
    }

    @Test
    void 원장_PIN으로_등록하면_201과_ID이고_PIN은_해시_허용은_기본_꺼짐_휴무는_월요일부터_저장() throws Exception {
        create("{\"name\":\"모의 스탭\",\"pin\":\"4321\",\"regularDaysOff\":[\"FRIDAY\",\"MONDAY\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        long id =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM staff WHERE shop_id = ?", Long.class, shop);
        Map<String, Object> r = row(id);
        assertThat(r)
                .containsEntry("name", "모의 스탭")
                .containsEntry("owner", false)
                .containsEntry("locked_feature_allowed", false)
                .containsEntry("regular_days_off", "MONDAY,FRIDAY")
                .containsEntry("status", "ACTIVE");
        assertThat((String) r.get("pin_hash")).startsWith("$2").isNotEqualTo("4321");
    }

    @Test
    void 같은_매장_스탭의_PIN과_같으면_비활성이어도_duplicate_409() throws Exception {
        long first = created("{\"name\":\"모의 스탭\",\"pin\":\"5678\"}");
        jdbc.update("UPDATE staff SET status = 'INACTIVE' WHERE id = ?", first);

        create("{\"name\":\"다른 스탭\",\"pin\":\"5678\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
    }

    @Test
    void 이름_PIN_요일_규칙을_어기면_invalid_400() throws Exception {
        create("{\"name\":\"모의 스탭\",\"pin\":\"12ab\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        create("{\"name\":\" \",\"pin\":\"1234\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        create("{\"name\":\"모의 스탭\",\"pin\":\"1234\",\"regularDaysOff\":[\"MONDAY\",\"MONDAY\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        create("{\"name\":\"모의 스탭\",\"pin\":\"1234\",\"regularDaysOff\":[\"MON\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required_403() throws Exception {
        mvc.perform(
                        post("/api/staff")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"모의 스탭\",\"pin\":\"4321\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    @Test
    void 수정은_보낸_칸만_바꾸고_원장_허용은_끌_수_없고_다른_매장_스탭은_not_found() throws Exception {
        long staff = created("{\"name\":\"모의 스탭\",\"pin\":\"4321\"}");

        update(staff, "{\"lockedFeatureAllowed\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushedReservations").value(empty()));
        assertThat(row(staff))
                .containsEntry("name", "모의 스탭")
                .containsEntry("locked_feature_allowed", true);

        update(owner, "{\"lockedFeatureAllowed\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        long otherStaff = data.staff(data.shop(), "ACTIVE", false);
        update(otherStaff, "{\"name\":\"바꿈\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void 정기_휴무를_바꾸면_그_요일의_앞으로_확정_예약을_돌려주고_취소하지_않는다() throws Exception {
        long staff = created("{\"name\":\"모의 스탭\",\"pin\":\"4321\"}");
        long customer = data.customer(shop, false, null);
        LocalDate monday =
                LocalDate.now()
                        .plusWeeks(1)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long pushed = reservation(staff, customer, monday.atTime(11, 0), "CONFIRMED");
        long noCustomer = reservation(staff, null, monday.plusWeeks(1).atTime(10, 0), "CONFIRMED");
        reservation(staff, customer, monday.plusDays(1).atTime(11, 0), "CONFIRMED"); // 화요일
        reservation(staff, customer, monday.atTime(15, 0), "CANCELED");
        reservation(staff, customer, monday.minusWeeks(2).atTime(11, 0), "CONFIRMED"); // 지난 예약

        update(staff, "{\"regularDaysOff\":[\"MONDAY\"]}")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.pushedReservations[*].id")
                                .value(contains((int) pushed, (int) noCustomer)))
                .andExpect(jsonPath("$.pushedReservations[0].customerName").value("모의 고객"))
                .andExpect(
                        jsonPath("$.pushedReservations[0].startAt")
                                .value(monday.atTime(11, 0) + ":00+09:00"))
                .andExpect(jsonPath("$.pushedReservations[1].customerName").value(nullValue()));

        assertThat(row(staff)).containsEntry("regular_days_off", "MONDAY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?",
                                String.class,
                                pushed))
                .isEqualTo("CONFIRMED");
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
