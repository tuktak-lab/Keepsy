package com.tuktaklab.keepsy.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.reservation.NewReservation;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 매장 기본 지표 (Keepsy #107, FR-ANL-05 · 06, F24). 답을 아는 모의 데이터로 2026년 10월을 직접 지정해 매출 · 방문 수 · 신규 고객 수를
 * 확인한다 (DEVELOPMENT 7장 「지표 계산 검증」).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShopSummaryTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        long menu = data.menu(shop, 60);
        long a = data.customer(shop, false, null);
        long b = data.customer(shop, false, null);
        long c = data.customer(shop, false, null);
        long d = data.customer(shop, false, null);
        long e = data.customer(shop, false, null);

        // 방문: 10월 NORMAL 4건(A · B · D · 고객 없음) → 방문 수 4. 첫 방문이 10월인 고객 A · C · D → 신규 3
        visit(a, "2026-10-05", "NORMAL");
        visit(a, "2026-11-01", "NORMAL"); // 기간 밖
        visit(b, "2026-09-20", "IMPORT"); // B의 첫 방문은 9월 → 신규 아님
        visit(b, "2026-10-10", "NORMAL");
        visit(c, "2026-10-15", "IMPORT"); // 가져온 방문: 방문 수에서 빼고 신규 고객에는 셈
        visit(d, "2026-10-20", "NORMAL");
        visit(null, "2026-10-25", "NORMAL"); // 고객 없는 방문: 방문 수에만
        visit(e, "2026-11-01", "NORMAL"); // 첫 방문이 11월
        jdbc.update(
                "UPDATE customer SET status = 'DELETED' WHERE id = ?", d); // 삭제된 고객도 그대로 센다 (C12)

        long other = data.shop(); // 다른 매장은 세지 않는다 (NFR-03)
        long otherCustomer = data.customer(other, false, null);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source)"
                        + " VALUES (?, ?, '2026-10-05', 'NORMAL')",
                other,
                otherCustomer);

        // 결제: 10,000 + 20,000 + 5,000 = 35,000. 경계 바깥 · 취소 결제는 뺀다
        long r =
                reservations.create(
                        shop,
                        new NewReservation(
                                a,
                                staff,
                                LocalDate.of(2026, 12, 1).atTime(11, 0),
                                null,
                                null,
                                false,
                                null,
                                List.of(menu)),
                        false);
        payment(r, a, 10_000, "2026-10-01T00:00", false);
        payment(r, a, 20_000, "2026-10-31T23:59:59", false);
        payment(r, null, 5_000, "2026-10-25T15:00", false);
        payment(r, a, 40_000, "2026-10-15T12:00", true);
        payment(r, a, 80_000, "2026-11-01T00:00", false);
        payment(r, a, 160_000, "2026-09-30T23:59:59", false);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private void visit(Long customer, String date, String source) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, ?)",
                shop,
                customer,
                LocalDate.parse(date),
                source);
    }

    private void payment(long reservation, Long customer, int amount, String at, boolean canceled) {
        jdbc.update(
                "INSERT INTO payment (shop_id, customer_id, reservation_id, amount, method, paid_at,"
                        + " canceled) VALUES (?, ?, ?, ?, 'CARD', ?, ?)",
                shop,
                customer,
                reservation,
                amount,
                LocalDateTime.parse(at),
                canceled);
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
    void 직접_지정_기간의_매출_방문_수_신규_고객_수() throws Exception {
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31")
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-01"))
                .andExpect(jsonPath("$.to").value("2026-10-31"))
                .andExpect(jsonPath("$.revenue").value(35_000))
                .andExpect(jsonPath("$.visitCount").value(4))
                .andExpect(jsonPath("$.newCustomerCount").value(3));
    }

    /**
     * 주기 내 재방문 (FR-ANL-05, F58). 이탈 신호 기간 끝날 = 종료일 + 이탈 신호 기간이 10월이고 오늘보다 이전인 스냅샷만 센다. 재방문 = 실제 다음
     * 방문일 ≤ 끝날
     */
    @Test
    void 주기_내_재방문은_이탈_신호_기간이_기간_안에_끝난_예측을_카테고리별로_센다() throws Exception {
        long n = data.customer(shop, false, null);
        snapshot(shop, n, "NAIL", "2026-10-01", 10, "2026-10-11"); // 끝날 10/11 당일 → 재방문
        snapshot(shop, n, "NAIL", "2026-10-01", 10, "2026-10-12"); // 하루 늦음
        snapshot(shop, n, "NAIL", "2026-10-01", 10, null); // 안 옴
        snapshot(shop, n, "NAIL", "2026-09-01", 5, "2026-09-03"); // 끝날 9/6 → 기간 밖
        snapshot(shop, n, "NAIL", "2026-10-01", null, "2026-10-02"); // 이탈 신호 기간 없음 → 뺌
        snapshot(shop, n, "LASH", "2026-10-20", 5, "2026-09-28"); // 끝날 10/25, 예측보다 이른 방문도 재방문
        snapshot(shop, n, "LASH", "2026-10-28", 10, null); // 끝날 11/7 → 기간 밖
        long deleted = data.customer(shop, false, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", deleted);
        snapshot(shop, deleted, "NAIL", "2026-10-01", 10, "2026-10-02"); // 삭제된 고객도 센다
        long other = data.shop();
        snapshot(other, data.customer(other, false, null), "NAIL", "2026-10-01", 10, "2026-10-02");

        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-01")
                                .param("to", "2026-10-31")
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleRevisits.length()").value(5))
                .andExpect(jsonPath("$.cycleRevisits[0].category").value("NAIL"))
                .andExpect(jsonPath("$.cycleRevisits[0].endedCount").value(4))
                .andExpect(jsonPath("$.cycleRevisits[0].revisitedCount").value(2))
                .andExpect(jsonPath("$.cycleRevisits[1].category").value("LASH"))
                .andExpect(jsonPath("$.cycleRevisits[1].endedCount").value(1))
                .andExpect(jsonPath("$.cycleRevisits[1].revisitedCount").value(1))
                .andExpect(jsonPath("$.cycleRevisits[2].category").value("WAXING"))
                .andExpect(jsonPath("$.cycleRevisits[2].endedCount").value(0))
                .andExpect(jsonPath("$.cycleRevisits[4].category").value("ETC"));

        // 끝날이 오늘인 예측은 아직 결과가 정해지지 않아 뺀다
        LocalDate today = LocalDate.now(TimeConfig.ZONE);
        snapshot(shop, n, "WAXING", today.minusDays(10).toString(), 10, null);
        snapshot(shop, n, "WAXING", today.minusDays(11).toString(), 10, null);
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", today.minusDays(1).toString())
                                .param("to", today.toString())
                                .session(session(true)))
                .andExpect(jsonPath("$.cycleRevisits[2].endedCount").value(1))
                .andExpect(jsonPath("$.cycleRevisits[2].revisitedCount").value(0));
    }

    private void snapshot(
            long shopId,
            long customer,
            String category,
            String endDate,
            Integer churnSignalDays,
            String actualVisitDate) {
        jdbc.update(
                "INSERT INTO prediction_snapshot (shop_id, customer_id, category, created_at,"
                        + " start_date, end_date, basis_visit_count, churn_signal_days,"
                        + " actual_visit_date) VALUES (?, ?, ?, NOW(), DATE_SUB(?, INTERVAL 6 DAY),"
                        + " ?, 2, ?, ?)",
                shopId,
                customer,
                category,
                endDate,
                endDate,
                churnSignalDays,
                actualVisitDate);
    }

    @Test
    void 하루만_지정해도_경계가_맞다() throws Exception {
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", "2026-11-01")
                                .param("to", "2026-11-01")
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(80_000))
                .andExpect(jsonPath("$.visitCount").value(2))
                .andExpect(jsonPath("$.newCustomerCount").value(1));
    }

    @Test
    void 기간_값이_틀리면_invalid() throws Exception {
        MockHttpSession s = session(true);
        mvc.perform(get("/api/analytics/summary").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(get("/api/analytics/summary").param("period", "YESTERDAY").session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-01")
                                .session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "CUSTOM")
                                .param("from", "2026-10-31")
                                .param("to", "2026-10-01")
                                .session(s))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 정해진_기간은_200이고_PIN_인증이_없으면_pin_required() throws Exception {
        mvc.perform(
                        get("/api/analytics/summary")
                                .param("period", "THIS_WEEK")
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").exists());

        mvc.perform(get("/api/analytics/summary").param("period", "TODAY").session(session(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }
}
