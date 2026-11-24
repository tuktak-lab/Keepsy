package com.tuktaklab.keepsy.account;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
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

/** GET /api/shop 매장 정보 · 영업시간 (Keepsy #26, CONTRACTS 6장 SET, F20). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class ShopInfoTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private ResultActions getShop(Long worker) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        if (worker != null) {
            shopSession.setCurrentStaffId(session, worker);
        }
        return mvc.perform(get("/api/shop").session(session));
    }

    @Test
    void 매장명과_월요일부터_일요일까지_영업시간을_HH_mm으로_주고_번호가_없으면_null() throws Exception {
        getShop(staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("모의 매장"))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.senderNumber").value(nullValue()))
                .andExpect(jsonPath("$.unsubscribeNumber").value(nullValue()))
                .andExpect(jsonPath("$.openingHours.length()").value(7))
                .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.openingHours[0].open").value("10:00"))
                .andExpect(jsonPath("$.openingHours[0].close").value("19:00"))
                .andExpect(jsonPath("$.openingHours[6].dayOfWeek").value("SUNDAY"));
    }

    @Test
    void 정기_휴무_요일은_open_close가_null이고_발신번호_수신_거부_번호도_준다() throws Exception {
        jdbc.update(
                "UPDATE shop SET phone = '02-0000-0000', sender_number = '010-0000-0000',"
                        + " unsubscribe_number = '080-000-0000', sun_open_time = NULL,"
                        + " sun_close_time = NULL, sat_close_time = NULL WHERE id = ?",
                shop);

        getShop(staff)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("02-0000-0000"))
                .andExpect(jsonPath("$.senderNumber").value("010-0000-0000"))
                .andExpect(jsonPath("$.unsubscribeNumber").value("080-000-0000"))
                .andExpect(jsonPath("$.openingHours[6].open").value(nullValue()))
                .andExpect(jsonPath("$.openingHours[6].close").value(nullValue()))
                // 시작 · 종료 중 하나만 있으면 휴무로 본다
                .andExpect(jsonPath("$.openingHours[5].dayOfWeek").value("SATURDAY"))
                .andExpect(jsonPath("$.openingHours[5].open").value(nullValue()));
    }

    @Test
    void 근무자가_없으면_worker_required_403() throws Exception {
        getShop(null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }
}
