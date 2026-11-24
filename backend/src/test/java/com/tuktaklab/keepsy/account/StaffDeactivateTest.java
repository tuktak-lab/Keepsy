package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
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

/**
 * POST /api/staff/{staffId}/deactivate 스탭 비활성화 (Keepsy #23, CONTRACTS 6장 ACC, FR-ACC-04, F34). 실제
 * MySQL에서 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffDeactivateTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

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

    private ResultActions deactivate(long staffId) throws Exception {
        return mvc.perform(
                post("/api/staff/{id}/deactivate", staffId).session(session(true)).with(csrf()));
    }

    private String staffStatus(long staffId) {
        return jdbc.queryForObject("SELECT status FROM staff WHERE id = ?", String.class, staffId);
    }

    @Test
    void 확정_예약이_없으면_204이고_비활성이_된다_다시_불러도_204() throws Exception {
        long note = data.customer(shop, false, null);
        // 취소 · 노쇼 · 완료 예약만 있으면 막지 않는다
        reservation(staff, note, LocalDateTime.now().plusDays(1), "CANCELED");
        reservation(staff, note, LocalDateTime.now().minusDays(1), "NO_SHOW");
        reservation(staff, note, LocalDateTime.now().minusDays(2), "COMPLETED");

        deactivate(staff).andExpect(status().isNoContent());
        assertThat(staffStatus(staff)).isEqualTo("INACTIVE");
        // 기록은 그대로 남는다
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM reservation WHERE staff_id = ?",
                                Integer.class,
                                staff))
                .isEqualTo(3);

        deactivate(staff).andExpect(status().isNoContent());
    }

    @Test
    void 앞으로의_확정_예약과_마감_안_된_지난_확정_예약이_있으면_conflict와_목록이고_바뀌지_않는다() throws Exception {
        long customer = data.customer(shop, false, null);
        LocalDateTime past = LocalDateTime.now().minusDays(1).withNano(0).withSecond(0);
        LocalDateTime future = LocalDateTime.now().plusDays(3).withNano(0).withSecond(0);
        long pastUnclosed = reservation(staff, customer, past, "CONFIRMED");
        long noCustomer = reservation(staff, null, future, "CONFIRMED");
        reservation(owner, null, future, "CONFIRMED");

        deactivate(staff)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"))
                .andExpect(
                        jsonPath("$.error.details[*].id")
                                .value(contains((int) pastUnclosed, (int) noCustomer)))
                .andExpect(jsonPath("$.error.details[0].startAt").value(past + ":00+09:00"))
                .andExpect(jsonPath("$.error.details[1].customerName").value(nullValue()));
        assertThat(staffStatus(staff)).isEqualTo("ACTIVE");
    }

    @Test
    void 원장은_invalid_다른_매장_스탭은_not_found_원장_PIN이_없으면_owner_pin_required() throws Exception {
        long otherShopStaff = data.staff(data.shop(), "ACTIVE", false);

        deactivate(owner)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        deactivate(otherShopStaff)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(post("/api/staff/{id}/deactivate", staff).session(session(false)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));

        assertThat(staffStatus(owner)).isEqualTo("ACTIVE");
        assertThat(staffStatus(otherShopStaff)).isEqualTo("ACTIVE");
        assertThat(staffStatus(staff)).isEqualTo("ACTIVE");
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
