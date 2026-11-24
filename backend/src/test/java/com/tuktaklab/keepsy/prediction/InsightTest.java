package com.tuktaklab.keepsy.prediction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 인사이트 목록 · 다음 액션 (Keepsy #77 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 정렬 규칙 자체는 {@link
 * ManagementPrioritySqlTest}, 다음 예약 경계는 {@link CustomerPredictionTest}.
 *
 * <pre>
 * longAbsent  NAIL 장기 미방문 + 동의      → WIN_BACK
 * churn       LASH 이탈 신호 + 동의        → CHECK_IN
 * due         NAIL 주기 도래 + 동의        → REVISIT
 * never       NAIL 주기 도래, 동의 이력 없음 → REQUEST_CONSENT
 * imported    NAIL 주기 도래, 철회 경로 없는 미동의(CSV) → REQUEST_CONSENT
 * withdrawn   NAIL 주기 도래, 직접 철회     → null
 * unsub       NAIL 주기 도래, 수신 거부     → null
 * scheduled   NAIL 예정 + 동의             → null
 * 안 나옴: 예측 없음 · 다음 예약 있는 카테고리 · 삭제된 고객 · 다른 매장
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class InsightTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long longAbsent, churn, due, never, imported, withdrawn, unsub, scheduled;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        long nailMenu = data.menu(shop, 60);

        longAbsent = customer("NAIL", -200, true, null); // 200일 전 종료 → 경과 비율 7.1
        churn = customer("LASH", -10, true, null); // 0.33
        due = customer("NAIL", 3, true, null); // −0.107
        never = customer("NAIL", 3, null, null);
        imported = customer("NAIL", 3, false, null);
        withdrawn = customer("NAIL", 3, false, "DIRECT");
        unsub = customer("NAIL", 3, false, "UNSUBSCRIBE");
        scheduled = customer("NAIL", 12, true, null); // 시작일이 5일 뒤

        long noPrediction = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category) VALUES (?, ?, 'ETC')",
                shop,
                noPrediction);
        long reserved = customer("NAIL", 3, true, null);
        reserve(reserved, nailMenu);
        long deleted = customer("NAIL", 3, true, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", deleted);
        long other = data.shop();
        long stranger = data.customer(other, false, null);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category, last_service_date,"
                        + " avg_interval_days, start_date, end_date) VALUES (?, ?, 'NAIL', ?, 28, ?, ?)",
                other,
                stranger,
                TODAY.minusDays(30),
                TODAY.minusDays(3),
                TODAY.plusDays(3));
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 전체는_우선순위_순이고_상태_동의_조합마다_다음_액션() throws Exception {
        String body =
                insights("")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(8))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // 경과 비율 → 같으면 동의 먼저 → 같으면 고객 순
        assertThat(ids(body))
                .containsExactly(
                        longAbsent, churn, due, never, imported, withdrawn, unsub, scheduled);
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].nextAction"))
                .containsExactly(
                        "WIN_BACK",
                        "CHECK_IN",
                        "REVISIT",
                        "REQUEST_CONSENT",
                        "REQUEST_CONSENT",
                        null,
                        null,
                        null);
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].status"))
                .containsExactly(
                        "LONG_ABSENT",
                        "CHURN_SIGNAL",
                        "DUE",
                        "DUE",
                        "DUE",
                        "DUE",
                        "DUE",
                        "SCHEDULED");
    }

    @Test
    void 항목은_이름_카테고리_마지막_시술일_경과일_예측_기간_수신_동의() throws Exception {
        insights("?status=CHURN_SIGNAL")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(churn))
                .andExpect(jsonPath("$.items[0].name").value("모의 고객"))
                .andExpect(jsonPath("$.items[0].category").value("LASH"))
                .andExpect(
                        jsonPath("$.items[0].lastServiceDate")
                                .value(TODAY.minusDays(40).toString()))
                .andExpect(jsonPath("$.items[0].elapsedDays").value(40))
                .andExpect(jsonPath("$.items[0].startDate").value(TODAY.minusDays(16).toString()))
                .andExpect(jsonPath("$.items[0].endDate").value(TODAY.minusDays(10).toString()))
                .andExpect(jsonPath("$.items[0].marketingConsent").value(true));
        insights("?status=SCHEDULED")
                .andExpect(jsonPath("$.items[0].customerId").value(scheduled))
                .andExpect(jsonPath("$.items[0].nextAction").value(nullValue()));
    }

    @Test
    void 상태_탭과_카테고리로_거르고_페이지를_나눈다() throws Exception {
        assertThat(ids(content(insights("?status=DUE"))))
                .containsExactly(due, never, imported, withdrawn, unsub);
        assertThat(ids(content(insights("?category=LASH")))).containsExactly(churn);
        assertThat(ids(content(insights("?status=DUE&category=LASH")))).isEmpty();

        insights("?status=DUE&page=1&size=2")
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.items[0].customerId").value(imported))
                .andExpect(jsonPath("$.items[1].customerId").value(withdrawn));
    }

    @Test
    void 예측_없음_탭이나_잘못된_필터는_invalid_인사이트를_잠그면_pin_required() throws Exception {
        for (String q :
                List.of("?status=NO_PREDICTION", "?status=SOON", "?category=HAIR", "?size=101")) {
            insights(q)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        jdbc.update("UPDATE shop SET pin_locked_features = 'INSIGHT' WHERE id = ?", shop);
        insights("")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    /**
     * 고객 하나와 예측 한 행. 예측 기간 = 종료일 − 6일 ~ 종료일, 마지막 시술 = 종료일 − 30일, 평균 간격 28일(LASH 30일)
     *
     * @param endInDays 오늘부터 종료일까지 일수 (음수면 지남)
     * @param agreed 마케팅 동의 최신 행. null이면 이력 없음
     * @param withdrawalPath 철회 경로 (미동의일 때)
     */
    private long customer(String category, int endInDays, Boolean agreed, String withdrawalPath) {
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
        if (withdrawalPath != null) {
            consent(id, true, null);
        }
        if (agreed != null) {
            consent(id, agreed, withdrawalPath);
        }
        return id;
    }

    private void consent(long customerId, boolean agreed, String withdrawalPath) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'MARKETING', ?, ?, ?, ?, ?)",
                shop,
                customerId,
                agreed,
                LocalDateTime.now(),
                staff,
                agreed || withdrawalPath == null ? "IMPORT" : "VERBAL",
                withdrawalPath);
    }

    /** 내일 확정 예약 */
    private void reserve(long customerId, long menuId) {
        LocalDateTime start = TODAY.plusDays(1).atTime(10, 0);
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 'STORE', ?)",
                shop,
                customerId,
                staff,
                start,
                start.plusHours(1),
                LocalDateTime.now());
        long reservation =
                jdbc.queryForObject(
                        "SELECT id FROM reservation WHERE customer_id = ?", Long.class, customerId);
        jdbc.update(
                "INSERT INTO reservation_service (shop_id, reservation_id, service_menu_id)"
                        + " VALUES (?, ?, ?)",
                shop,
                reservation,
                menuId);
    }

    private static List<Long> ids(String body) {
        return JsonPath.<List<Number>>read(body, "$.items[*].customerId").stream()
                .map(Number::longValue)
                .toList();
    }

    private static String content(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private ResultActions insights(String query) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(get("/api/insights" + query).session(session));
    }
}
