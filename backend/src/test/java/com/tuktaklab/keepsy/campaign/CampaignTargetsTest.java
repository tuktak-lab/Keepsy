package com.tuktaklab.keepsy.campaign;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 대상 미리 계산 · 자동 제외 (Keepsy #88 완료 조건). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 제외 사유 판정 자체의 경계값은 {@code
 * ExclusionReasonTest}.
 *
 * <pre>
 * 네일 행: A 동의 · B 미동의 · C 수신 거부 · D 동의 + 이틀 전 광고 발송 성공(재발송 제한 7일)
 *         E 동의 + 이틀 전 대조군 행 · 실패 발송만 · G 동의지만 삭제된 고객
 * 속눈썹 행만: F 동의
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignTargetsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long a, b, d, e, f, g;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        LocalDate today = LocalDate.now();
        a = customer("NAIL", true, null);
        prediction(a, "NAIL", today.minusDays(20), today.minusDays(1), today.plusDays(5));
        b = customer("NAIL", false, null);
        customer("NAIL", false, "UNSUBSCRIBE");
        d = customer("NAIL", true, null);
        message(d, false, "SENT");
        e = customer("NAIL", true, null);
        message(e, true, null);
        message(e, false, "FAILED");
        f = customer("LASH", true, null);
        g = customer("NAIL", true, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", g);
        // 다른 매장 고객은 나오지 않는다
        long other = data.shop();
        long stranger = data.customer(other, false, null);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category) VALUES (?, ?, 'NAIL')",
                other,
                stranger);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 카테고리_행에서_제외_사유별로_세고_대조군_실패_발송은_재발송_제한에_넣지_않는다() throws Exception {
        targets("{\"category\":\"NAIL\"}", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCount").value(2)) // A, E
                .andExpect(jsonPath("$.excludedCounts.UNSUBSCRIBED").value(1))
                .andExpect(jsonPath("$.excludedCounts.NO_CONSENT").value(1))
                .andExpect(jsonPath("$.excludedCounts.RESEND_LIMIT").value(1));
    }

    @Test
    void 인사이트에서_고른_고객은_그_카테고리_행만_남고_0건도_준다() throws Exception {
        targets("{\"category\":\"NAIL\",\"customerIds\":[%d,%d,%d]}".formatted(a, b, f), true)
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.excludedCounts.NO_CONSENT").value(1))
                .andExpect(jsonPath("$.excludedCounts.UNSUBSCRIBED").value(0))
                .andExpect(jsonPath("$.excludedCounts.RESEND_LIMIT").value(0));
    }

    @Test
    void 예측_상태_마지막_시술일_방문_횟수로_고른다() throws Exception {
        targets("{\"category\":\"NAIL\",\"predictionStatus\":\"DUE\"}", true)
                .andExpect(jsonPath("$.targetCount").value(1)); // A만 주기 도래
        String from = LocalDate.now().minusDays(1).toString();
        targets("{\"category\":\"NAIL\",\"lastServiceFrom\":\"%s\"}".formatted(from), true)
                .andExpect(jsonPath("$.targetCount").value(0))
                .andExpect(
                        jsonPath("$.excludedCounts.NO_CONSENT")
                                .value(0)); // 마지막 시술일이 있는 건 A(20일 전)뿐
        targets("{\"category\":\"NAIL\",\"visitCountMin\":1}", true)
                .andExpect(jsonPath("$.targetCount").value(0)); // 방문 기록이 없다
    }

    @Test
    void 동의_재확인_안내는_재발송_제한을_보지_않는다() throws Exception {
        targets("{\"kind\":\"CONSENT_RECONFIRM\",\"customerIds\":[%d,%d]}".formatted(d, b), true)
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.excludedCounts.NO_CONSENT").value(1))
                .andExpect(jsonPath("$.excludedCounts.RESEND_LIMIT").value(0));
    }

    @Test
    void 고르기_조건이_맞지_않으면_invalid() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    "{\"category\":\"HAIR\"}",
                    "{\"kind\":\"CONSENT_RECONFIRM\",\"category\":\"NAIL\",\"customerIds\":[1]}",
                    "{\"kind\":\"CONSENT_RECONFIRM\"}",
                    "{\"kind\":\"UNSUBSCRIBE_NOTICE\",\"category\":\"NAIL\"}",
                    "{\"category\":\"NAIL\",\"visitCountMin\":3,\"visitCountMax\":1}",
                    "{\"category\":\"NAIL\",\"lastServiceFrom\":\"2026-11-02\",\"lastServiceTo\":\"2026-11-01\"}"
                }) {
            targets(body, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void PIN_인증이_없으면_pin_required() throws Exception {
        targets("{\"category\":\"NAIL\"}", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    /** 그 카테고리 예측 행과 마케팅 동의 이력을 만든다 */
    private long customer(String category, boolean agreed, String withdrawalPath) {
        long id = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category) VALUES (?, ?, ?)",
                shop,
                id,
                category);
        if (agreed || withdrawalPath != null) {
            consent(id, true, null);
        }
        if (withdrawalPath != null) {
            consent(id, false, withdrawalPath);
        }
        return id;
    }

    private void prediction(
            long customerId, String category, LocalDate last, LocalDate start, LocalDate end) {
        jdbc.update(
                "UPDATE customer_prediction SET last_service_date = ?, basis_visit_count = 3,"
                        + " avg_interval_days = 21, start_date = ?, end_date = ?"
                        + " WHERE customer_id = ? AND category = ?",
                last,
                start,
                end,
                customerId,
                category);
    }

    private void consent(long customerId, boolean agreed, String withdrawalPath) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'MARKETING', ?, ?, ?, ?, ?)",
                shop,
                customerId,
                agreed,
                LocalDateTime.now(),
                withdrawalPath == null ? staff : null,
                withdrawalPath == null ? "TABLET" : null,
                withdrawalPath);
    }

    /** 이틀 전 광고 메시지. 대조군 행은 발송 상태 · 결과 시각이 없다 */
    private void message(long customerId, boolean control, String sendStatus) {
        jdbc.update(
                "INSERT INTO campaign_message (shop_id, kind, customer_id, category, control_group,"
                        + " send_status, result_at) VALUES (?, 'AD', ?, 'NAIL', ?, ?, ?)",
                shop,
                customerId,
                control,
                sendStatus,
                control ? null : LocalDateTime.now().minusDays(2));
    }

    private ResultActions targets(String body, boolean pin) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return mvc.perform(
                post("/api/campaigns/targets")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
