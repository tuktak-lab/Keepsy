package com.tuktaklab.keepsy.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 개인정보 접근 로그 (Keepsy #12, NFR-06, D2, ARCHITECTURE 6장 「기록에 남는 사람」). 대표 호출은 고객 상세 조회. 실제 MySQL에서 돈다.
 * 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessLogTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired AccessLogger accessLogger;

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

    /** 현재 근무자는 worker, PIN 인증은 pinStaff */
    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        return session;
    }

    private void lockCustomerView(boolean locked) {
        jdbc.update(
                "UPDATE shop SET pin_locked_features = ? WHERE id = ?",
                locked ? "CUSTOMER_VIEW" : "",
                shop);
    }

    private List<Map<String, Object>> logs() {
        return jdbc.queryForList(
                "SELECT staff_id, target_type, target_id, action, ip FROM access_log WHERE shop_id = ?",
                shop);
    }

    @Test
    void 공용_기능으로_고객_상세를_보면_현재_근무자로_대상_ID와_IP를_남긴다() throws Exception {
        lockCustomerView(false);

        mvc.perform(get("/api/customers/{id}", customer).session(session()))
                .andExpect(status().isOk());

        assertThat(logs())
                .singleElement()
                .satisfies(row ->
                        assertThat(row)
                                .containsEntry("staff_id", worker)
                                .containsEntry("target_type", "CUSTOMER")
                                .containsEntry("target_id", customer)
                                .containsEntry("action", "VIEW")
                                .containsEntry("ip", "127.0.0.1"));
    }

    @Test
    void 잠긴_기능으로_보면_PIN_인증_스탭으로_남긴다() throws Exception {
        lockCustomerView(true);

        mvc.perform(get("/api/customers/{id}", customer).session(session()))
                .andExpect(status().isOk());

        assertThat(logs())
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("staff_id", pinStaff));
    }

    @Test
    void 없는_고객이면_404이고_남기지_않는다() throws Exception {
        lockCustomerView(false);

        mvc.perform(get("/api/customers/{id}", customer + 100_000).session(session()))
                .andExpect(status().isNotFound());

        assertThat(logs()).isEmpty();
    }

    @Test
    void 정리는_2년_지난_행만_지운다() {
        LocalDateTime now = LocalDateTime.now();
        for (LocalDateTime at :
                List.of(now.minusYears(2).minusDays(1), now.minusYears(2).plusDays(1))) {
            jdbc.update(
                    "INSERT INTO access_log (shop_id, staff_id, target_type, target_id, action, accessed_at, ip)"
                            + " VALUES (?, ?, 'CUSTOMER', ?, 'VIEW', ?, '127.0.0.1')",
                    shop,
                    worker,
                    customer,
                    at);
        }

        accessLogger.purgeExpired();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM access_log WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isOne();
    }
}
