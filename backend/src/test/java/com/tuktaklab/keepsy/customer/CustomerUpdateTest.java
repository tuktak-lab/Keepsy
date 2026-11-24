package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 고객 기본 정보 수정 (Keepsy #40 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 매장의 기능 잠금은 기본값(명세서 2장, {@code
 * CUSTOMER_EDIT}은 PIN)에서 시작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerUpdateTest {

    private static final String BODY =
            """
            {"name":"%s","phone":"%s","gender":"FEMALE","birthYear":1995,
             "preferredLanguage":"KO","acquisitionChannel":"INSTAGRAM"}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void PIN_인증_뒤_통째로_바꾸고_번호는_하이픈_형태로_저장한다() throws Exception {
        update(shop, customer, BODY.formatted("바뀐 고객", "01000009999"), true)
                .andExpect(status().isNoContent());

        assertThat(row())
                .containsEntry("name", "바뀐 고객")
                .containsEntry("phone", "010-0000-9999")
                .containsEntry("gender", "FEMALE")
                .containsEntry("birth_year", 1995)
                .containsEntry("preferred_language", "KO")
                .containsEntry("acquisition_channel", "INSTAGRAM");

        // 빠진 선택 칸은 비운다
        update(shop, customer, "{\"name\":\"바뀐 고객\",\"phone\":\"010-0000-9999\"}", true)
                .andExpect(status().isNoContent());
        assertThat(row()).containsEntry("gender", null).containsEntry("birth_year", null);
    }

    @Test
    void PIN_인증이_없으면_pin_required이고_바뀌지_않는다() throws Exception {
        update(shop, customer, BODY.formatted("바뀐 고객", "010-0000-9999"), false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));

        assertThat(row()).containsEntry("name", "모의 고객");
    }

    @Test
    void 다른_고객과_같은_번호면_duplicate_자기_번호는_그대로_된다() throws Exception {
        long other = data.customer(shop, false, null);
        String otherPhone = phoneOf(other);

        update(shop, customer, BODY.formatted("모의 고객", otherPhone), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
        update(shop, customer, BODY.formatted("이름만 바꿈", phoneOf(customer)), true)
                .andExpect(status().isNoContent());
    }

    @Test
    void 필수_칸_형식_출생_연도가_틀리면_invalid() throws Exception {
        for (String body :
                new String[] {
                    BODY.formatted(" ", "010-0000-9999"),
                    BODY.formatted("모의 고객", "02-000-0000"),
                    "{\"name\":\"모의 고객\"}",
                    "{\"name\":\"모의 고객\",\"phone\":\"010-0000-9999\",\"birthYear\":2999}"
                }) {
            update(shop, customer, body, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        assertThat(row()).containsEntry("name", "모의 고객");
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        update(data.shop(), customer, BODY.formatted("모의 고객", "010-0000-9999"), true)
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        update(shop, customer, BODY.formatted("모의 고객", "010-0000-9999"), true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap(
                "SELECT name, phone, gender, birth_year, preferred_language, acquisition_channel"
                        + " FROM customer WHERE id = ?",
                customer);
    }

    private String phoneOf(long customerId) {
        return jdbc.queryForObject(
                "SELECT phone FROM customer WHERE id = ?", String.class, customerId);
    }

    private ResultActions update(long shopId, long customerId, String body, boolean pin)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return mvc.perform(
                patch("/api/customers/" + customerId)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
