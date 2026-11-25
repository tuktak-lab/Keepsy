package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 오늘 관리할 고객 (Keepsy #80 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 고르는 SQL은 인사이트와 같아서 다음 예약 · 예측 없음
 * · 다른 매장 제외는 {@link InsightTest}가 본다.
 *
 * <pre>
 * churn     LASH 이탈 신호 + 동의       → 1번째, CHECK_IN
 * due[0~10] NAIL 주기 도래 + 동의 11명  → 앞 9명만, REVISIT
 * 안 나옴: 장기 미방문 · 예정 · 관리 종료(우선순위가 가장 높아도) · 삭제된 고객
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CareTargetTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long churn;
    private final List<Long> due = new ArrayList<>();

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);

        customer("NAIL", -200); // 장기 미방문
        customer("NAIL", 12); // 예정
        long closed = customer("LASH", -20); // 이탈 신호 중 경과 비율이 가장 높음
        jdbc.update(
                "UPDATE customer_prediction SET handling_mark = 'CLOSED' WHERE customer_id = ?",
                closed);
        long deleted = customer("LASH", -20);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", deleted);

        churn = customer("LASH", -10);
        for (int i = 0; i < 11; i++) {
            due.add(customer("NAIL", 3));
        }
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 주기_도래_이탈_신호_중_우선순위_상위_10행과_추천_액션() throws Exception {
        String body =
                careTargets()
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(10))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<Long> expected = new ArrayList<>(List.of(churn));
        expected.addAll(due.subList(0, 9));
        assertThat(
                        JsonPath.<List<Number>>read(body, "$[*].customerId").stream()
                                .map(Number::longValue)
                                .toList())
                .containsExactlyElementsOf(expected);
        assertThat(JsonPath.<String>read(body, "$[0].status")).isEqualTo("CHURN_SIGNAL");
        assertThat(JsonPath.<String>read(body, "$[0].nextAction")).isEqualTo("CHECK_IN");
        assertThat(JsonPath.<List<String>>read(body, "$[1:].status")).containsOnly("DUE");
        assertThat(JsonPath.<List<String>>read(body, "$[1:].nextAction")).containsOnly("REVISIT");
    }

    @Test
    void 행이_없으면_빈_배열_인사이트를_잠그면_pin_required() throws Exception {
        jdbc.update(
                "UPDATE customer_prediction SET handling_mark = 'CLOSED' WHERE shop_id = ?", shop);
        careTargets().andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        jdbc.update("UPDATE shop SET pin_locked_features = 'INSIGHT' WHERE id = ?", shop);
        careTargets()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    /**
     * 마케팅 동의 고객 하나와 예측 한 행. 예측 기간 = 종료일 − 6일 ~ 종료일, 마지막 시술 = 종료일 − 30일
     *
     * @param endInDays 오늘부터 종료일까지 일수 (음수면 지남)
     */
    private long customer(String category, int endInDays) {
        long id = data.customer(shop, false, null);
        LocalDate end = TODAY.plusDays(endInDays);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category, last_service_date,"
                        + " basis_visit_count, avg_interval_days, start_date, end_date)"
                        + " VALUES (?, ?, ?, ?, 3, ?, ?, ?)",
                shop,
                id,
                category,
                end.minusDays(30),
                "LASH".equals(category) ? 30 : 28,
                end.minusDays(6),
                end);
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method) VALUES (?, ?, 'MARKETING', TRUE, ?, ?, 'IMPORT')",
                shop,
                id,
                LocalDateTime.now(),
                staff);
        return id;
    }

    private ResultActions careTargets() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(get("/api/dashboard/care-targets").session(session));
    }
}
