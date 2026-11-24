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
 * PUT /api/feature-locks 기능별 공용/PIN 변경 (Keepsy #24, CONTRACTS 6장 ACC, FR-ACC-05, F38). 실제 MySQL에서
 * 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureLockTest {

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

    /** 현재 근무자는 원장. ownerPin이면 원장 PIN 인증까지 */
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

    private ResultActions putLocks(String features) throws Exception {
        return mvc.perform(
                put("/api/feature-locks")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"features\":" + features + "}"));
    }

    private String locked() {
        return jdbc.queryForObject(
                "SELECT pin_locked_features FROM shop WHERE id = ?", String.class, shop);
    }

    @Test
    void 고객_조회를_PIN으로_바꾸면_바로_다음_요청부터_pin_required_다시_공용이면_통과() throws Exception {
        MockHttpSession noPin = session(false);
        mvc.perform(get("/api/customers").session(noPin)).andExpect(status().isOk());

        putLocks("{\"CUSTOMER_VIEW\":\"PIN\"}").andExpect(status().isNoContent());

        mvc.perform(get("/api/customers").session(noPin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        mvc.perform(get("/api/session").session(noPin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.CUSTOMER_VIEW").value("PIN"))
                .andExpect(jsonPath("$.features.CONTACT_FULL").value("PIN"));

        putLocks("{\"CUSTOMER_VIEW\":\"PUBLIC\"}").andExpect(status().isNoContent());

        mvc.perform(get("/api/customers").session(noPin)).andExpect(status().isOk());
    }

    @Test
    void 고정_항목을_바꾸거나_모르는_키면_invalid_400과_칸별_사유이고_바뀌지_않는다() throws Exception {
        String before = locked();

        putLocks("{\"CUSTOMER_VIEW\":\"PIN\",\"CONSENT\":\"PIN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("features.CONSENT"));
        putLocks("{\"OWNER_PIN\":\"PUBLIC\"}").andExpect(status().isBadRequest());
        putLocks("{\"NO_SUCH_KEY\":\"PIN\"}").andExpect(status().isBadRequest());
        putLocks("{}").andExpect(status().isBadRequest());
        putLocks("null").andExpect(status().isBadRequest());

        assertThat(locked()).isEqualTo(before);
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required() throws Exception {
        mvc.perform(
                        put("/api/feature-locks")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"features\":{\"CUSTOMER_VIEW\":\"PIN\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }
}
