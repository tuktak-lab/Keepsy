package com.tuktaklab.keepsy.customer;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 파기 대상 목록 (Keepsy #51 완료 조건 · 검증 방법, D3). 실제 MySQL에서 돌고 모의 데이터만 쓴다. 보관 기간 3년 기준.
 *
 * <pre>
 * visit: 3년 + 1일 전 방문, 최근 예약 있음 → VISIT (방문이 예약보다 앞선다)
 * edge: 정확히 3년 전 방문 → 빠짐 (기준일 + 3년이 오늘)
 * rsv: 방문 없음, 3년 + 10일 전 취소 예약 → RESERVATION (상태 무관)
 * reg: 방문 · 예약 없음, 3년 + 20일 전 등록 → REGISTERED
 * recent: 2년 전 등록 → 3년이면 빠지고 1년이면 들어감
 * deleted: 오래전 등록, 삭제됨 → 빠짐
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionExpiredTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long owner;
    private LocalDate today;
    private long visit;
    private long edge;
    private long rsv;
    private long reg;
    private long recent;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        today = LocalDate.now(clock);
        jdbc.update("UPDATE shop SET retention_years = 3 WHERE id = ?", shop);
        LocalDate old = today.minusYears(10);

        visit = customer(old);
        visit(visit, today.minusYears(3).minusDays(1));
        reservation(visit, today.plusDays(1), "CONFIRMED");
        edge = customer(old);
        visit(edge, today.minusYears(3));
        rsv = customer(old);
        reservation(rsv, today.minusYears(3).minusDays(10), "CANCELED");
        reg = customer(today.minusYears(3).minusDays(20));
        recent = customer(today.minusYears(2));
        long deleted = customer(old);
        jdbc.update(
                "UPDATE customer SET status = 'DELETED', name = NULL, phone = NULL WHERE id = ?",
                deleted);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 기준일은_방문_예약_등록일_순이고_오래된_순으로_준다() throws Exception {
        list(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(
                        jsonPath("$.items[*].customerId")
                                .value(contains((int) reg, (int) rsv, (int) visit)))
                .andExpect(
                        jsonPath("$.items[*].baseKind")
                                .value(contains("REGISTERED", "RESERVATION", "VISIT")))
                .andExpect(
                        jsonPath("$.items[2].baseDate")
                                .value(today.minusYears(3).minusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].name").value("모의 고객"))
                .andExpect(
                        jsonPath("$.items[0].phone")
                                .value(matchesPattern("010-\\*\\*\\*\\*-\\d{4}")));
    }

    @Test
    void 보관_기간을_바꾸면_목록이_바로_달라진다() throws Exception {
        jdbc.update("UPDATE shop SET retention_years = 1 WHERE id = ?", shop);

        list(true)
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(
                        jsonPath("$.items[*].customerId")
                                .value(
                                        contains(
                                                (int) reg,
                                                (int) rsv,
                                                (int) visit,
                                                (int) edge,
                                                (int) recent)));
    }

    @Test
    void 원장_PIN이_아니면_owner_pin_required() throws Exception {
        list(false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    private long customer(LocalDate registered) {
        long id = data.customer(shop, false, null);
        jdbc.update(
                "UPDATE customer SET registered_at = ? WHERE id = ?", registered.atTime(9, 0), id);
        return id;
    }

    private void visit(long customerId, LocalDate date) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                date);
    }

    private void reservation(long customerId, LocalDate date, String status) {
        LocalDateTime start = date.atTime(11, 0);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customerId,
                owner,
                start,
                start.plusHours(1),
                status,
                start.minusDays(1));
    }

    private ResultActions list(boolean ownerPin) throws Exception {
        long staff = ownerPin ? owner : data.staff(shop, "ACTIVE", false);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        return mvc.perform(get("/api/customers/retention-expired").session(session));
    }
}
