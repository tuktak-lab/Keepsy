package com.tuktaklab.keepsy.settings;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
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

/** GET /api/day-offs 휴무일 (Keepsy #31, CONTRACTS 6장 SET, F22). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class DayOffListTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

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

    private void dayOff(long shopId, Long staffId, LocalDate date, String reason) {
        jdbc.update(
                "INSERT INTO day_off (shop_id, staff_id, off_date, reason) VALUES (?, ?, ?, ?)",
                shopId,
                staffId,
                date,
                reason);
    }

    private ResultActions dayOffs(Long worker, String from, String to) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        if (worker != null) {
            shopSession.setCurrentStaffId(session, worker);
        }
        return mvc.perform(
                get("/api/day-offs").param("from", from).param("to", to).session(session));
    }

    @Test
    void 기간_안_휴무일을_날짜순_같은_날은_매장_휴무_먼저_준다() throws Exception {
        dayOff(shop, staff, DAY.plusDays(2), null);
        dayOff(shop, null, DAY.plusDays(2), "매장 점검");
        dayOff(shop, null, DAY, "모의 휴무");
        dayOff(shop, null, DAY.plusDays(31), "기간 밖");
        dayOff(data.shop(), null, DAY, "다른 매장");

        dayOffs(staff, "2026-12-01", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].date")
                                .value(contains("2026-12-01", "2026-12-03", "2026-12-03")))
                .andExpect(jsonPath("$[0].staffId").value(nullValue()))
                .andExpect(jsonPath("$[0].reason").value("모의 휴무"))
                .andExpect(jsonPath("$[1].staffId").value(nullValue()))
                .andExpect(jsonPath("$[2].staffId").value((int) staff))
                .andExpect(jsonPath("$[2].reason").value(nullValue()));
    }

    @Test
    void 기간이_31일을_넘거나_순서가_거꾸로면_invalid_400() throws Exception {
        dayOffs(staff, "2026-12-01", "2027-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        dayOffs(staff, "2026-12-02", "2026-12-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 근무자가_없으면_worker_required_403() throws Exception {
        dayOffs(null, "2026-12-01", "2026-12-07")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }
}
