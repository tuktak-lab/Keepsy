package com.tuktaklab.keepsy.analytics;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * 예측 정확도 (Keepsy #108, FR-ANL-07, F29). 답을 아는 스냅샷(적중 · 빗나감 · 다음 방문 없음 · 아직 안 끝남)으로 끝난 것만 평가되는지와 평균
 * 오차가 예측 중앙일 기준 일수인지 확인한다 (DEVELOPMENT 7장 「지표 계산 검증」). 날짜는 오늘(Asia/Seoul) 기준으로 놓는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PredictionAccuracyTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        long a = data.customer(shop, false, null);
        long deleted = data.customer(shop, false, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", deleted);
        LocalDate d = today.minusDays(20);

        // 평가 대상 4건: 적중 2, 빗나감 1, 다음 방문 없음 1(빗나감, 오차 제외)
        snapshot(shop, a, d, d.plusDays(4), d.plusDays(1), true); // 중앙 d+2 → 오차 1
        snapshot(shop, a, d, d.plusDays(3), d.plusDays(6), false); // 중앙 d+1.5 → 오차 4.5
        snapshot(shop, a, d, d.plusDays(5), null, null); // 끝났는데 다음 방문 없음
        snapshot(shop, deleted, d, d.plusDays(2), d.plusDays(1), true); // 삭제된 고객, 오차 0
        // 평균 오차 (1 + 4.5 + 0) / 3 = 1.83 → 1.8

        // 빼는 것: 종료일이 오늘(아직 안 끝남), 오늘 뒤, 기간 밖, 다른 매장
        snapshot(shop, a, today.minusDays(3), today, today.minusDays(1), true);
        snapshot(shop, a, today, today.plusDays(3), null, null);
        snapshot(shop, a, today.minusDays(50), today.minusDays(40), today.minusDays(45), true);
        long other = data.shop();
        snapshot(other, data.customer(other, false, null), d, d.plusDays(4), d.plusDays(1), true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private void snapshot(
            long shopId,
            long customer,
            LocalDate start,
            LocalDate end,
            LocalDate actual,
            Boolean hit) {
        jdbc.update(
                "INSERT INTO prediction_snapshot (shop_id, customer_id, category, created_at,"
                        + " start_date, end_date, basis_visit_count, actual_visit_date, hit)"
                        + " VALUES (?, ?, 'NAIL', NOW(), ?, ?, 2, ?, ?)",
                shopId,
                customer,
                start,
                end,
                actual,
                hit);
    }

    /** 매출 · 성과 분석은 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (명세서 2장) */
    private MockHttpSession session(boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    @Test
    void 끝난_예측만_적중률과_평균_오차를_센다() throws Exception {
        mvc.perform(
                        get("/api/analytics/prediction-accuracy")
                                .param("period", "CUSTOM")
                                .param("from", today.minusDays(30).toString())
                                .param("to", today.plusDays(10).toString())
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.minusDays(30).toString()))
                .andExpect(jsonPath("$.evaluatedCount").value(4))
                .andExpect(jsonPath("$.hitCount").value(2))
                .andExpect(jsonPath("$.averageErrorDays").value(1.8));
    }

    @Test
    void 끝난_예측이_없으면_0건이고_평균_오차는_null() throws Exception {
        mvc.perform(
                        get("/api/analytics/prediction-accuracy")
                                .param("period", "CUSTOM")
                                .param("from", today.toString())
                                .param("to", today.plusDays(10).toString())
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedCount").value(0))
                .andExpect(jsonPath("$.hitCount").value(0))
                .andExpect(jsonPath("$.averageErrorDays").value(nullValue()));
    }

    @Test
    void 기간이_틀리면_invalid이고_PIN_인증이_없으면_pin_required() throws Exception {
        mvc.perform(
                        get("/api/analytics/prediction-accuracy")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-31")
                                .param("to", "2026-10-01")
                                .session(session(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        mvc.perform(
                        get("/api/analytics/prediction-accuracy")
                                .param("period", "THIS_MONTH")
                                .session(session(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }
}
