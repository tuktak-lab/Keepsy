package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET · PUT /api/settings 매장 설정값 (Keepsy #33, CONTRACTS 6장 SET, FR-SET-03 · 04 · 06, F40). 실제
 * MySQL에서 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsTest {

    private static final String BODY =
            "{\"churnSignalCapDays\":%d,\"attributionDays\":%d,\"dailySendLimit\":%d,"
                    + "\"resendBlockDays\":%d,\"virtualFailurePercent\":%d,\"retentionYears\":%d}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long owner;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private MockHttpSession session(boolean ownerPin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, owner);
        if (ownerPin) {
            shopSession.setPin(session, owner, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private ResultActions putSettings(String body) throws Exception {
        return mvc.perform(
                put("/api/settings")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions getSettings() throws Exception {
        return mvc.perform(get("/api/settings").session(session(true))).andExpect(status().isOk());
    }

    @Test
    void 새_매장은_기본값이고_바꾸면_조회와_매장_정보의_보관_기간에_반영된다() throws Exception {
        getSettings()
                .andExpect(jsonPath("$.churnSignalCapDays").value(120))
                .andExpect(jsonPath("$.attributionDays").value(14))
                .andExpect(jsonPath("$.dailySendLimit").value(500))
                .andExpect(jsonPath("$.resendBlockDays").value(7))
                .andExpect(jsonPath("$.virtualFailurePercent").value(0))
                .andExpect(jsonPath("$.retentionYears").value(3));

        putSettings(BODY.formatted(90, 7, 1000, 0, 30, 5)).andExpect(status().isNoContent());

        getSettings()
                .andExpect(jsonPath("$.churnSignalCapDays").value(90))
                .andExpect(jsonPath("$.attributionDays").value(7))
                .andExpect(jsonPath("$.dailySendLimit").value(1000))
                .andExpect(jsonPath("$.resendBlockDays").value(0))
                .andExpect(jsonPath("$.virtualFailurePercent").value(30))
                .andExpect(jsonPath("$.retentionYears").value(5));
        // 동의서 고지용: 근무자가 읽는 매장 정보에도 나온다 (FR-SET-06, F40)
        mvc.perform(get("/api/shop").session(session(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionYears").value(5));
    }

    @Test
    void 범위_밖이거나_빠지면_invalid_400과_칸별_사유이고_아무것도_바뀌지_않는다() throws Exception {
        putSettings(BODY.formatted(120, 14, 500, 7, 0, 6))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("retentionYears"));
        for (String body :
                List.of(
                        BODY.formatted(29, 14, 500, 7, 0, 3),
                        BODY.formatted(120, 61, 500, 7, 0, 3),
                        BODY.formatted(120, 14, 10_001, 7, 0, 3),
                        BODY.formatted(120, 14, 500, -1, 0, 3),
                        BODY.formatted(120, 14, 500, 7, 101, 3),
                        BODY.formatted(90, 7, 1000, 0, 30, 0),
                        "{\"churnSignalCapDays\":90}")) {
            putSettings(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }

        assertThat(
                        jdbc.queryForMap(
                                "SELECT churn_signal_cap_days, retention_years FROM shop WHERE id = ?",
                                shop))
                .containsEntry("churn_signal_cap_days", 120)
                .containsEntry("retention_years", 3);
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required() throws Exception {
        mvc.perform(get("/api/settings").session(session(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        mvc.perform(
                        put("/api/settings")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.formatted(90, 7, 1000, 0, 30, 5)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }
}
