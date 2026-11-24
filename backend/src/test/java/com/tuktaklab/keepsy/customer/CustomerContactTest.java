package com.tuktaklab.keepsy.customer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 연락처 전체 보기 (Keepsy #39 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 매장의 기능 잠금은 기본값(명세서 2장, {@code
 * CONTACT_FULL}은 PIN)에서 시작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerContactTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;
    private String phone;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        customer = data.customer(shop, false, null);
        phone =
                jdbc.queryForObject(
                        "SELECT phone FROM customer WHERE id = ?", String.class, customer);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void PIN_인증이_없으면_pin_required이고_번호를_주지_않는다() throws Exception {
        contact(shop, customer, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"))
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void PIN_인증_뒤에는_전체_번호() throws Exception {
        contact(shop, customer, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone));
    }

    @Test
    void 공용으로_바꾸면_PIN_없이_바로_통과() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = '' WHERE id = ?", shop);

        contact(shop, customer, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone));
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        contact(data.shop(), customer, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        contact(shop, customer, true).andExpect(status().isNotFound());
    }

    private ResultActions contact(long shopId, long customerId, boolean pin) throws Exception {
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
        return mvc.perform(get("/api/customers/" + customerId + "/contact").session(session));
    }
}
