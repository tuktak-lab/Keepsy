package com.tuktaklab.keepsy.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
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

/**
 * 개인정보 API마다 접근 로그가 남는지 (NFR-06, Keepsy #39 · #40 검증 방법). 기록 규칙 자체는 {@link AccessLogTest}. 실제
 * MySQL에서 돌고, 매장의 기능 잠금은 기본값(명세서 2장)에서 시작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessLogWiringTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long worker;
    private long pinStaff;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        worker = data.staff(shop, "ACTIVE", false);
        pinStaff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 연락처_전체_보기는_PIN_인증_스탭으로_VIEW_PIN이_없어_막히면_남기지_않는다() throws Exception {
        mvc.perform(get(contactPath()).session(session(false))).andExpect(status().isForbidden());
        assertThat(logs()).isEmpty();

        mvc.perform(get(contactPath()).session(session(true))).andExpect(status().isOk());
        assertThat(logs()).containsExactly(log(pinStaff, "VIEW"));
    }

    @Test
    void 기본_정보_수정은_PIN_인증_스탭으로_UPDATE() throws Exception {
        mvc.perform(
                        patch("/api/customers/" + customer)
                                .session(session(true))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"바뀐 고객\",\"phone\":\"010-0000-9999\"}"))
                .andExpect(status().isNoContent());

        assertThat(logs()).containsExactly(log(pinStaff, "UPDATE"));
    }

    @Test
    void 동의_조회는_공용이라_현재_근무자로_VIEW() throws Exception {
        mvc.perform(get("/api/customers/" + customer + "/consents").session(session(true)))
                .andExpect(status().isOk());

        assertThat(logs()).containsExactly(log(worker, "VIEW"));
    }

    @Test
    void 예약_상세는_고객이_있을_때만_그_고객으로_VIEW() throws Exception {
        long withCustomer = reservation(customer);
        long walkIn = reservation(null);

        mvc.perform(get("/api/reservations/" + walkIn).session(session(false)))
                .andExpect(status().isOk());
        assertThat(logs()).isEmpty();

        mvc.perform(get("/api/reservations/" + withCustomer).session(session(false)))
                .andExpect(status().isOk());
        assertThat(logs()).containsExactly(log(worker, "VIEW"));
    }

    private String contactPath() {
        return "/api/customers/" + customer + "/contact";
    }

    private Map<String, Object> log(long staffId, String action) {
        return Map.of(
                "staff_id", staffId,
                "target_type", AccessLogger.CUSTOMER,
                "target_id", customer,
                "action", action);
    }

    private List<Map<String, Object>> logs() {
        return jdbc.queryForList(
                "SELECT staff_id, target_type, target_id, action FROM access_log WHERE shop_id = ?",
                shop);
    }

    private long reservation(Long customerId) {
        LocalDateTime start = LocalDateTime.of(2099, 1, 5, 10, 0);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 'STORE', ?)",
                shop,
                customerId,
                worker,
                start,
                start.plusHours(1),
                start.minusDays(1));
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM reservation WHERE shop_id = ?", Long.class, shop);
    }

    /** 현재 근무자는 worker, {@code pin}이면 PIN 인증은 pinStaff */
    private MockHttpSession session(boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }
}
