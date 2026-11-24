package com.tuktaklab.keepsy.campaign;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 캠페인 목록 · 상세 (Keepsy #90 완료 조건). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CAMPAIGN_VIEW}는 기본 PIN이다.
 *
 * <pre>
 * draft    작성 중 · 광고 · 네일, 대상 3명(대조군 1), 발송 기록 없음
 * sent     발송 완료 · 안내, 검토함. 발송 결과: a 성공 · b 실패 · c 대조군 · d 삭제된 고객 성공
 * canceled 취소(일 발송 한도 초과)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignListTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 11, 20, 14, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long draft;
    private long sent;
    private long canceled;
    private long a, b, c, d;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose) VALUES (?, '모의', '{고객명}님', 'REVISIT')",
                shop);
        long template =
                jdbc.queryForObject(
                        "SELECT id FROM message_template WHERE shop_id = ?", Long.class, shop);
        a = data.customer(shop, false, null);
        b = data.customer(shop, false, null);
        c = data.customer(shop, false, null);
        d = data.customer(shop, false, null);

        draft = campaign(template, "11월 재방문", "NAIL", "AD", "DRAFT");
        target(draft, a, false);
        target(draft, b, false);
        target(draft, c, true);

        sent = campaign(template, "동의 재확인", null, "INFO", "SENT");
        jdbc.update(
                "UPDATE campaign SET reviewed_by_staff_id = ?, reviewed_at = ?, sent_at = ? WHERE id = ?",
                staff,
                SENT_AT.minusHours(1),
                SENT_AT,
                sent);
        message(sent, a, false, "SENT", null);
        message(sent, b, false, "FAILED", "번호 형식 오류");
        message(sent, c, true, null, null);
        message(sent, d, false, "SENT", null);
        // 삭제된 고객: 이름 · 번호를 비우고 발송 기록의 수신 번호도 비운다 (C12)
        jdbc.update(
                "UPDATE customer SET status = 'DELETED', name = NULL, phone = NULL WHERE id = ?",
                d);
        jdbc.update("UPDATE campaign_message SET recipient_phone = NULL WHERE customer_id = ?", d);

        canceled = campaign(template, "예약 발송", "LASH", "AD", "CANCELED");
        jdbc.update(
                "UPDATE campaign SET cancel_reason = 'DAILY_LIMIT_EXCEEDED', canceled_at = ? WHERE id = ?",
                SENT_AT,
                canceled);

        long other = data.shop();
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose) VALUES (?, '모의', '{고객명}님', 'REVISIT')",
                other);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 목록은_최근_만든_순이고_대상_대조군_수_상태_취소_사유() throws Exception {
        campaigns("/api/campaigns", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].id").value(canceled))
                .andExpect(jsonPath("$.items[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.items[0].cancelReason").value("DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.items[1].id").value(sent))
                .andExpect(jsonPath("$.items[1].category").value(nullValue()))
                .andExpect(jsonPath("$.items[1].messageKind").value("INFO"))
                .andExpect(jsonPath("$.items[1].sentAt").value("2026-11-20T14:00:00+09:00"))
                .andExpect(jsonPath("$.items[2].id").value(draft))
                .andExpect(jsonPath("$.items[2].targetCount").value(3))
                .andExpect(jsonPath("$.items[2].controlCount").value(1))
                .andExpect(jsonPath("$.items[2].scheduledAt").value(nullValue()));
        campaigns("/api/campaigns?page=1&size=2", true)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(draft));
    }

    @Test
    void 상세는_고객별_성공_실패_사유_대조군과_삭제된_고객을_구분하고_번호는_마스킹() throws Exception {
        campaigns("/api/campaigns/" + sent, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").isNumber())
                .andExpect(jsonPath("$.reviewedBy.id").value(staff))
                .andExpect(jsonPath("$.reviewedAt").value("2026-11-20T13:00:00+09:00"))
                .andExpect(jsonPath("$.messages.totalElements").value(4))
                .andExpect(jsonPath("$.messages.items[0].customerId").value(a))
                .andExpect(jsonPath("$.messages.items[0].sendStatus").value("SENT"))
                .andExpect(
                        jsonPath("$.messages.items[0].phone")
                                .value(matchesPattern("010-\\*\\*\\*\\*-\\d{4}")))
                .andExpect(jsonPath("$.messages.items[0].customerDeleted").value(false))
                .andExpect(jsonPath("$.messages.items[1].sendStatus").value("FAILED"))
                .andExpect(jsonPath("$.messages.items[1].failureReason").value("번호 형식 오류"))
                .andExpect(jsonPath("$.messages.items[2].controlGroup").value(true))
                .andExpect(jsonPath("$.messages.items[2].sendStatus").value(nullValue()))
                .andExpect(jsonPath("$.messages.items[3].customerDeleted").value(true))
                .andExpect(jsonPath("$.messages.items[3].customerName").value(nullValue()))
                .andExpect(jsonPath("$.messages.items[3].phone").value(nullValue()));
        campaigns("/api/campaigns/" + sent + "?page=1&size=3", true)
                .andExpect(jsonPath("$.messages.items.length()").value(1))
                .andExpect(jsonPath("$.messages.items[0].customerId").value(d));
    }

    @Test
    void 발송_전_캠페인은_고객별_결과가_빈_페이지() throws Exception {
        campaigns("/api/campaigns/" + draft, true)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.reviewedBy").value(nullValue()))
                .andExpect(jsonPath("$.messages.totalElements").value(0));
    }

    @Test
    void 다른_매장_없는_캠페인은_not_found_PIN_인증이_없으면_pin_required() throws Exception {
        long other = data.shop();
        MockHttpSession otherSession = session(other, true);
        mvc.perform(get("/api/campaigns/" + sent).session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/campaigns").session(otherSession))
                .andExpect(jsonPath("$.totalElements").value(0));
        campaigns("/api/campaigns/" + Long.MAX_VALUE, true).andExpect(status().isNotFound());
        campaigns("/api/campaigns", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private long campaign(long template, String name, String category, String kind, String status) {
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                shop,
                name,
                category,
                template,
                kind,
                status);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM campaign WHERE shop_id = ?", Long.class, shop);
    }

    private void target(long campaign, long customer, boolean control) {
        jdbc.update(
                "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                        + " VALUES (?, ?, ?, ?)",
                shop,
                campaign,
                customer,
                control);
    }

    private void message(
            long campaign, long customer, boolean control, String sendStatus, String reason) {
        jdbc.update(
                "INSERT INTO campaign_message (shop_id, campaign_id, kind, customer_id, control_group,"
                        + " recipient_phone, send_status, failure_reason, result_at)"
                        + " SELECT ?, ?, 'CONSENT_RECONFIRM', id, ?, phone, ?, ?, ? FROM customer WHERE id = ?",
                shop,
                campaign,
                control,
                sendStatus,
                reason,
                control ? null : SENT_AT.plusSeconds(5),
                customer);
    }

    private MockHttpSession session(long shopId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private ResultActions campaigns(String url, boolean pin) throws Exception {
        return mvc.perform(get(url).session(session(shop, pin)));
    }
}
