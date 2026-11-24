package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 동의 재확인 대상 (Keepsy #100 완료 조건, 결정 F45). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CAMPAIGN_VIEW}는 기본
 * PIN이다.
 *
 * <pre>
 * 나옴: overdue(3년 전 동의) · failedOnly(3년 전 동의, 재확인은 실패 · 대조군만) · corrected(정정으로 되살린 동의, 기한 5일 뒤)
 *       · soon(기한 10일 뒤, 야간 광고 동의)
 * 안 나옴: notYet(기한 40일 뒤) · reconfirmed(1년 전 재확인 성공) · withdrawn · never · deleted · 다른 매장
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentReconfirmTargetsTest {

    private static final LocalDate TODAY = LocalDate.now(TimeConfig.ZONE);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long overdue, failedOnly, corrected, soon;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        LocalDateTime threeYearsAgo = TODAY.minusYears(3).atTime(11, 0);

        overdue = customer();
        consent(overdue, "MARKETING", true, threeYearsAgo, null);

        failedOnly = customer();
        consent(failedOnly, "MARKETING", true, threeYearsAgo, null);
        reconfirm(failedOnly, "FAILED", false, TODAY.minusYears(1));
        reconfirm(failedOnly, null, true, TODAY.minusYears(1));

        // 동의 → 잘못 기록한 철회 → 정정: 정정 행은 처리 시각이 아니라 되살린 동의 일시를 가져 앞 행보다 이를 수 있다 (F44)
        corrected = customer();
        LocalDateTime consentedAt = TODAY.minusYears(2).plusDays(5).atTime(9, 0);
        consent(corrected, "MARKETING", true, consentedAt, null);
        consent(corrected, "MARKETING", false, TODAY.minusDays(3).atTime(9, 0), "DIRECT");
        consent(corrected, "MARKETING", true, consentedAt, null);

        soon = customer();
        consent(soon, "MARKETING", true, TODAY.minusYears(2).plusDays(10).atTime(9, 0), null);
        consent(soon, "NIGHT_AD", true, TODAY.minusYears(2).plusDays(10).atTime(9, 0), null);

        long notYet = customer();
        consent(notYet, "MARKETING", true, TODAY.minusYears(2).plusDays(40).atTime(9, 0), null);
        long reconfirmed = customer();
        consent(reconfirmed, "MARKETING", true, threeYearsAgo, null);
        reconfirm(reconfirmed, "SENT", false, TODAY.minusYears(1));
        long withdrawn = customer();
        consent(withdrawn, "MARKETING", true, threeYearsAgo, null);
        consent(withdrawn, "MARKETING", false, TODAY.minusYears(1).atTime(9, 0), "UNSUBSCRIBE");
        customer(); // 동의 이력 없음
        long deleted = customer();
        consent(deleted, "MARKETING", true, threeYearsAgo, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", deleted);
        long other = data.shop();
        long stranger = data.customer(other, false, null);
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at)"
                        + " VALUES (?, ?, 'MARKETING', TRUE, ?)",
                other,
                stranger,
                threeYearsAgo);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 동의_유지_중인_고객만_기한_30일_전부터_기한_순으로_기한이_지나도_남는다() throws Exception {
        String body =
                targets("")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(4))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(JsonPath.<List<Number>>read(body, "$.items[*].customerId"))
                .extracting(Number::longValue)
                .containsExactly(overdue, failedOnly, corrected, soon);
    }

    @Test
    void 항목은_유효_동의일_마지막_재확인_기한_야간_광고_동의와_마스킹한_번호() throws Exception {
        targets("")
                // 실패 · 대조군 재확인은 기준일을 바꾸지 않는다
                .andExpect(jsonPath("$.items[1].lastReconfirmedAt").value(nullValue()))
                .andExpect(
                        jsonPath("$.items[1].dueDate")
                                .value(TODAY.minusYears(3).plusYears(2).toString()))
                // 정정 행은 처리 시각이 아니라 되살린 동의 일시 (F44)
                .andExpect(
                        jsonPath("$.items[2].consentDate")
                                .value(TODAY.minusYears(2).plusDays(5).toString()))
                .andExpect(jsonPath("$.items[2].dueDate").value(TODAY.plusDays(5).toString()))
                .andExpect(jsonPath("$.items[2].nightAdConsent").value(false))
                .andExpect(jsonPath("$.items[3].nightAdConsent").value(true))
                .andExpect(jsonPath("$.items[3].name").value("모의 고객"))
                .andExpect(
                        jsonPath("$.items[3].phone")
                                .value(matchesPattern("010-\\*\\*\\*\\*-\\d{4}")));
    }

    @Test
    void 재확인_발송에_성공하면_그날부터_다음_2년을_센다() throws Exception {
        reconfirm(soon, "SENT", false, TODAY);

        String body = targets("").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(body, "$.items[*].customerId"))
                .extracting(Number::longValue)
                .doesNotContain(soon);
    }

    @Test
    void 페이지를_나누고_PIN_인증이_없으면_pin_required() throws Exception {
        targets("?page=1&size=2")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[0].customerId").value(corrected));
        targets("?size=101").andExpect(status().isBadRequest());

        MockHttpSession noPin = session(false);
        mvc.perform(get("/api/consent-reconfirm-targets").session(noPin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private long customer() {
        return data.customer(shop, false, null);
    }

    private long consent(
            long customerId, String item, boolean agreed, LocalDateTime at, String withdrawalPath) {
        Map<String, Object> row = new HashMap<>();
        row.put("shop_id", shop);
        row.put("customer_id", customerId);
        row.put("item", item);
        row.put("agreed", agreed);
        row.put("recorded_at", at);
        row.put("staff_id", staff);
        row.put("method", "TABLET");
        row.put("withdrawal_path", withdrawalPath);
        return new SimpleJdbcInsert(jdbc)
                .withTableName("consent")
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(row)
                .longValue();
    }

    /** 동의 재확인 안내 발송 기록. 대조군 행은 발송 상태 · 결과 시각이 없다 */
    private void reconfirm(long customerId, String sendStatus, boolean control, LocalDate on) {
        jdbc.update(
                "INSERT INTO campaign_message (shop_id, kind, customer_id, control_group,"
                        + " send_status, result_at) VALUES (?, 'CONSENT_RECONFIRM', ?, ?, ?, ?)",
                shop,
                customerId,
                control,
                sendStatus,
                control ? null : on.atTime(10, 0));
    }

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

    private ResultActions targets(String query) throws Exception {
        return mvc.perform(get("/api/consent-reconfirm-targets" + query).session(session(true)));
    }
}
