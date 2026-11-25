package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
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
 * 캠페인 미리보기 (Keepsy #92 완료 조건, 결정 F46 · F47). 실제 MySQL에서 돌고 모의 데이터만 쓴다. 치환 규칙 자체는 {@link
 * MessageRendererTest}. 캠페인 · 대상은 만든 뒤의 철회 · 삭제를 재현하려고 SQL로 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignPreviewTest {

    private static final String REVISIT = "{고객명}님, {매장명}입니다. 지난 {최근시술} 시술 후 {경과일}일이 지났어요.";
    private static final String RECONFIRM = "{고객명}님 {동의일} 동의 (야간: {야간광고동의})";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long staff;
    private LocalDate today;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        today = LocalDate.now(clock);
        shopNumbers("02-000-0000", "080-000-0000");
        jdbc.update("UPDATE shop SET virtual_unit_price = 10 WHERE id = ?", shop);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 광고는_고객별로_치환하고_대조군을_빼고_발송_불가를_한_번씩_센다() throws Exception {
        long menu = data.menu(shop, 60); // NAIL '젤'
        // a: 그날 주기 계산 제외 시술 다음의 메뉴 없는 시술 → 카테고리 이름
        long a = customer();
        LocalDate aDate = today.minusDays(30);
        prediction(a, aDate);
        long aVisit = visit(a, aDate);
        visitService(aVisit, menu, true);
        visitService(aVisit, null, false);
        // b: 메뉴 시술
        long b = customer();
        prediction(b, today.minusDays(5));
        visitService(visit(b, today.minusDays(5)), menu, false);
        // c: 예측 없음, d: 삭제(변수 값도 없음), e: 대조군
        long c = customer();
        long d = customer();
        long e = customer();
        long campaign = campaign("AD", "NAIL", REVISIT, List.of(a, b, c, d), List.of(e));
        delete(d);

        String aText =
                "(광고)모의 매장 02-000-0000\n모의 고객님, 모의 매장입니다. 지난 네일 시술 후 30일이 지났어요.\n무료수신거부 080-000-0000";
        preview(campaign, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendCount").value(4))
                .andExpect(jsonPath("$.controlCount").value(1))
                .andExpect(jsonPath("$.estimatedCost").value(40))
                .andExpect(jsonPath("$.unsendableCount").value(2))
                .andExpect(jsonPath("$.items.totalElements").value(4))
                .andExpect(
                        jsonPath("$.items.items[*].customerId")
                                .value(contains((int) a, (int) b, (int) c, (int) d)))
                .andExpect(jsonPath("$.items.items[0].text").value(aText))
                .andExpect(
                        jsonPath("$.items.items[0].bytes").value(MessageComposer.countBytes(aText)))
                .andExpect(jsonPath("$.items.items[0].messageType").value("LMS"))
                .andExpect(jsonPath("$.items.items[0].missingVariables", empty()))
                .andExpect(jsonPath("$.items.items[1].text").value(containsString("지난 젤 시술 후 5일이")))
                .andExpect(jsonPath("$.items.items[2].customerName").value("모의 고객"))
                .andExpect(jsonPath("$.items.items[2].text", nullValue()))
                .andExpect(jsonPath("$.items.items[2].bytes", nullValue()))
                .andExpect(jsonPath("$.items.items[2].messageType", nullValue()))
                .andExpect(jsonPath("$.items.items[2].missingVariables", contains("최근시술", "경과일")))
                .andExpect(jsonPath("$.items.items[3].customerName", nullValue()))
                .andExpect(jsonPath("$.items.items[3].messageType", nullValue()))
                .andExpect(
                        jsonPath(
                                "$.items.items[3].missingVariables",
                                contains("고객명", "최근시술", "경과일")));

        // 페이지는 발송 인원 안에서 자르고 합계는 전체 기준
        preview(campaign, "?page=1&size=3")
                .andExpect(jsonPath("$.items.items[*].customerId").value(contains((int) d)))
                .andExpect(jsonPath("$.items.page").value(1))
                .andExpect(jsonPath("$.unsendableCount").value(2));
        // 저장하지 않는다
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign_message WHERE campaign_id = ?",
                                Integer.class,
                                campaign))
                .isZero();
    }

    @Test
    void 삭제된_고객은_고객_변수가_없는_템플릿이어도_발송_불가() throws Exception {
        long alive = customer();
        long deleted = customer();
        long campaign = campaign("AD", null, "{매장명} 안내", List.of(alive, deleted), List.of());
        delete(deleted);

        preview(campaign, "")
                .andExpect(jsonPath("$.sendCount").value(2))
                .andExpect(jsonPath("$.unsendableCount").value(1))
                .andExpect(jsonPath("$.items.items[0].messageType").value("SMS"))
                .andExpect(jsonPath("$.items.items[1].customerName", nullValue()))
                .andExpect(jsonPath("$.items.items[1].text", nullValue()))
                .andExpect(jsonPath("$.items.items[1].messageType", nullValue()))
                .andExpect(jsonPath("$.items.items[1].missingVariables", empty()));
    }

    @Test
    void 동의일은_최신_마케팅_행이_동의일_때만이고_철회하면_그_고객만_발송_불가() throws Exception {
        long kept = customer();
        consent(kept, "MARKETING", true, LocalDateTime.of(2026, 3, 1, 10, 0));
        consent(kept, "NIGHT_AD", true, LocalDateTime.of(2026, 3, 1, 10, 0));
        long withdrawn = customer();
        consent(withdrawn, "MARKETING", true, LocalDateTime.of(2026, 3, 1, 10, 0));
        long again = customer();
        consent(again, "MARKETING", true, LocalDateTime.of(2026, 3, 1, 10, 0));
        long campaign =
                campaign("INFO", null, RECONFIRM, List.of(kept, withdrawn, again), List.of());
        // 만든 뒤 철회, 철회 후 재동의
        consent(withdrawn, "MARKETING", false, LocalDateTime.of(2026, 4, 1, 10, 0));
        consent(again, "MARKETING", false, LocalDateTime.of(2026, 4, 1, 10, 0));
        consent(again, "MARKETING", true, LocalDateTime.of(2026, 5, 2, 10, 0));

        preview(campaign, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unsendableCount").value(1))
                .andExpect(
                        jsonPath("$.items.items[0].text").value("모의 고객님 2026년 3월 1일 동의 (야간: 동의)"))
                .andExpect(jsonPath("$.items.items[1].text", nullValue()))
                .andExpect(jsonPath("$.items.items[1].missingVariables", contains("동의일")))
                .andExpect(
                        jsonPath("$.items.items[2].text").value("모의 고객님 2026년 5월 2일 동의 (야간: 미동의)"));
    }

    @Test
    void 광고는_매장_전화번호나_수신_거부_번호가_비면_conflict이고_안내는_검사하지_않는다() throws Exception {
        long customer = customer();
        consent(customer, "MARKETING", true, LocalDateTime.of(2026, 3, 1, 10, 0));
        long ad = campaign("AD", null, "{고객명}님", List.of(customer), List.of());
        long info = campaign("INFO", null, RECONFIRM, List.of(customer), List.of());

        preview(ad, "").andExpect(status().isOk());
        for (String[] numbers :
                new String[][] {{null, "080-000-0000"}, {"02-000-0000", null}, {null, null}}) {
            shopNumbers(numbers[0], numbers[1]);
            preview(ad, "")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("conflict"))
                    .andExpect(
                            jsonPath("$.error.message")
                                    .value("광고 표기에 필요한 매장 전화번호와 수신 거부 번호를 매장 정보에 먼저 등록해 주세요."));
            preview(info, "").andExpect(status().isOk());
        }
    }

    @Test
    void 다른_매장_캠페인은_not_found() throws Exception {
        long other = data.shop();
        long otherCustomer = data.customer(other, false, null);
        long otherCampaign =
                campaign(other, "AD", null, "{고객명}님", List.of(otherCustomer), List.of());

        preview(otherCampaign, "").andExpect(status().isNotFound());
    }

    private long customer() {
        return data.customer(shop, false, null);
    }

    private void delete(long customerId) {
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customerId);
    }

    private void shopNumbers(String phone, String unsubscribe) {
        jdbc.update(
                "UPDATE shop SET phone = ?, unsubscribe_number = ? WHERE id = ?",
                phone,
                unsubscribe,
                shop);
    }

    private void prediction(long customerId, LocalDate lastServiceDate) {
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category, last_service_date)"
                        + " VALUES (?, ?, 'NAIL', ?)",
                shop,
                customerId,
                lastServiceDate);
    }

    private long visit(long customerId, LocalDate date) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                date);
        return jdbc.queryForObject("SELECT MAX(id) FROM visit WHERE shop_id = ?", Long.class, shop);
    }

    private void visitService(long visitId, Long menuId, boolean cycleExcluded) {
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category, cycle_excluded)"
                        + " VALUES (?, ?, ?, 'NAIL', ?)",
                shop,
                visitId,
                menuId,
                cycleExcluded);
    }

    private void consent(long customerId, String item, boolean agreed, LocalDateTime at) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                shop,
                customerId,
                item,
                agreed,
                at,
                agreed ? staff : null,
                agreed ? "TABLET" : null,
                agreed ? null : "DIRECT");
    }

    private long campaign(
            String kind, String category, String body, List<Long> send, List<Long> control) {
        return campaign(shop, kind, category, body, send, control);
    }

    private long campaign(
            long shopId,
            String kind,
            String category,
            String body,
            List<Long> send,
            List<Long> control) {
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose) VALUES (?, '모의', ?, ?)",
                shopId,
                body,
                kind.equals("AD") ? "REVISIT" : "CONSENT_RECONFIRM");
        long template =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM message_template WHERE shop_id = ?",
                        Long.class,
                        shopId);
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, category, template_id, message_kind)"
                        + " VALUES (?, '모의', ?, ?, ?)",
                shopId,
                category,
                template,
                kind);
        long id =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM campaign WHERE shop_id = ?", Long.class, shopId);
        for (long c : send) {
            target(shopId, id, c, false);
        }
        for (long c : control) {
            target(shopId, id, c, true);
        }
        return id;
    }

    private void target(long shopId, long campaignId, long customerId, boolean control) {
        jdbc.update(
                "INSERT INTO campaign_target (shop_id, campaign_id, customer_id, control_group)"
                        + " VALUES (?, ?, ?, ?)",
                shopId,
                campaignId,
                customerId,
                control);
    }

    private ResultActions preview(long campaignId, String query) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        return mvc.perform(
                get("/api/campaigns/" + campaignId + "/preview" + query).session(session));
    }
}
