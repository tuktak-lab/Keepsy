package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * POST /api/campaigns/{campaignId}/send 즉시 발송 (Keepsy #96, CONTRACTS 6장 CMP, FR-CMP-08 · 18, F7 ·
 * F51). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. 가상 발송기 스케줄러(3초마다)가 언제 돌아도 결과가 같게, 발송 행은 PENDING · SENT를 함께
 * "보냄"으로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignSendTest {

    private static final String BODY = "{고객명}님, {매장명}입니다.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired CampaignSendService sendService;
    @Autowired VirtualMessageSender sender;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        jdbc.update(
                "UPDATE shop SET phone = '02-000-0000', unsubscribe_number = '080-000-0000' WHERE id = ?",
                shop);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 검토된_광고를_보내면_동의한_고객만_대기로_만들고_빠진_고객은_사유와_실패로_대조군은_대조군_행으로_남긴다() throws Exception {
        long a = agreedCustomer();
        long b = agreedCustomer();
        long withdrawn = agreedCustomer();
        consent(withdrawn, false);
        long deleted = agreedCustomer();
        jdbc.update("UPDATE customer SET status = 'DELETED', phone = NULL WHERE id = ?", deleted);
        long control = agreedCustomer();
        long campaign = campaign(BODY, List.of(a, b, withdrawn, deleted), List.of(control), true);

        send(campaign)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(2));

        assertThat(row(campaign, a))
                .containsEntry("kind", "AD")
                .containsEntry("category", "NAIL")
                .containsEntry("control_group", false);
        assertThat((String) row(campaign, a).get("send_status")).isIn("PENDING", "SENT");
        assertThat((String) row(campaign, a).get("body"))
                .startsWith("(광고)")
                .contains("님, 모의 매장입니다.");
        assertThat(row(campaign, withdrawn))
                .containsEntry("send_status", "FAILED")
                .containsEntry("failure_reason", CampaignSendService.NO_MARKETING_CONSENT);
        assertThat(row(campaign, deleted))
                .containsEntry("send_status", "FAILED")
                .containsEntry("failure_reason", CampaignSendService.DELETED_CUSTOMER);
        assertThat(row(campaign, control))
                .containsEntry("control_group", true)
                .containsEntry("send_status", null)
                .containsEntry("body", null);
        assertThat(campaignRow(campaign).get("sent_at")).isNotNull();

        // 가상 발송기가 대기 행을 다 처리하면 캠페인은 발송 완료 (F7)
        sender.deliverPending();
        assertThat(row(campaign, a)).containsEntry("send_status", "SENT");
        assertThat(campaignRow(campaign)).containsEntry("status", "SENT");
    }

    @Test
    void 같은_캠페인을_다시_보내면_conflict이고_발송_기록은_그대로() throws Exception {
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        send(campaign).andExpect(status().isOk());

        send(campaign)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        assertThat(rowCount(campaign)).isOne();
    }

    @Test
    void 동시에_두_번_보내도_한_번만_발송된다() throws Exception {
        long campaign =
                campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        Callable<String> attempt =
                () -> {
                    try {
                        sendService.send(shop, campaign);
                        return "ok";
                    } catch (ApiException e) {
                        return e.errorCode().name();
                    }
                };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> results = new ArrayList<>();
        try {
            for (Future<String> f : pool.invokeAll(List.of(attempt, attempt))) {
                results.add(f.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(results).containsExactlyInAnyOrder("ok", ErrorCode.CONFLICT.name());
        assertThat(rowCount(campaign)).isEqualTo(2);
    }

    @Test
    void 검토_기록이_없으면_review_required이고_작성_중_그대로() throws Exception {
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), false);

        send(campaign)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("review_required"));
        assertThat(campaignRow(campaign)).containsEntry("status", "DRAFT");
        assertThat(rowCount(campaign)).isZero();
    }

    @Test
    void 광고인데_매장_번호가_없으면_conflict이고_작성_중_그대로() throws Exception {
        jdbc.update("UPDATE shop SET unsubscribe_number = NULL WHERE id = ?", shop);
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);

        send(campaign).andExpect(status().isConflict());
        assertThat(campaignRow(campaign)).containsEntry("status", "DRAFT");
    }

    @Test
    void 모두_빠지거나_발송_불가여도_진행하고_바로_발송_완료() throws Exception {
        long withdrawn = agreedCustomer();
        consent(withdrawn, false);
        long noValue = agreedCustomer(); // {최근시술}은 예측이 없어 값이 없다
        long campaign = campaign("{고객명}님 {최근시술}", List.of(withdrawn, noValue), List.of(), true);

        send(campaign)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(2));
        assertThat(row(campaign, noValue))
                .containsEntry("failure_reason", CampaignSendService.UNSENDABLE);
        assertThat(campaignRow(campaign)).containsEntry("status", "SENT");
    }

    @Test
    void 다른_매장_캠페인은_not_found_PIN이_없으면_pin_required() throws Exception {
        long otherShop = data.shop();
        long other = campaignIn(otherShop, BODY, List.of(), true);
        send(other).andExpect(status().isNotFound());

        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        mvc.perform(post("/api/campaigns/{id}/send", campaign).session(session(false)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        assertThat(campaignRow(campaign)).containsEntry("status", "DRAFT");
    }

    private ResultActions send(long campaignId) throws Exception {
        return mvc.perform(
                post("/api/campaigns/{id}/send", campaignId).session(session(true)).with(csrf()));
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

    private long agreedCustomer() {
        long id = data.customer(shop, false, null);
        consent(id, true);
        return id;
    }

    private void consent(long customerId, boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'MARKETING', ?, ?, ?, ?, ?)",
                shop,
                customerId,
                agreed,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                staff,
                agreed ? "TABLET" : "VERBAL",
                agreed ? null : "DIRECT");
    }

    private long campaign(String body, List<Long> send, List<Long> control, boolean reviewed) {
        long id = campaignIn(shop, body, send, reviewed);
        for (long c : control) {
            jdbc.update(
                    "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                            + " VALUES (?, ?, ?, TRUE)",
                    shop,
                    id,
                    c);
        }
        return id;
    }

    private long campaignIn(long shopId, String body, List<Long> send, boolean reviewed) {
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose) VALUES (?, '모의', ?, 'REVISIT')",
                shopId,
                body);
        long template =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM message_template WHERE shop_id = ?",
                        Long.class,
                        shopId);
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind,"
                        + " reviewed_by_staff_id, reviewed_at) VALUES (?, '모의', 'NAIL', ?, 'AD', ?, ?)",
                shopId,
                template,
                reviewed && shopId == shop ? staff : null,
                reviewed && shopId == shop ? LocalDateTime.now() : null);
        long id =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM campaign WHERE shop_id = ?", Long.class, shopId);
        for (long c : send) {
            jdbc.update(
                    "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                            + " VALUES (?, ?, ?, FALSE)",
                    shopId,
                    id,
                    c);
        }
        return id;
    }

    private Map<String, Object> row(long campaignId, long customerId) {
        return jdbc.queryForMap(
                "SELECT kind, category, control_group, send_status, failure_reason, body"
                        + " FROM campaign_message WHERE campaign_id = ? AND customer_id = ?",
                campaignId,
                customerId);
    }

    private int rowCount(long campaignId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign_message WHERE campaign_id = ?",
                Integer.class,
                campaignId);
    }

    private Map<String, Object> campaignRow(long campaignId) {
        return jdbc.queryForMap("SELECT status, sent_at FROM campaign WHERE id = ?", campaignId);
    }
}
