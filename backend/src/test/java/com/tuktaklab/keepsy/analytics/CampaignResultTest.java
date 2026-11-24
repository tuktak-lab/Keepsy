package com.tuktaklab.keepsy.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 캠페인 전환 퍼널 · 연결 매출 (Keepsy #102 · #103, FR-ANL-01 · 02 · 03, F33). 귀속은 저장된 값을 읽으므로 귀속 발송 ID를 직접 넣은
 * 답을 아는 모의 데이터로 확인한다 (DEVELOPMENT 7장 「지표 계산 검증」).
 *
 * <p>네일 캠페인 X의 고객: A(도달 · 취소 예약 → 승계 예약 → 기간 밖 방문, F33), B(도달 · 노쇼, 승계 없음), C(도달만), D(실패 · 귀속이 잘못
 * 들어간 예약 · 방문), E(대기), F(대조군 · 귀속이 잘못 들어간 예약), G(도달 · 다른 카테고리 시술에만 귀속), H(도달 · 네일은 X, 왁싱은 Y에 귀속된
 * 방문), I(도달 · 가져온 방문에만 귀속). 왁싱 캠페인 Y의 고객은 H 하나.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignResultTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long nail;
    private long lash;
    private long waxing;
    private long campaignX;
    private long campaignY;
    private long otherShopCampaign;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        nail = data.menu(shop, 60);
        lash = menu(shop, "LASH");
        waxing = menu(shop, "WAXING");
        long template = template(shop);
        campaignX = campaign(shop, "NAIL", template);
        campaignY = campaign(shop, "WAXING", template);

        long a = data.customer(shop, false, null);
        long b = data.customer(shop, false, null);
        long c = data.customer(shop, false, null);
        long d = data.customer(shop, false, null);
        long e = data.customer(shop, false, null);
        long f = data.customer(shop, false, null);
        long g = data.customer(shop, false, null);
        long h = data.customer(shop, false, null);
        long i = data.customer(shop, false, null);

        long mA = message(campaignX, a, "NAIL", false, "SENT");
        long mB = message(campaignX, b, "NAIL", false, "SENT");
        message(campaignX, c, "NAIL", false, "SENT");
        long mD = message(campaignX, d, "NAIL", false, "FAILED");
        message(campaignX, e, "NAIL", false, "PENDING");
        long mF = message(campaignX, f, "NAIL", true, null);
        long mG = message(campaignX, g, "NAIL", false, "SENT");
        long mH = message(campaignX, h, "NAIL", false, "SENT");
        long mI = message(campaignX, i, "NAIL", false, "SENT");
        long mHy = message(campaignY, h, "WAXING", false, "SENT");

        // A: 취소된 예약과 승계 예약이 같은 발송을 갖는다 → 재예약 고객당 1 (FR-ANL-03). 방문은 귀속 기간(14일) 밖 (F33)
        long rA1 = reservation(a, "CANCELED");
        reservationService(rA1, nail, mA);
        long rA2 = reservation(a, "COMPLETED");
        reservationService(rA2, nail, mA);
        long vA = visit(a, "2026-11-20", "NORMAL");
        visitService(vA, rA2, nail, "NAIL", mA);
        payment(rA2, a, vA, 30_000, false);
        payment(rA2, a, vA, 20_000, false); // 한 방문에 결제 여러 건 → 전액 합산 (A13)
        payment(rA2, a, vA, 99_000, true); // 취소 결제 제외 (FR-PAY-02)
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", a); // 삭제된 고객도 센다 (C12)

        // B: 노쇼, 이어받은 예약 없음 → 재예약에는 남고 재방문에서는 빠진다 (FR-ANL-01)
        reservationService(reservation(b, "NO_SHOW"), nail, mB);

        // D(실패) · F(대조군)에 귀속이 잘못 들어가 있어도 세지 않는다 (명세서 5장 campaign_message, E2)
        long rD = reservation(d, "COMPLETED");
        reservationService(rD, nail, mD);
        long vD = visit(d, "2026-10-10", "NORMAL");
        visitService(vD, rD, nail, "NAIL", mD);
        payment(rD, d, vD, 7_000, false);
        reservationService(reservation(f, "CONFIRMED"), nail, mF);

        // G: 다른 카테고리 시술에 귀속이 들어가 있어도 세지 않는다 (E7)
        long rG = reservation(g, "COMPLETED");
        reservationService(rG, lash, mG);
        long vG = visit(g, "2026-10-10", "NORMAL");
        visitService(vG, rG, lash, "LASH", mG);
        payment(rG, g, vG, 11_000, false);

        // H: 네일 두 개는 X, 왁싱은 Y에 귀속된 방문 → 두 캠페인에 각각 전액, X에는 한 번만 (FR-ANL-02)
        long rH = reservation(h, "COMPLETED");
        reservationService(rH, nail, mH);
        reservationService(rH, waxing, mHy);
        long vH = visit(h, "2026-10-10", "NORMAL");
        visitService(vH, rH, nail, "NAIL", mH);
        visitService(vH, rH, nail, "NAIL", mH);
        visitService(vH, rH, waxing, "WAXING", mHy);
        payment(rH, h, vH, 50_000, false);

        // I: 가져온 방문은 귀속 · 매출 제외 (FR-CUS-13)
        long vI = visit(i, "2026-10-10", "IMPORT");
        visitService(vI, null, null, "NAIL", mI);

        long other = data.shop();
        otherShopCampaign = campaign(other, "NAIL", template(other));
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 퍼널은_고객_단위로_세고_대조군_실패_다른_카테고리_가져온_방문을_뺀다() throws Exception {
        mvc.perform(get("/api/campaigns/{id}/funnel", campaignX).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentCount").value(8)) // F(대조군) 제외, D(실패) · E(대기) 포함
                .andExpect(jsonPath("$.reachedCount").value(6)) // A B C G H I
                .andExpect(jsonPath("$.rebookedCount").value(3)) // A(취소 + 승계 = 1) B(노쇼) H
                .andExpect(jsonPath("$.revisitedCount").value(2)); // A(F33 기간 밖 방문) H
    }

    @Test
    void 여러_캠페인에_귀속된_방문은_캠페인마다_전액_한_번() throws Exception {
        MockHttpSession s = session(true);
        // X: A 50,000(30,000 + 20,000, 취소 99,000 제외) + H 50,000. D(실패) · G(다른 카테고리)는 뺀다
        mvc.perform(get("/api/campaigns/{id}/revenue", campaignX).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(100_000));
        mvc.perform(get("/api/campaigns/{id}/revenue", campaignY).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(50_000));
        mvc.perform(get("/api/campaigns/{id}/funnel", campaignY).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentCount").value(1))
                .andExpect(jsonPath("$.reachedCount").value(1))
                .andExpect(jsonPath("$.rebookedCount").value(1))
                .andExpect(jsonPath("$.revisitedCount").value(1));
    }

    @Test
    void 다른_매장_캠페인과_없는_캠페인은_not_found() throws Exception {
        MockHttpSession s = session(true);
        for (String path : List.of("funnel", "revenue")) {
            for (long id : List.of(otherShopCampaign, 0L)) {
                mvc.perform(get("/api/campaigns/{id}/" + path, id).session(s))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("not_found"));
            }
        }
    }

    @Test
    void 기본_PIN_잠금이면_pin_required() throws Exception {
        MockHttpSession s = session(false);
        for (String path : List.of("funnel", "revenue")) {
            mvc.perform(get("/api/campaigns/{id}/" + path, campaignX).session(s))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("pin_required"));
        }
    }

    @Test
    void 퍼널은_캠페인_조회_매출은_매출_분석_설정을_따른다() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'SALES_ANALYTICS' WHERE id = ?", shop);
        MockHttpSession s = session(false);
        mvc.perform(get("/api/campaigns/{id}/funnel", campaignX).session(s))
                .andExpect(status().isOk());
        mvc.perform(get("/api/campaigns/{id}/revenue", campaignX).session(s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));

        jdbc.update("UPDATE shop SET pin_locked_features = 'CAMPAIGN_VIEW' WHERE id = ?", shop);
        mvc.perform(get("/api/campaigns/{id}/funnel", campaignX).session(s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        mvc.perform(get("/api/campaigns/{id}/revenue", campaignX).session(s))
                .andExpect(status().isOk());
    }

    /** 캠페인 조회 · 매출 분석은 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (명세서 2장) */
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

    private long menu(long shopId, String category) {
        return insert(
                "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                        + " cycle_min_days, cycle_max_days) VALUES (?, ?, '모의 시술', 30000, 60, 21, 28)",
                shopId,
                category);
    }

    private long template(long shopId) {
        return insert(
                "INSERT INTO message_template (shop_id, name, body, purpose)"
                        + " VALUES (?, '모의 템플릿', '모의 본문', 'REVISIT')",
                shopId);
    }

    private long campaign(long shopId, String category, long template) {
        return insert(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind, status,"
                        + " sent_at) VALUES (?, '모의 캠페인', ?, ?, 'AD', 'SENT', ?)",
                shopId,
                category,
                template,
                LocalDateTime.of(2026, 10, 1, 10, 0));
    }

    private long message(
            long campaign, long customer, String category, boolean control, String status) {
        return insert(
                "INSERT INTO campaign_message (shop_id, campaign_id, kind, customer_id, category,"
                        + " control_group, send_status, result_at) VALUES (?, ?, 'AD', ?, ?, ?, ?, ?)",
                shop,
                campaign,
                customer,
                category,
                control,
                status,
                status == null ? null : LocalDateTime.of(2026, 10, 1, 10, 0));
    }

    private long reservation(long customer, String status) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 10, 11, 0);
        return insert(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customer,
                staff,
                start,
                start.plusHours(1),
                status,
                LocalDateTime.of(2026, 10, 3, 9, 0));
    }

    private void reservationService(long reservation, long menu, long message) {
        insert(
                "INSERT INTO reservation_service (shop_id, reservation_id, service_menu_id,"
                        + " attributed_message_id) VALUES (?, ?, ?, ?)",
                shop,
                reservation,
                menu,
                message);
    }

    private long visit(long customer, String date, String source) {
        return insert(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, ?)",
                shop,
                customer,
                LocalDate.parse(date),
                source);
    }

    private void visitService(
            long visit, Long reservation, Long menu, String category, long message) {
        insert(
                "INSERT INTO visit_service (shop_id, visit_id, reservation_id, service_menu_id,"
                        + " category, staff_id, attributed_message_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                shop,
                visit,
                reservation,
                menu,
                category,
                reservation == null ? null : staff,
                message);
    }

    private void payment(
            long reservation, long customer, long visit, int amount, boolean canceled) {
        insert(
                "INSERT INTO payment (shop_id, customer_id, reservation_id, visit_id, amount, method,"
                        + " paid_at, canceled) VALUES (?, ?, ?, ?, ?, 'CARD', ?, ?)",
                shop,
                customer,
                reservation,
                visit,
                amount,
                LocalDateTime.of(2026, 10, 10, 12, 0),
                canceled);
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
