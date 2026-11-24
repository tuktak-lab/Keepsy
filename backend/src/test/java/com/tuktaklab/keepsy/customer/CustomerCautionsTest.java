package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 시술 · 건강 주의사항 (Keepsy #41 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CUSTOMER_WRITE}는 기본
 * 공용이다 (명세서 2장).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerCautionsTest {

    private static final String BODY = "{\"serviceCaution\":%s,\"healthCaution\":%s}";

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
    void 건강_정보_동의가_없으면_건강_주의사항은_consent_required이고_아무것도_바뀌지_않는다() throws Exception {
        save(shop, BODY.formatted("\"젤 리무버 주의\"", "\"아세톤 알레르기\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("consent_required"));

        assertThat(cautions()).containsEntry("service_caution", null);
        assertThat(logCount()).isZero();
    }

    @Test
    void 시술_주의사항은_동의_없이_저장하고_현재_근무자로_접근_로그() throws Exception {
        save(shop, BODY.formatted("\"  젤 리무버 주의 \"", "null")).andExpect(status().isNoContent());

        assertThat(cautions())
                .containsEntry("service_caution", "젤 리무버 주의")
                .containsEntry("health_caution", null);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT staff_id, action FROM access_log WHERE shop_id = ?", shop))
                .isEqualTo(Map.of("staff_id", staff, "action", "UPDATE"));
    }

    @Test
    void 건강_정보_동의가_있으면_두_칸_모두_저장_철회했으면_다시_consent_required() throws Exception {
        consent(true);
        save(shop, BODY.formatted("\"젤 리무버 주의\"", "\"아세톤 알레르기\""))
                .andExpect(status().isNoContent());
        assertThat(cautions()).containsEntry("health_caution", "아세톤 알레르기");

        consent(false);
        save(shop, BODY.formatted("null", "\"아세톤 알레르기\""))
                .andExpect(jsonPath("$.error.code").value("consent_required"));
    }

    @Test
    void 비우면_지우고_건강_주의사항_지우기는_동의가_없어도_된다() throws Exception {
        jdbc.update(
                "UPDATE customer SET service_caution = '젤', health_caution = '알레르기' WHERE id = ?",
                customer);

        save(shop, BODY.formatted("\" \"", "\"\"")).andExpect(status().isNoContent());

        assertThat(cautions())
                .containsEntry("service_caution", null)
                .containsEntry("health_caution", null);
    }

    @Test
    void 천_자를_넘으면_invalid_다른_매장_삭제된_고객은_not_found() throws Exception {
        save(shop, BODY.formatted("\"" + "가".repeat(1001) + "\"", "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        save(data.shop(), BODY.formatted("\"젤\"", "null")).andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        save(shop, BODY.formatted("\"젤\"", "null")).andExpect(status().isNotFound());
    }

    private Map<String, Object> cautions() {
        return jdbc.queryForMap(
                "SELECT service_caution, health_caution FROM customer WHERE id = ?", customer);
    }

    private int logCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_log WHERE shop_id = ?", Integer.class, shop);
    }

    private void consent(boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'HEALTH', ?, ?, ?, 'TABLET', ?)",
                shop,
                customer,
                agreed,
                LocalDateTime.now(),
                staff,
                agreed ? null : "DIRECT");
    }

    private ResultActions save(long shopId, String body) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(
                put("/api/customers/" + customer + "/cautions")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
