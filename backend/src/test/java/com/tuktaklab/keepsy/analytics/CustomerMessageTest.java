package com.tuktaklab.keepsy.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
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
 * 고객 수신 이력 · 캠페인 연결 기록 (Keepsy #105 완료 조건 · 검증 방법, CONTRACTS 6장 「고객 (CUS)」, 결정 F37). 귀속은 저장된 값을
 * 읽으므로 귀속 발송 ID를 직접 넣은 답을 아는 모의 데이터로 확인한다. 실제 MySQL, 모의 데이터만 쓴다.
 *
 * <p>고객이 받은 메시지: 네일 광고 X(10/1, 취소 예약 → 승계 예약 → 기간 밖 방문, F33), 동의 재확인 안내(10/3), 수신 거부 처리 안내(10/5).
 * 넣지 않는 것: 실패 · 대기 · 대조군 행, 다른 고객이 받은 메시지.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerMessageTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long nail;
    private long template;
    private long customer;
    private long campaignX;
    private long consentCampaign;
    private long mX;
    private long mConsent;
    private long mUnsubscribe;
    private long rCanceled;
    private long rNext;
    private long visit;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        // 가상 발송기 스케줄러(3초마다)가 PENDING 행을 먼저 처리해도 결과가 같도록 실패 비율을 100%로 둔다 (PENDING → FAILED,
        // VirtualPhoneInboxTest와 같은 방식)
        jdbc.update("UPDATE shop SET virtual_failure_percent = 100 WHERE id = ?", shop);
        staff = data.staff(shop, "ACTIVE", false);
        nail = data.menu(shop, 60);
        long lash =
                insert(
                        "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                                + " cycle_min_days, cycle_max_days)"
                                + " VALUES (?, 'LASH', '모의 시술', 30000, 60, 21, 28)",
                        shop);
        template =
                insert(
                        "INSERT INTO message_template (shop_id, name, body, purpose)"
                                + " VALUES (?, '모의 템플릿', '모의 본문', 'REVISIT')",
                        shop);
        customer = data.customer(shop, false, null);

        campaignX = campaign("NAIL", "AD", "네일 재방문");
        consentCampaign = campaign(null, "INFO", "동의 재확인");
        mX = message(campaignX, "AD", customer, "NAIL", false, "SENT", day(1));
        mConsent =
                message(
                        consentCampaign,
                        "CONSENT_RECONFIRM",
                        customer,
                        null,
                        false,
                        "SENT",
                        day(3));
        mUnsubscribe = message(null, "UNSUBSCRIBE_NOTICE", customer, null, false, "SENT", day(5));

        // 받지 않은 행은 넣지 않는다 (F37). 실패 행에 귀속이 잘못 들어가 있어도 나오지 않는다
        long mFailed =
                message(
                        campaign("NAIL", "AD", "실패"),
                        "AD",
                        customer,
                        "NAIL",
                        false,
                        "FAILED",
                        day(7));
        message(campaign("NAIL", "AD", "대기"), "AD", customer, "NAIL", false, "PENDING", null);
        message(campaign("NAIL", "AD", "대조군"), "AD", customer, "NAIL", true, null, null);
        message(campaignX, "AD", data.customer(shop, false, null), "NAIL", false, "SENT", day(9));

        // 취소된 예약과 승계 예약이 같은 발송을 갖는다 (FR-VIS-04). 승계 예약은 네일 두 개 → 한 번만
        rCanceled = reservation("CANCELED", LocalDate.of(2026, 10, 10));
        reservationService(rCanceled, nail, mX);
        rNext = reservation("COMPLETED", LocalDate.of(2026, 10, 12));
        reservationService(rNext, nail, mX);
        reservationService(rNext, nail, mX);
        // 다른 카테고리 시술에 잘못 들어간 귀속은 퍼널처럼 빼고 (E7), 실패 행의 귀속도 나오지 않는다
        reservationService(reservation("COMPLETED", LocalDate.of(2026, 10, 14)), lash, mX);
        reservationService(reservation("COMPLETED", LocalDate.of(2026, 10, 16)), nail, mFailed);

        // 귀속 기간(14일) 밖 방문도 승계로 든다 (F33). 가져온 방문은 빠진다 (FR-CUS-13)
        visit = visit("2026-11-20", "NORMAL");
        visitService(visit, rNext, nail, "NAIL", mX);
        visitService(visit, rNext, lash, "LASH", mX);
        visitService(visit("2026-10-20", "IMPORT"), null, null, "NAIL", mX);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 받은_메시지만_최신순으로_종류와_상관없이_주고_광고에_귀속된_예약_방문을_붙인다() throws Exception {
        mvc.perform(get("/api/customers/{id}/messages", customer).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(3))
                // 수신 거부 처리 안내: 캠페인 · 카테고리 없음
                .andExpect(jsonPath("$.items[0].messageId").value(mUnsubscribe))
                .andExpect(jsonPath("$.items[0].kind").value("UNSUBSCRIBE_NOTICE"))
                .andExpect(jsonPath("$.items[0].campaign").value(nullValue()))
                .andExpect(jsonPath("$.items[0].category").value(nullValue()))
                .andExpect(jsonPath("$.items[0].reservations.length()").value(0))
                .andExpect(jsonPath("$.items[0].visits.length()").value(0))
                .andExpect(jsonPath("$.items[0].receivedAt").value(endsWith("+09:00")))
                // 동의 재확인 안내: 카테고리가 없어 귀속 없음 (E7)
                .andExpect(jsonPath("$.items[1].messageId").value(mConsent))
                .andExpect(jsonPath("$.items[1].kind").value("CONSENT_RECONFIRM"))
                .andExpect(jsonPath("$.items[1].campaign.id").value(consentCampaign))
                .andExpect(jsonPath("$.items[1].campaign.name").value("동의 재확인"))
                .andExpect(jsonPath("$.items[1].reservations.length()").value(0))
                // 광고: 캠페인 → 예약(취소 포함, 승계) → 방문
                .andExpect(jsonPath("$.items[2].messageId").value(mX))
                .andExpect(jsonPath("$.items[2].kind").value("AD"))
                .andExpect(jsonPath("$.items[2].campaign.id").value(campaignX))
                .andExpect(jsonPath("$.items[2].campaign.name").value("네일 재방문"))
                .andExpect(jsonPath("$.items[2].category").value("NAIL"))
                .andExpect(jsonPath("$.items[2].reservations.length()").value(2))
                .andExpect(jsonPath("$.items[2].reservations[0].id").value(rCanceled))
                .andExpect(jsonPath("$.items[2].reservations[0].status").value("CANCELED"))
                .andExpect(
                        jsonPath("$.items[2].reservations[0].startAt")
                                .value("2026-10-10T11:00:00+09:00"))
                .andExpect(jsonPath("$.items[2].reservations[1].id").value(rNext))
                .andExpect(jsonPath("$.items[2].reservations[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[2].visits.length()").value(1))
                .andExpect(jsonPath("$.items[2].visits[0].id").value(visit))
                .andExpect(jsonPath("$.items[2].visits[0].visitDate").value("2026-11-20"));

        mvc.perform(
                        get("/api/customers/{id}/messages", customer)
                                .param("page", "1")
                                .param("size", "2")
                                .session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(mX))
                .andExpect(jsonPath("$.items[0].reservations.length()").value(2));
    }

    @Test
    void 받은_메시지가_없으면_빈_목록이고_조회는_접근_로그에_남는다() throws Exception {
        long other = data.customer(shop, false, null);
        mvc.perform(get("/api/customers/{id}/messages", other).session(session(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        // 캠페인 조회는 기본 PIN이라 기록 스탭은 PIN 인증 스탭 (NFR-06, ARCHITECTURE 6장)
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM access_log WHERE shop_id = ? AND staff_id = ?"
                                        + " AND target_type = 'CUSTOMER' AND target_id = ?"
                                        + " AND action = 'VIEW'",
                                Integer.class,
                                shop,
                                staff,
                                other))
                .isEqualTo(1);
    }

    @Test
    void 캠페인_조회_설정을_따른다_잠겨_있으면_pin_required() throws Exception {
        mvc.perform(get("/api/customers/{id}/messages", customer).session(session(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));

        // 매출 분석만 잠그고 캠페인 조회를 공용으로 두면 PIN 없이 보인다 (명세서 2장)
        jdbc.update("UPDATE shop SET pin_locked_features = 'SALES_ANALYTICS' WHERE id = ?", shop);
        mvc.perform(get("/api/customers/{id}/messages", customer).session(session(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found_size가_범위_밖이면_invalid() throws Exception {
        long otherShopCustomer = data.customer(data.shop(), false, null);
        mvc.perform(get("/api/customers/{id}/messages", otherShopCustomer).session(session(true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mvc.perform(
                        get("/api/customers/{id}/messages", customer)
                                .param("size", "101")
                                .session(session(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        mvc.perform(get("/api/customers/{id}/messages", customer).session(session(true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    /** 캠페인 조회는 기본 PIN 잠금이라 PIN 인증을 함께 채운다 (명세서 2장) */
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

    private static LocalDateTime day(int dayOfOctober) {
        return LocalDateTime.of(2026, 10, dayOfOctober, 10, 0);
    }

    private long campaign(String category, String messageKind, String name) {
        return insert(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind, status,"
                        + " sent_at) VALUES (?, ?, ?, ?, ?, 'SENT', ?)",
                shop,
                name,
                category,
                template,
                messageKind,
                day(1));
    }

    private long message(
            Long campaign,
            String kind,
            long customerId,
            String category,
            boolean control,
            String status,
            LocalDateTime resultAt) {
        return insert(
                "INSERT INTO campaign_message (shop_id, campaign_id, kind, customer_id, category,"
                        + " control_group, recipient_phone, body, send_status, result_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, '010-0000-0000', '모의 본문', ?, ?)",
                shop,
                campaign,
                kind,
                customerId,
                category,
                control,
                status,
                resultAt);
    }

    private long reservation(String status, LocalDate date) {
        LocalDateTime start = date.atTime(11, 0);
        return insert(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PHONE', ?)",
                shop,
                customer,
                staff,
                start,
                start.plusHours(1),
                status,
                day(2));
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

    private long visit(String date, String source) {
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
