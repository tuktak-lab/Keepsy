package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 캠페인 만들기 · 대조군 (Keepsy #89 완료 조건 · 검증 방법, 결정 F41). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 대상 고르기 규칙 자체는 {@link
 * CampaignTargetsTest}. {@code CAMPAIGN_SEND}는 기본 PIN이다.
 *
 * <pre>
 * 네일 행 + 마케팅 동의 15명, 네일 행 수신 거부 1명(u), 속눈썹 행 동의 1명(lash)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignCreateTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private final List<Long> nail = new ArrayList<>();
    private long u;
    private long ad;
    private long info;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        for (int i = 0; i < 15; i++) {
            nail.add(customer("NAIL", true, null));
        }
        u = customer("NAIL", true, "UNSUBSCRIBE");
        customer("LASH", true, null);
        ad = template(shop, "REVISIT");
        info = template(shop, "CONSENT_RECONFIRM");
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 광고는_제외_후_대상을_저장하고_대조군은_비율을_내림한_인원() throws Exception {
        long id =
                create(
                        "{\"name\":\" 11월 재방문 \",\"templateId\":%d,\"controlPercent\":10,\"category\":\"NAIL\"}"
                                .formatted(ad));

        assertThat(
                        jdbc.queryForMap(
                                "SELECT shop_id, name, category, template_id, message_kind,"
                                        + " control_percent, status FROM campaign WHERE id = ?",
                                id))
                .isEqualTo(
                        Map.of(
                                "shop_id", shop,
                                "name", "11월 재방문",
                                "category", "NAIL",
                                "template_id", ad,
                                "message_kind", "AD",
                                "control_percent", 10,
                                "status", "DRAFT"));
        // 15명 × 10% = 1.5 → 1명. 수신 거부 · 다른 카테고리 고객은 없다
        assertThat(targetIds(id)).containsExactlyInAnyOrderElementsOf(nail);
        assertThat(controlCount(id)).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign_target WHERE campaign_id = ? AND shop_id = ?",
                                Integer.class,
                                id,
                                shop))
                .isEqualTo(15);
        // 대조군 행은 발송을 시작할 때 만든다 (F7)
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign_message WHERE campaign_id = ?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void 대조군_비율을_안_보내면_0퍼센트라_대조군이_없다() throws Exception {
        long id =
                create("{\"name\":\"재방문\",\"templateId\":%d,\"category\":\"NAIL\"}".formatted(ad));

        assertThat(controlCount(id)).isZero();
        assertThat(targetIds(id)).hasSize(15);
    }

    @Test
    void 동의_재확인_안내는_카테고리_없이_INFO이고_대조군_비율은_invalid() throws Exception {
        long id =
                create(
                        "{\"name\":\"재확인\",\"templateId\":%d,\"customerIds\":[%d,%d]}"
                                .formatted(info, nail.get(0), u));

        assertThat(jdbc.queryForMap("SELECT category, message_kind FROM campaign WHERE id = ?", id))
                .containsEntry("category", null)
                .containsEntry("message_kind", "INFO");
        assertThat(targetIds(id)).containsExactly(nail.get(0));

        request(
                        "{\"name\":\"재확인\",\"templateId\":%d,\"controlPercent\":10,\"customerIds\":[%d]}"
                                .formatted(info, nail.get(0)),
                        true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("controlPercent"));
        request(
                        "{\"name\":\"재확인\",\"templateId\":%d,\"category\":\"NAIL\",\"customerIds\":[%d]}"
                                .formatted(info, nail.get(0)),
                        true)
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 규칙을_어기거나_보낼_대상이_없으면_invalid_다른_매장_템플릿은_not_found() throws Exception {
        for (String body :
                List.of(
                        "{\"name\":\" \",\"templateId\":%d,\"category\":\"NAIL\"}",
                        "{\"name\":\"재방문\",\"category\":\"NAIL\"}",
                        "{\"name\":\"재방문\",\"templateId\":%d,\"controlPercent\":51,\"category\":\"NAIL\"}",
                        "{\"name\":\"재방문\",\"templateId\":%d}",
                        "{\"name\":\"재방문\",\"templateId\":%d,\"category\":\"NAIL\",\"customerIds\":[%d]}")) {
            request(body.formatted(ad, u), true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        request(
                        "{\"name\":\"재방문\",\"templateId\":%d,\"category\":\"NAIL\"}"
                                .formatted(template(data.shop(), "REVISIT")),
                        true)
                .andExpect(status().isNotFound());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void PIN_인증이_없으면_pin_required() throws Exception {
        request("{\"name\":\"재방문\",\"templateId\":%d,\"category\":\"NAIL\"}".formatted(ad), false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private long create(String body) throws Exception {
        String json =
                request(body, true)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private List<Long> targetIds(long campaignId) {
        return jdbc.queryForList(
                "SELECT customer_id FROM campaign_target WHERE campaign_id = ?",
                Long.class,
                campaignId);
    }

    private int controlCount(long campaignId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign_target WHERE campaign_id = ? AND control_group",
                Integer.class,
                campaignId);
    }

    private long template(long shopId, String purpose) {
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose) VALUES (?, '모의', '{고객명}님', ?)",
                shopId,
                purpose);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM message_template WHERE shop_id = ?", Long.class, shopId);
    }

    /** 그 카테고리 예측 행과 마케팅 동의 이력을 만든다 */
    private long customer(String category, boolean agreed, String withdrawalPath) {
        long id = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO customer_prediction (shop_id, customer_id, category) VALUES (?, ?, ?)",
                shop,
                id,
                category);
        if (agreed) {
            consent(id, true, null);
        }
        if (withdrawalPath != null) {
            consent(id, false, withdrawalPath);
        }
        return id;
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

    private ResultActions request(String body, boolean pin) throws Exception {
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
                post("/api/campaigns")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
