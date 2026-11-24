package com.tuktaklab.keepsy.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * 예약 경로 통계 (Keepsy #81, FR-DSH-06, F27). 답을 아는 모의 데이터로 2026년 10월을 직접 지정해 경로별 건수 · 비율을 확인한다. 상태와
 * 관계없이 세고 고객 없는 예약도 넣는다 (E3).
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingChannelsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        long customer = data.customer(shop, false, null);

        // 10월: NAVER 2 · KAKAO 1 · PHONE 1 → 합계 4. STORE · ETC는 0건
        reservation(shop, staff, null, "2026-10-01T00:00", "CONFIRMED", "NAVER"); // 고객 없는 예약
        reservation(shop, staff, null, "2026-10-15T11:00", "CANCELED", "NAVER");
        reservation(shop, staff, customer, "2026-10-20T11:00", "NO_SHOW", "KAKAO");
        reservation(shop, staff, customer, "2026-10-31T23:30", "COMPLETED", "PHONE");
        // 기간 밖
        reservation(shop, staff, customer, "2026-09-30T23:30", "COMPLETED", "STORE");
        reservation(shop, staff, customer, "2026-11-01T00:00", "CONFIRMED", "STORE");

        long other = data.shop(); // 다른 매장은 세지 않는다 (NFR-03)
        long otherStaff = data.staff(other, "ACTIVE", false);
        reservation(other, otherStaff, null, "2026-10-10T11:00", "CONFIRMED", "ETC");
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 기간_안_예약을_상태와_관계없이_경로별로_센다() throws Exception {
        mvc.perform(
                        get("/api/dashboard/booking-channels")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31")
                                .session(session(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-31"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.channels.length()").value(5))
                .andExpect(jsonPath("$.channels[0].channel").value("NAVER"))
                .andExpect(jsonPath("$.channels[0].count").value(2))
                .andExpect(jsonPath("$.channels[0].ratio").value(0.5))
                .andExpect(jsonPath("$.channels[1].channel").value("KAKAO"))
                .andExpect(jsonPath("$.channels[1].ratio").value(0.25))
                .andExpect(jsonPath("$.channels[2].channel").value("PHONE"))
                .andExpect(jsonPath("$.channels[2].count").value(1))
                .andExpect(jsonPath("$.channels[3].channel").value("STORE"))
                .andExpect(jsonPath("$.channels[3].count").value(0))
                .andExpect(jsonPath("$.channels[3].ratio").value(0.0))
                .andExpect(jsonPath("$.channels[4].channel").value("ETC"))
                .andExpect(jsonPath("$.channels[4].count").value(0));
    }

    @Test
    void 예약이_없으면_비율은_0() throws Exception {
        mvc.perform(
                        get("/api/dashboard/booking-channels")
                                .param("period", "CUSTOM")
                                .param("from", "2025-01-01")
                                .param("to", "2025-01-31")
                                .session(session(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.channels.length()").value(5))
                .andExpect(jsonPath("$.channels[0].ratio").value(0.0));
    }

    @Test
    void 정해진_기간도_받는다() throws Exception {
        mvc.perform(
                        get("/api/dashboard/booking-channels")
                                .param("period", "THIS_MONTH")
                                .session(session(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.channels.length()").value(5));
    }

    @Test
    void 기간_값이_틀리면_invalid() throws Exception {
        MockHttpSession s = session(staff);
        mvc.perform(get("/api/dashboard/booking-channels").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(get("/api/dashboard/booking-channels").param("period", "YESTERDAY").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        get("/api/dashboard/booking-channels")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-31")
                                .param("to", "2026-10-01")
                                .session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 근무자가_없으면_worker_required() throws Exception {
        mvc.perform(
                        get("/api/dashboard/booking-channels")
                                .param("period", "TODAY")
                                .session(session(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }

    /** 매장 로그인 + 현재 근무자 (세션을 직접 채운다). 대시보드는 공용 고정이라 PIN이 필요 없다 */
    private MockHttpSession session(Long worker) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        return session;
    }

    private void reservation(
            long shopId,
            long staffId,
            Long customerId,
            String start,
            String status,
            String channel) {
        LocalDateTime at = LocalDateTime.parse(start);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                shopId,
                customerId,
                staffId,
                at,
                at.plusMinutes(30),
                status,
                channel,
                at.minusDays(1));
    }
}
