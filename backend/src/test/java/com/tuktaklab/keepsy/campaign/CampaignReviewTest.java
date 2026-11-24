package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * 검토 체크 (Keepsy #93 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CAMPAIGN_SEND}는 기본 PIN이라 검토자는
 * 현재 근무자가 아니라 PIN 인증 스탭이다 (ARCHITECTURE 6장). 템플릿 수정 거부(F43)는 {@link MessageTemplateWriteTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignReviewTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired CampaignRepository campaigns;

    private TestShopData data;
    private long shop;
    private long worker;
    private long pinStaff;
    private long template;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        worker = data.staff(shop, "ACTIVE", false);
        pinStaff = data.staff(shop, "ACTIVE", true);
        jdbc.update(
                "INSERT INTO message_template (shop_id, name, body, purpose)"
                        + " VALUES (?, '동의 재확인', '{고객명}님', 'CONSENT_RECONFIRM')",
                shop);
        template =
                jdbc.queryForObject(
                        "SELECT id FROM message_template WHERE shop_id = ?", Long.class, shop);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 검토하면_PIN_인증_스탭과_시각을_남기고_그_전에는_review_required() throws Exception {
        // 동의 재확인 안내 캠페인에도 같은 검토 체크 (FR-CMP-13)
        long c = campaign("DRAFT");
        assertThatThrownBy(() -> campaigns.findById(c).orElseThrow().requireReviewed())
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.REVIEW_REQUIRED);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        review(c, shop, true).andExpect(status().isNoContent());

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT reviewed_by_staff_id, reviewed_at FROM campaign WHERE id = ?", c);
        assertThat(row).containsEntry("reviewed_by_staff_id", pinStaff);
        assertThat((LocalDateTime) row.get("reviewed_at")).isAfter(before);
        campaigns.findById(c).orElseThrow().requireReviewed();
    }

    @Test
    void 작성_중이_아니면_conflict_다른_매장_없는_캠페인은_not_found() throws Exception {
        for (String s : List.of("SCHEDULED", "SENDING", "SENT", "CANCELED")) {
            review(campaign(s), shop, true)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("conflict"));
        }
        long c = campaign("DRAFT");
        long other = data.shop();
        review(c, other, true).andExpect(status().isNotFound());
        review(Long.MAX_VALUE, shop, true).andExpect(status().isNotFound());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign WHERE shop_id = ?"
                                        + " AND reviewed_at IS NOT NULL",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void PIN_인증이_없으면_pin_required() throws Exception {
        review(campaign("DRAFT"), shop, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private long campaign(String status) {
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, template_id, message_kind, status)"
                        + " VALUES (?, '모의 캠페인', ?, 'INFO', ?)",
                shop,
                template,
                status);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM campaign WHERE shop_id = ?", Long.class, shop);
    }

    /** 현재 근무자는 worker, PIN 인증은 pinStaff */
    private ResultActions review(long campaignId, long shopId, boolean pin) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        }
        return mvc.perform(
                post("/api/campaigns/{id}/review", campaignId).session(session).with(csrf()));
    }
}
