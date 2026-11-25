package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
    @Autowired MutableClock clock;
    @Autowired ScheduledCampaignRunner runner;

    /** 야간 제한(F52)이 CI 시각(UTC)에 따라 흔들리지 않게 시계를 고정한다. 기본은 서울 오늘 14:00 */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    /** 테스트에서 시각을 바꿀 수 있는 서울 시계 */
    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock() {
            at(LocalDate.now(TimeConfig.ZONE).atTime(14, 0));
        }

        void at(LocalDateTime seoul) {
            now = seoul.atZone(TimeConfig.ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return TimeConfig.ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

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
        clock.at(LocalDate.now(TimeConfig.ZONE).atTime(14, 0));
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
    void 야간에도_광고이고_보낼_고객_전원이_야간_광고_동의면_보낸다() throws Exception {
        clock.at(LocalDate.now(TimeConfig.ZONE).atTime(22, 0));
        long a = nightConsentCustomer();
        long b = nightConsentCustomer();
        // 빠지는 고객 · 대조군은 야간 광고 동의가 없어도 막지 않는다 (F52)
        long withdrawn = agreedCustomer();
        consent(withdrawn, false);
        long control = agreedCustomer();
        long campaign = campaign(BODY, List.of(a, b, withdrawn), List.of(control), true);

        send(campaign)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(1));
    }

    @Test
    void 야간에_보낼_고객_중_한_명이라도_야간_광고_동의가_없으면_night_send_blocked이고_그대로() throws Exception {
        clock.at(LocalDate.now(TimeConfig.ZONE).atTime(21, 0));
        long campaign =
                campaign(BODY, List.of(nightConsentCustomer(), agreedCustomer()), List.of(), true);

        send(campaign)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("night_send_blocked"));
        assertThat(campaignRow(campaign)).containsEntry("status", "DRAFT");
        assertThat(rowCount(campaign)).isZero();

        // 08:00부터는 낮이라 보낸다
        clock.at(LocalDate.now(TimeConfig.ZONE).plusDays(1).atTime(8, 0));
        send(campaign).andExpect(status().isOk());
    }

    @Test
    void 동의_재확인_안내는_야간_예외가_없다() throws Exception {
        clock.at(LocalDate.now(TimeConfig.ZONE).atTime(23, 0));
        long campaign = infoCampaign(List.of(nightConsentCustomer()));

        send(campaign)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("night_send_blocked"));
    }

    @Test
    void 오늘_보낸_건수와_이번_인원의_합이_한도를_넘으면_전체_거부하고_그대로() throws Exception {
        dailyLimit(3);
        long first = campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        send(first).andExpect(status().isOk());

        long second = campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        send(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("daily_limit_exceeded"));
        assertThat(campaignRow(second)).containsEntry("status", "DRAFT");
        assertThat(rowCount(second)).isZero();

        long third = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        send(third).andExpect(status().isOk());
    }

    @Test
    void 대조군과_발송_시점에_빠진_고객은_한도_건수에_넣지_않는다() throws Exception {
        dailyLimit(2);
        long withdrawn = agreedCustomer();
        consent(withdrawn, false);
        long first =
                campaign(
                        BODY,
                        List.of(agreedCustomer(), agreedCustomer(), withdrawn),
                        List.of(agreedCustomer()),
                        true);
        send(first).andExpect(status().isOk()).andExpect(jsonPath("$.sendCount").value(2));

        // 오늘 건수 2 + 1 > 2
        long second = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        send(second).andExpect(jsonPath("$.error.code").value("daily_limit_exceeded"));
    }

    @Test
    void 한도_근처에서_두_캠페인을_동시에_보내도_합계가_한도를_넘지_않는다() throws Exception {
        dailyLimit(3);
        long a = campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        long b = campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        List<Callable<String>> attempts = new ArrayList<>();
        for (long id : List.of(a, b)) {
            attempts.add(() -> {
                try {
                    sendService.send(shop, id);
                    return "ok";
                } catch (ApiException e) {
                    return e.errorCode().name();
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> results = new ArrayList<>();
        try {
            for (Future<String> f : pool.invokeAll(attempts)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(results).containsExactlyInAnyOrder("ok", ErrorCode.DAILY_LIMIT_EXCEEDED.name());
        assertThat(rowCount(a) + rowCount(b)).isEqualTo(2);
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

    @Test
    void 이미_보낸_캠페인을_야간에_다시_보내도_conflict() throws Exception {
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        send(campaign).andExpect(status().isOk());

        clock.at(today().atTime(22, 0));
        send(campaign).andExpect(jsonPath("$.error.code").value("conflict"));
    }

    // ---------- 예약 발송 (Keepsy #97, F56) ----------

    @Test
    void 예약하면_예약_발송_대기이고_예약_시각이_되면_스케줄러가_보낸다() throws Exception {
        long a = agreedCustomer();
        long control = agreedCustomer();
        long campaign = campaign(BODY, List.of(a), List.of(control), true);

        schedule(campaign, today().atTime(15, 0)).andExpect(status().isNoContent());
        assertThat(campaignRow(campaign))
                .containsEntry("status", "SCHEDULED")
                .containsEntry("scheduled_at", today().atTime(15, 0));

        runner.run(); // 아직 14:00
        assertThat(rowCount(campaign)).isZero();

        clock.at(today().atTime(15, 0));
        runner.run();
        assertThat((String) row(campaign, a).get("send_status")).isIn("PENDING", "SENT");
        assertThat(row(campaign, control)).containsEntry("control_group", true);
        assertThat((String) campaignRow(campaign).get("status")).isIn("SENDING", "SENT");
    }

    @Test
    void 예약은_같은_API로_다시_지정하고_지난_시각_검토_없음_발송_뒤는_거부한다() throws Exception {
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        schedule(campaign, today().atTime(15, 0)).andExpect(status().isNoContent());
        schedule(campaign, today().atTime(16, 30)).andExpect(status().isNoContent());
        assertThat(campaignRow(campaign)).containsEntry("scheduled_at", today().atTime(16, 30));

        schedule(campaign, today().atTime(14, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        put("/api/campaigns/{id}/schedule", campaign)
                                .session(session(true))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        long unreviewed = campaign(BODY, List.of(agreedCustomer()), List.of(), false);
        schedule(unreviewed, today().atTime(15, 0))
                .andExpect(jsonPath("$.error.code").value("review_required"));

        send(campaign).andExpect(status().isOk()); // 예약 발송 대기도 즉시 발송할 수 있다
        schedule(campaign, today().atTime(17, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void 야간_시각은_보낼_고객_전원이_야간_광고_동의인_광고만_예약할_수_있다() throws Exception {
        long mixed =
                campaign(BODY, List.of(nightConsentCustomer(), agreedCustomer()), List.of(), true);
        schedule(mixed, today().atTime(22, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("night_send_blocked"));
        assertThat(campaignRow(mixed)).containsEntry("status", "DRAFT");

        long info = infoCampaign(List.of(nightConsentCustomer()));
        schedule(info, today().atTime(22, 0))
                .andExpect(jsonPath("$.error.code").value("night_send_blocked"));

        long ok = campaign(BODY, List.of(nightConsentCustomer()), List.of(), true);
        schedule(ok, today().atTime(22, 0)).andExpect(status().isNoContent());
    }

    @Test
    void 야간_예약_광고는_발송_시점에_야간_광고_동의가_없어진_고객만_빼고_보낸다() throws Exception {
        long keep = nightConsentCustomer();
        long withdrew = nightConsentCustomer();
        long campaign = campaign(BODY, List.of(keep, withdrew), List.of(), true);
        schedule(campaign, today().atTime(22, 0)).andExpect(status().isNoContent());
        nightConsent(withdrew, false);

        clock.at(today().atTime(22, 0));
        runner.run();

        assertThat((String) row(campaign, keep).get("send_status")).isIn("PENDING", "SENT");
        assertThat(row(campaign, withdrew))
                .containsEntry("send_status", "FAILED")
                .containsEntry("failure_reason", CampaignSendService.NO_NIGHT_AD_CONSENT);
    }

    @Test
    void 발송_시점에_일_발송_한도를_넘으면_기록_없이_취소하고_사유를_남긴다() throws Exception {
        long campaign =
                campaign(BODY, List.of(agreedCustomer(), agreedCustomer()), List.of(), true);
        schedule(campaign, today().atTime(15, 0)).andExpect(status().isNoContent());
        dailyLimit(1);

        clock.at(today().atTime(15, 0));
        runner.run();

        assertThat(campaignRow(campaign))
                .containsEntry("status", "CANCELED")
                .containsEntry("cancel_reason", "DAILY_LIMIT_EXCEEDED");
        assertThat(campaignRow(campaign).get("canceled_at")).isNotNull();
        assertThat(rowCount(campaign)).isZero();
    }

    @Test
    void 발송_시점에_광고_표기용_매장_정보가_없으면_기록_없이_취소한다() throws Exception {
        long campaign = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        schedule(campaign, today().atTime(15, 0)).andExpect(status().isNoContent());
        jdbc.update("UPDATE shop SET phone = NULL WHERE id = ?", shop);

        clock.at(today().atTime(15, 0));
        runner.run();

        assertThat(campaignRow(campaign))
                .containsEntry("status", "CANCELED")
                .containsEntry("cancel_reason", "SHOP_INFO_MISSING");
        assertThat(rowCount(campaign)).isZero();
    }

    @Test
    void 작성_중_예약_발송_대기를_취소하면_USER_CANCELED이고_스케줄러가_보내지_않는다() throws Exception {
        long draft = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        cancel(draft).andExpect(status().isNoContent());
        assertThat(campaignRow(draft))
                .containsEntry("status", "CANCELED")
                .containsEntry("cancel_reason", "USER_CANCELED");
        cancel(draft).andExpect(status().isConflict());

        long scheduled = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        schedule(scheduled, today().atTime(15, 0)).andExpect(status().isNoContent());
        cancel(scheduled).andExpect(status().isNoContent());
        clock.at(today().atTime(15, 0));
        runner.run();
        assertThat(rowCount(scheduled)).isZero();
        assertThat(campaignRow(scheduled)).containsEntry("status", "CANCELED");

        long sent = campaign(BODY, List.of(agreedCustomer()), List.of(), true);
        send(sent).andExpect(status().isOk());
        cancel(sent).andExpect(jsonPath("$.error.code").value("conflict"));

        long other = campaignIn(data.shop(), BODY, List.of(), true);
        cancel(other).andExpect(status().isNotFound());
    }

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.ZONE);
    }

    private ResultActions schedule(long campaignId, LocalDateTime seoul) throws Exception {
        return mvc.perform(
                put("/api/campaigns/{id}/schedule", campaignId)
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"scheduledAt\":\""
                                        + seoul.atZone(TimeConfig.ZONE).toOffsetDateTime()
                                        + "\"}"));
    }

    private ResultActions cancel(long campaignId) throws Exception {
        return mvc.perform(
                post("/api/campaigns/{id}/cancel", campaignId).session(session(true)).with(csrf()));
    }

    private void nightConsent(long customerId, boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method)"
                        + " VALUES (?, ?, 'NIGHT_AD', ?, ?, ?, 'TABLET')",
                shop,
                customerId,
                agreed,
                LocalDateTime.of(2026, 1, 2, 0, 0),
                staff);
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
            // PIN 만료는 권한 검사와 같은 테스트 시계로 계산한다 (실제 시각으로 넣으면 고정한 시각보다 이를 때 만료로 보인다)
            shopSession.setPin(session, staff, LocalDateTime.now(clock).plusMinutes(5));
        }
        return session;
    }

    private long agreedCustomer() {
        long id = data.customer(shop, false, null);
        consent(id, true);
        return id;
    }

    private void dailyLimit(int limit) {
        jdbc.update("UPDATE shop SET daily_send_limit = ? WHERE id = ?", limit, shop);
    }

    private long nightConsentCustomer() {
        long id = agreedCustomer();
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method)"
                        + " VALUES (?, ?, 'NIGHT_AD', TRUE, ?, ?, 'TABLET')",
                shop,
                id,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                staff);
        return id;
    }

    /** 동의 재확인 안내 캠페인 (카테고리 없음, 메시지 종류 INFO) */
    private long infoCampaign(List<Long> send) {
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose)"
                        + " VALUES (?, '모의', '{고객명}님 동의 확인', 'CONSENT_RECONFIRM')",
                shop);
        long template =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM message_template WHERE shop_id = ?", Long.class, shop);
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, template_id, message_kind, reviewed_by_staff_id, reviewed_at)"
                        + " VALUES (?, '모의', ?, 'INFO', ?, ?)",
                shop,
                template,
                staff,
                LocalDateTime.now());
        long id =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM campaign WHERE shop_id = ?", Long.class, shop);
        for (long c : send) {
            jdbc.update(
                    "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                            + " VALUES (?, ?, ?, FALSE)",
                    shop,
                    id,
                    c);
        }
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
        return jdbc.queryForMap(
                "SELECT status, sent_at, scheduled_at, cancel_reason, canceled_at FROM campaign"
                        + " WHERE id = ?",
                campaignId);
    }
}
