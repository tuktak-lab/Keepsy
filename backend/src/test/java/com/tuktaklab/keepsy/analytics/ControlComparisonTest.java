package com.tuktaklab.keepsy.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 대조군 비교 · 최근 캠페인 성과 (Keepsy #104 · #82, FR-ANL-09, FR-DSH-04, 결정 F36). 답을 아는 모의 데이터로 확인한다
 * (DEVELOPMENT 7장 「지표 계산 검증」).
 *
 * <p>네일 캠페인 X(10/1 10:00 발송, 귀속 기간 기본 14일)의 발송군: A(도달 · 귀속된 예약), B(도달만), C(실패 · 귀속이 잘못 들어간 예약),
 * D(대기). 대조군: P(4일째 예약), Q(14일째 취소된 예약 = 기간 끝), R(15일째 예약), S(발송 1시간 전 예약), T(속눈썹 예약만), U(네일 예약 둘),
 * V(예약 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlComparisonTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long nail;
    private long lash;
    private long template;
    private long campaignX;
    private long otherShopCampaign;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        // 가상 발송기 스케줄러(3초마다)가 PENDING 행을 먼저 처리해도 결과가 같도록 실패 비율을 100%로 둔다 (PENDING → FAILED,
        // VirtualPhoneInboxTest와 같은 방식)
        jdbc.update("UPDATE shop SET virtual_failure_percent = 100 WHERE id = ?", shop);
        staff = data.staff(shop, "ACTIVE", false);
        nail = data.menu(shop, 60);
        lash =
                insert(
                        "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                                + " cycle_min_days, cycle_max_days)"
                                + " VALUES (?, 'LASH', '모의 시술', 30000, 60, 21, 28)",
                        shop);
        template = template(shop);
        campaignX = campaign(shop, "AD", "NAIL", SENT_AT);

        long a = data.customer(shop, false, null);
        long b = data.customer(shop, false, null);
        long c = data.customer(shop, false, null);
        long d = data.customer(shop, false, null);
        long mA = message(campaignX, a, false, "SENT");
        message(campaignX, b, false, "SENT");
        long mC = message(campaignX, c, false, "FAILED");
        message(campaignX, d, false, "PENDING");
        reservationService(reservation(a, "CONFIRMED", day(2)), nail, mA);
        reservationService(reservation(c, "CONFIRMED", day(2)), nail, mC); // 실패는 어느 집단에도 없다

        long p = control(campaignX);
        long q = control(campaignX);
        long r = control(campaignX);
        long s = control(campaignX);
        long t = control(campaignX);
        long u = control(campaignX);
        control(campaignX); // V: 예약 없음
        reservationService(reservation(p, "CONFIRMED", day(4)), nail, null);
        // 발송일 + 14일 끝까지 든다 (A8 날짜 기준). 취소돼도 센다 (퍼널 재예약과 같음, F36)
        reservationService(
                reservation(q, "CANCELED", SENT_AT.plusDays(14).withHour(23)), nail, null);
        reservationService(
                reservation(r, "CONFIRMED", SENT_AT.plusDays(15).withHour(9)), nail, null);
        reservationService(reservation(s, "CONFIRMED", SENT_AT.minusHours(1)), nail, null);
        reservationService(reservation(t, "CONFIRMED", day(3)), lash, null);
        reservationService(reservation(u, "CONFIRMED", day(2)), nail, null);
        reservationService(reservation(u, "COMPLETED", day(10)), nail, null);
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", u); // 삭제된 고객도 센다 (C12)

        long other = data.shop();
        otherShopCampaign = campaign(other, "AD", "NAIL", SENT_AT);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 발송군은_도달_고객과_퍼널_재예약_대조군은_귀속_기간_안_같은_카테고리_예약() throws Exception {
        mvc.perform(get("/api/campaigns/{id}/control-comparison", campaignX).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(2)) // A B. C(실패) · D(대기) 제외
                .andExpect(jsonPath("$.rebookedCount").value(1)) // A
                .andExpect(jsonPath("$.controlCount").value(7))
                .andExpect(jsonPath("$.controlRebookedCount").value(3)); // P Q U
    }

    /**
     * 주기 내 재방문 (FR-ANL-09, F57). 캠페인 Y(10/1 10:00 발송)의 발송군과 대조군에 스냅샷을 직접 둔다. 재방문 = 발송 시각 예측의 실제 다음
     * 방문일 ≤ 종료일 + 이탈 신호 기간
     */
    @Test
    void 주기_내_재방문은_발송_시각_예측의_이탈_신호_기간_안에_다음_방문이_왔는지() throws Exception {
        long y = campaign(shop, "AD", "NAIL", SENT_AT);
        LocalDateTime before = SENT_AT.minusDays(20);
        // 발송군 (도달)
        snapshot(reached(y), before, "2026-10-05", 10, "2026-10-15"); // 끝날 = 10/15 → 재방문
        snapshot(reached(y), before, "2026-10-05", 10, "2026-10-16"); // 하루 늦음
        snapshot(reached(y), before, "2026-10-05", 10, null); // 아직 안 옴 → 분모에만
        snapshot(reached(y), before, "2026-10-05", null, "2026-10-06"); // 기간 없는 옛 스냅샷 → 뺌
        reached(y); // 스냅샷 없음(예측 없음 · 종료 신호) → 뺌
        long twice = reached(y);
        snapshot(twice, before.minusDays(30), "2026-09-10", 10, "2026-09-20"); // 발송 전에 끝난 예측
        snapshot(twice, before, "2026-10-10", 5, "2026-10-03"); // 발송 때의 예측 → 재방문
        snapshot(reached(y), SENT_AT.plusHours(1), "2026-10-05", 10, "2026-10-06"); // 발송 뒤 → 뺌
        message(y, data.customer(shop, false, null), false, "FAILED"); // 실패는 어느 집단에도 없다
        // 대조군
        long c1 = control(y);
        long c2 = control(y);
        control(y); // 스냅샷 없음
        snapshot(c1, before, "2026-10-08", 3, "2026-10-02");
        snapshot(c2, before, "2026-10-08", 3, "2026-10-12"); // 끝날 10/11 다음날

        mvc.perform(get("/api/campaigns/{id}/control-comparison", y).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(7))
                .andExpect(jsonPath("$.reachedCycleCount").value(4))
                .andExpect(jsonPath("$.reachedCycleRevisitedCount").value(2))
                .andExpect(jsonPath("$.controlCount").value(3))
                .andExpect(jsonPath("$.controlCycleCount").value(2))
                .andExpect(jsonPath("$.controlCycleRevisitedCount").value(1));
        // 스냅샷이 없는 기존 캠페인은 0
        mvc.perform(get("/api/campaigns/{id}/control-comparison", campaignX).session(session(true)))
                .andExpect(jsonPath("$.reachedCycleCount").value(0))
                .andExpect(jsonPath("$.controlCycleCount").value(0));
    }

    private long reached(long campaign) {
        long customer = data.customer(shop, false, null);
        message(campaign, customer, false, "SENT");
        return customer;
    }

    private void snapshot(
            long customer,
            LocalDateTime createdAt,
            String endDate,
            Integer churnSignalDays,
            String actualVisitDate) {
        jdbc.update(
                "INSERT INTO prediction_snapshot (shop_id, customer_id, category, created_at,"
                        + " start_date, end_date, basis_visit_count, churn_signal_days,"
                        + " actual_visit_date) VALUES (?, ?, 'NAIL', ?, DATE_SUB(?, INTERVAL 6 DAY),"
                        + " ?, 2, ?, ?)",
                shop,
                customer,
                createdAt,
                endDate,
                endDate,
                churnSignalDays,
                actualVisitDate);
    }

    @Test
    void 대조군_기간은_조회할_때의_매장_귀속_기간을_쓴다() throws Exception {
        jdbc.update("UPDATE shop SET attribution_days = 7 WHERE id = ?", shop);
        mvc.perform(get("/api/campaigns/{id}/control-comparison", campaignX).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlRebookedCount").value(2)); // P(4일째) U(2일째)
    }

    @Test
    void 대조군이_없거나_발송_전인_캠페인은_대조군이_0() throws Exception {
        long noControl = campaign(shop, "AD", "NAIL", SENT_AT);
        long e = data.customer(shop, false, null);
        message(noControl, e, false, "SENT");
        long draft = campaign(shop, "AD", "NAIL", null);
        long w = control(draft);
        reservationService(reservation(w, "CONFIRMED", day(2)), nail, null);

        MockHttpSession s = session(true);
        mvc.perform(get("/api/campaigns/{id}/control-comparison", noControl).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(1))
                .andExpect(jsonPath("$.controlCount").value(0))
                .andExpect(jsonPath("$.controlRebookedCount").value(0));
        mvc.perform(get("/api/campaigns/{id}/control-comparison", draft).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(0))
                .andExpect(jsonPath("$.controlCount").value(1))
                .andExpect(jsonPath("$.controlRebookedCount").value(0));
    }

    @Test
    void 최근_캠페인은_발송한_광고_캠페인_최신순_3개이고_퍼널과_같은_값() throws Exception {
        LocalDateTime later = SENT_AT.plusDays(5);
        long k2 = campaign(shop, "AD", "LASH", SENT_AT.plusDays(3));
        long k3 = campaign(shop, "AD", "NAIL", later);
        long k4 = campaign(shop, "AD", "NAIL", later); // 같은 시각이면 ID가 큰 것 먼저
        campaign(shop, "INFO", null, SENT_AT.plusDays(9)); // 동의 재확인 안내는 뺀다 (E7)
        campaign(shop, "AD", "NAIL", null); // 아직 발송 안 함
        campaign(data.shop(), "AD", "NAIL", SENT_AT.plusDays(9)); // 다른 매장 (NFR-03)

        long f = data.customer(shop, false, null);
        long g = data.customer(shop, false, null);
        long mF = message(k3, f, false, "SENT");
        message(k3, g, false, "SENT");
        long h = control(k3); // 대조군은 넣지 않는다
        reservationService(reservation(f, "NO_SHOW", later.plusDays(1)), nail, mF);
        reservationService(reservation(h, "CONFIRMED", later.plusDays(1)), nail, null);

        MockHttpSession s = session(true);
        mvc.perform(get("/api/dashboard/recent-campaigns").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(k4))
                .andExpect(jsonPath("$[0].reachedCount").value(0))
                .andExpect(jsonPath("$[0].rebookedCount").value(0))
                .andExpect(jsonPath("$[1].id").value(k3))
                .andExpect(jsonPath("$[1].name").value("모의 캠페인"))
                .andExpect(jsonPath("$[1].category").value("NAIL"))
                .andExpect(jsonPath("$[1].sentAt").value("2026-10-06T10:00:00+09:00"))
                .andExpect(jsonPath("$[1].reachedCount").value(2))
                .andExpect(jsonPath("$[1].rebookedCount").value(1))
                .andExpect(jsonPath("$[2].id").value(k2));
        mvc.perform(get("/api/campaigns/{id}/funnel", k3).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachedCount").value(2))
                .andExpect(jsonPath("$.rebookedCount").value(1));
    }

    @Test
    void 발송한_캠페인이_없으면_빈_배열() throws Exception {
        long empty = data.shop();
        long emptyStaff = data.staff(empty, "ACTIVE", false);
        campaign(empty, "AD", "NAIL", null);
        mvc.perform(
                        get("/api/dashboard/recent-campaigns")
                                .session(session(empty, emptyStaff, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 다른_매장_캠페인과_없는_캠페인은_not_found() throws Exception {
        MockHttpSession s = session(true);
        for (long id : List.of(otherShopCampaign, 0L)) {
            mvc.perform(get("/api/campaigns/{id}/control-comparison", id).session(s))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Test
    void 캠페인_조회가_PIN이면_두_API_모두_pin_required() throws Exception {
        MockHttpSession s = session(false);
        for (String path :
                List.of(
                        "/api/campaigns/" + campaignX + "/control-comparison",
                        "/api/dashboard/recent-campaigns")) {
            mvc.perform(get(path).session(s))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("pin_required"));
        }
        // 공용으로 바꾸면 PIN 없이 된다. 최근 캠페인은 대시보드가 아니라 캠페인 조회 설정을 따른다 (명세서 2장)
        jdbc.update("UPDATE shop SET pin_locked_features = 'SALES_ANALYTICS' WHERE id = ?", shop);
        for (String path :
                List.of(
                        "/api/campaigns/" + campaignX + "/control-comparison",
                        "/api/dashboard/recent-campaigns")) {
            mvc.perform(get(path).session(s)).andExpect(status().isOk());
        }
    }

    private MockHttpSession session(boolean pin) {
        return session(shop, staff, pin);
    }

    /** 캠페인 조회는 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (명세서 2장) */
    private MockHttpSession session(long shopId, long staffId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staffId);
        if (pin) {
            shopSession.setPin(session, staffId, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private static LocalDateTime day(int n) {
        return SENT_AT.plusDays(n);
    }

    private long template(long shopId) {
        return insert(
                "INSERT INTO message_template (shop_id, name, body, purpose)"
                        + " VALUES (?, '모의 템플릿', '모의 본문', 'REVISIT')",
                shopId);
    }

    private long campaign(long shopId, String kind, String category, LocalDateTime sentAt) {
        long t = shopId == shop ? template : template(shopId);
        return insert(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind,"
                        + " control_percent, status, sent_at)"
                        + " VALUES (?, '모의 캠페인', ?, ?, ?, 10, ?, ?)",
                shopId,
                category,
                t,
                kind,
                sentAt == null ? "DRAFT" : "SENT",
                sentAt);
    }

    private long message(long campaign, long customer, boolean controlGroup, String status) {
        return insert(
                "INSERT INTO campaign_message (shop_id, campaign_id, kind, customer_id, category,"
                        + " control_group, send_status, result_at)"
                        + " VALUES (?, ?, 'AD', ?, 'NAIL', ?, ?, ?)",
                shop,
                campaign,
                customer,
                controlGroup,
                status,
                status == null ? null : SENT_AT);
    }

    /** 대조군 고객을 새로 만들어 대조군 행을 남긴다 (FR-CMP-18) */
    private long control(long campaign) {
        long customer = data.customer(shop, false, null);
        message(campaign, customer, true, null);
        return customer;
    }

    private long reservation(long customer, String status, LocalDateTime createdAt) {
        LocalDateTime start = createdAt.plusDays(7).withHour(11);
        return insert(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customer,
                staff,
                start,
                start.plusHours(1),
                status,
                createdAt);
    }

    private void reservationService(long reservation, long menu, Long message) {
        insert(
                "INSERT INTO reservation_service (shop_id, reservation_id, service_menu_id,"
                        + " attributed_message_id) VALUES (?, ?, ?, ?)",
                shop,
                reservation,
                menu,
                message);
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int n = 0; n < args.length; n++) {
                        ps.setObject(n + 1, args[n]);
                    }
                    return ps;
                },
                key);
        return key.getKey().longValue();
    }
}
