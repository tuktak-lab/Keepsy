package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
 * CRM 핵심 흐름 통합 테스트 (Keepsy #351, 기획서 7장 시나리오, DEVELOPMENT 7장). 예약 → 마감 → 고객 기록 → 예측 → 캠페인 대상 → 검토 →
 * 가상 발송 → 재예약 · 재방문 → 성과 분석을 실제 API로 고정 시계 위에서 잇고, 단계마다 지표 기대값을 단언한다. 실패 · 경계 경우는 아래 테스트로 나눈다. 실제
 * MySQL(테스트 전용 DB)에서 돈다.
 *
 * <p>무작위 실패(가상 발송 실패 비율)는 기본 0이라 결과가 정해진다. 대조군 배정은 무작위라, 누가 대조군인지는 만든 뒤 DB에서 읽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrmFlowTest {

    /** 2026-06-01. 매장은 매일 10:00~19:00 영업 (TestShopData) */
    private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);

    /** 예측 주기 21~28일(TestShopData 메뉴)의 도래 구간 안. 이날 캠페인을 만들고 보낸다 */
    private static final LocalDate SEND_DAY = DAY0.plusDays(22);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired VirtualMessageSender sender;
    @Autowired FlowClock clock;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        FlowClock flowClock() {
            return new FlowClock();
        }
    }

    /** 시나리오 시각으로 옮기는 서울 시계 (CampaignSendTest의 MutableClock 방식) */
    static class FlowClock extends Clock {
        private volatile Instant now = DAY0.atTime(9, 0).atZone(TimeConfig.ZONE).toInstant();

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
    private long gel;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        gel = data.menu(shop, 60);
        // 광고 표기용 매장 번호 (F47)
        jdbc.update(
                "UPDATE shop SET phone = '02-000-0000', unsubscribe_number = '080-000-0000' WHERE id = ?",
                shop);
        clock.at(DAY0.atTime(9, 0));
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
        clock.at(DAY0.atTime(9, 0));
    }

    @Test
    void 예약부터_성과_분석까지_한_흐름으로_이어지고_단계마다_지표가_맞다() throws Exception {
        // 1. 고객 기록: 개인정보 · 마케팅 동의로 4명 등록
        List<Long> customers =
                List.of(
                        register("모의 가", "010-0000-9101"),
                        register("모의 나", "010-0000-9102"),
                        register("모의 다", "010-0000-9103"),
                        register("모의 라", "010-0000-9104"));

        // 2. 예약 → 마감: DAY0에 한 명씩 젤 네일, 5만 원 카드 결제
        for (int i = 0; i < customers.size(); i++) {
            LocalDateTime start = DAY0.atTime(10 + 2 * i, 0);
            long reservation = reserve(customers.get(i), start);
            clock.at(start.plusHours(1));
            close(reservation, 50_000);
        }
        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isEqualTo(4);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM payment WHERE shop_id = ?", shop))
                .isEqualTo(200_000);

        // 3. 예측: 메뉴 주기 21~28일이라 DAY0 + 22일은 주기 도래(DUE)
        clock.at(SEND_DAY.atTime(13, 0));
        mvc.perform(get("/api/customers/{id}/predictions", customers.get(0)).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictions[0].category").value("NAIL"))
                .andExpect(jsonPath("$.predictions[0].status").value("DUE"))
                .andExpect(
                        jsonPath("$.predictions[0].startDate").value(DAY0.plusDays(21).toString()))
                .andExpect(
                        jsonPath("$.predictions[0].endDate").value(DAY0.plusDays(28).toString()));

        // 4. 캠페인 대상: 네일 주기 도래 고객 4명, 제외 없음
        mvc.perform(
                        post("/api/campaigns/targets")
                                .session(session(true))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"category\":\"NAIL\",\"predictionStatus\":\"DUE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCount").value(4));

        long campaign = createCampaign(template(), 25);
        List<Long> control =
                jdbc.queryForList(
                        "SELECT customer_id FROM campaign_target WHERE campaign_id = ? AND control_group",
                        Long.class,
                        campaign);
        List<Long> sendGroup =
                jdbc.queryForList(
                        "SELECT customer_id FROM campaign_target WHERE campaign_id = ?"
                                + " AND NOT control_group ORDER BY customer_id",
                        Long.class,
                        campaign);
        assertThat(control).hasSize(1); // 4명의 25%
        assertThat(sendGroup).hasSize(3);
        long rebooker = sendGroup.get(0);
        long lateBooker = sendGroup.get(1);
        long withdrew = sendGroup.get(2);
        long controlCustomer = control.get(0);

        // 동의 철회: 캠페인을 만든 뒤 마케팅 동의를 철회한 고객은 발송 시점에 빠진다 (F51)
        withdrawMarketing(withdrew);

        // 5. 검토 → 가상 발송 (FR-CMP-06 · 08)
        mvc.perform(
                        post("/api/campaigns/{id}/review", campaign)
                                .session(session(true))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        clock.at(SEND_DAY.atTime(14, 0));
        mvc.perform(post("/api/campaigns/{id}/send", campaign).session(session(true)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(1));
        // 캠페인 중복 발송은 conflict이고 아무것도 더 만들지 않는다 (F7)
        mvc.perform(post("/api/campaigns/{id}/send", campaign).session(session(true)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        sender.deliverPending();

        assertThat(sendStatus(campaign, rebooker)).isEqualTo("SENT");
        assertThat(sendStatus(campaign, lateBooker)).isEqualTo("SENT");
        assertThat(sendStatus(campaign, withdrew)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_reason FROM campaign_message WHERE campaign_id = ? AND customer_id = ?",
                                String.class,
                                campaign,
                                withdrew))
                .isEqualTo(CampaignSendService.NO_MARKETING_CONSENT);
        assertThat(
                        count(
                                "SELECT count(*) FROM campaign_message WHERE campaign_id = ? AND control_group",
                                campaign))
                .isEqualTo(1);

        // 6. 재예약 · 재방문. 귀속 기간은 기본 14일이고 발송일 + 14일까지가 안이다 (FR-ANL-03)
        //    대조군: 발송 뒤 기간 안에 같은 카테고리 예약 → 대조군 재예약 (F36, 귀속은 아님)
        clock.at(SEND_DAY.plusDays(3).atTime(10, 0));
        reserve(controlCustomer, SEND_DAY.plusDays(5).atTime(11, 0));
        //    경계 안: 발송일 + 14일 18:00에 만든 예약은 이 발송에 귀속된다
        clock.at(SEND_DAY.plusDays(14).atTime(18, 0));
        long rebooked = reserve(rebooker, SEND_DAY.plusDays(16).atTime(11, 0));
        //    경계 밖: 발송일 + 15일에 만든 예약은 귀속되지 않는다
        clock.at(SEND_DAY.plusDays(15).atTime(10, 0));
        long late = reserve(lateBooker, SEND_DAY.plusDays(16).atTime(14, 0));

        clock.at(SEND_DAY.plusDays(16).atTime(12, 0));
        close(rebooked, 60_000);
        clock.at(SEND_DAY.plusDays(16).atTime(15, 0));
        close(late, 70_000);

        // 7. 성과 분석 (FR-ANL-01 · 02 · 09)
        MockHttpSession s = session(true);
        mvc.perform(get("/api/campaigns/{id}/funnel", campaign).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentCount").value(3)) // 대기 · 실패 포함, 대조군 제외
                .andExpect(jsonPath("$.reachedCount").value(2))
                .andExpect(jsonPath("$.rebookedCount").value(1)) // 경계 밖 예약은 빠진다
                .andExpect(jsonPath("$.revisitedCount").value(1));
        mvc.perform(get("/api/campaigns/{id}/revenue", campaign).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(60_000)); // 귀속된 방문의 결제만
        mvc.perform(get("/api/campaigns/{id}/control-comparison", campaign).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(2))
                .andExpect(jsonPath("$.rebookedCount").value(1))
                .andExpect(jsonPath("$.controlCount").value(1))
                .andExpect(jsonPath("$.controlRebookedCount").value(1));
    }

    @Test
    void 같은_스탭의_겹치는_예약은_reservation_conflict() throws Exception {
        long customer = register("모의 가", "010-0000-9201");
        reserve(customer, DAY0.atTime(10, 0));

        reserveRequest(customer, DAY0.atTime(10, 30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("reservation_conflict"));
        assertThat(count("SELECT count(*) FROM reservation WHERE shop_id = ?", shop)).isEqualTo(1);
    }

    @Test
    void 마감한_예약은_취소할_수_없고_시작_전_노쇼와_취소한_예약의_마감은_conflict() throws Exception {
        long customer = register("모의 가", "010-0000-9301");
        long done = reserve(customer, DAY0.atTime(10, 0));
        long later = reserve(customer, DAY0.atTime(15, 0));
        clock.at(DAY0.atTime(11, 0));
        close(done, 50_000);

        // 마감(완료)한 예약은 확정 예약이 아니라 취소 · 노쇼할 수 없다 (FR-RSV-03)
        mvc.perform(post("/api/reservations/{id}/cancel", done).session(session(true)).with(csrf()))
                .andExpect(status().isConflict());
        // 시작 전 노쇼는 conflict (C8)
        mvc.perform(
                        post("/api/reservations/{id}/no-show", later)
                                .session(session(true))
                                .with(csrf()))
                .andExpect(status().isConflict());
        // 취소한 예약은 마감할 수 없다
        mvc.perform(
                        post("/api/reservations/{id}/cancel", later)
                                .session(session(true))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        closeRequest(later, 50_000).andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM visit WHERE shop_id = ?", shop)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM reservation WHERE id = ?", String.class, done))
                .isEqualTo("COMPLETED");
    }

    @Test
    void 다른_매장의_고객과_캠페인_성과는_not_found이고_PIN_없이_발송하면_pin_required() throws Exception {
        long customer = register("모의 가", "010-0000-9401");
        long campaign = createCampaignFor(customer);
        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", true);
        MockHttpSession other = session(otherShop, otherStaff, true);

        mvc.perform(get("/api/customers/{id}", customer).session(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/campaigns/{id}/funnel", campaign).session(other))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/campaigns/{id}/send", campaign).session(other).with(csrf()))
                .andExpect(status().isNotFound());

        // 권한 부족: 발송은 기본 PIN 잠금이라 PIN 인증 없이는 거부되고 상태가 그대로다 (NFR-04)
        mvc.perform(post("/api/campaigns/{id}/send", campaign).session(session(false)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM campaign WHERE id = ?", String.class, campaign))
                .isEqualTo("DRAFT");
    }

    // ---- 도우미: 모두 실제 API를 부른다 (템플릿만 SQL로 넣는다) ----

    private long register(String name, String phone) throws Exception {
        String body =
                mvc.perform(
                                post("/api/customers")
                                        .session(session(true))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                ("{\"name\":\"%s\",\"phone\":\"%s\","
                                                                + "\"consents\":[\"PRIVACY\",\"MARKETING\"],"
                                                                + "\"consentMethod\":\"TABLET\"}")
                                                        .formatted(name, phone)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(body, "$.customerId")).longValue();
    }

    private ResultActions reserveRequest(long customerId, LocalDateTime start) throws Exception {
        return mvc.perform(
                post("/api/reservations")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                ("{\"customerId\":%d,\"staffId\":%d,\"startAt\":\"%s\","
                                                + "\"channel\":\"PHONE\",\"serviceMenuIds\":[%d]}")
                                        .formatted(customerId, staff, offset(start), gel)));
    }

    private long reserve(long customerId, LocalDateTime start) throws Exception {
        String body =
                reserveRequest(customerId, start)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions closeRequest(long reservationId, int amount) throws Exception {
        return mvc.perform(
                post("/api/reservations/{id}/closing", reservationId)
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                ("{\"services\":[{\"serviceMenuId\":%d,\"staffId\":%d}],"
                                                + "\"amount\":%d,\"method\":\"CARD\"}")
                                        .formatted(gel, staff, amount)));
    }

    private void close(long reservationId, int amount) throws Exception {
        closeRequest(reservationId, amount).andExpect(status().is2xxSuccessful());
    }

    private void withdrawMarketing(long customerId) throws Exception {
        mvc.perform(
                        post("/api/customers/{id}/consents", customerId)
                                .session(session(true))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"item\":\"MARKETING\",\"agreed\":false,"
                                                + "\"method\":\"VERBAL\",\"withdrawalPath\":\"DIRECT\"}"))
                .andExpect(status().isCreated());
    }

    /** 재방문 안내 광고 템플릿. 템플릿 API는 이 흐름의 대상이 아니라 SQL로 넣는다 */
    private long template() {
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose)"
                        + " VALUES (?, '모의 재방문', '{고객명}님, 다시 방문할 때가 됐어요', 'REVISIT')",
                shop);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM message_template WHERE shop_id = ?", Long.class, shop);
    }

    private long createCampaign(long templateId, int controlPercent) throws Exception {
        String body =
                mvc.perform(
                                post("/api/campaigns")
                                        .session(session(true))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                ("{\"name\":\"네일 재방문\",\"templateId\":%d,"
                                                                + "\"controlPercent\":%d,\"category\":\"NAIL\","
                                                                + "\"predictionStatus\":\"DUE\"}")
                                                        .formatted(templateId, controlPercent)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** 고객 한 명을 직접 고른 광고 캠페인 */
    private long createCampaignFor(long customerId) throws Exception {
        String body =
                mvc.perform(
                                post("/api/campaigns")
                                        .session(session(true))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                ("{\"name\":\"모의\",\"templateId\":%d,\"category\":\"NAIL\","
                                                                + "\"customerIds\":[%d]}")
                                                        .formatted(template(), customerId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String sendStatus(long campaignId, long customerId) {
        return jdbc.queryForObject(
                "SELECT send_status FROM campaign_message WHERE campaign_id = ? AND customer_id = ?",
                String.class,
                campaignId,
                customerId);
    }

    private static String offset(LocalDateTime seoul) {
        return seoul.atZone(TimeConfig.ZONE).toOffsetDateTime().toString();
    }

    private MockHttpSession session(boolean pin) {
        return session(shop, staff, pin);
    }

    /** PIN 만료는 권한 검사와 같은 테스트 시계로 계산한다 (시계를 옮긴 뒤마다 새로 만든다) */
    private MockHttpSession session(long shopId, long staffId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staffId);
        if (pin) {
            shopSession.setPin(session, staffId, LocalDateTime.now(clock).plusMinutes(5));
        }
        return session;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
