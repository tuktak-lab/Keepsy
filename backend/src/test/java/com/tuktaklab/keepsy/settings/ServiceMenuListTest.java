package com.tuktaklab.keepsy.settings;

import static org.hamcrest.Matchers.contains;
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

/** GET /api/service-menus 시술 메뉴 (Keepsy #28, CONTRACTS 6장 SET, F22). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceMenuListTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long worker;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        worker = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long menu(long shopId, String category, String name, String attribute) {
        long id = data.menu(shopId, 90);
        jdbc.update(
                "UPDATE service_menu SET category = ?, name = ?, price = 30000, cycle_min_days = 30,"
                        + " cycle_max_days = 60, prediction_attribute = ? WHERE id = ?",
                category,
                name,
                attribute,
                id);
        return id;
    }

    private MockHttpSession session(Long workerId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        if (workerId != null) {
            shopSession.setCurrentStaffId(session, workerId);
        }
        return session;
    }

    @Test
    void 매장_메뉴를_카테고리_순서_그다음_등록_순으로_모든_칸과_함께_준다() throws Exception {
        long etc = menu(shop, "ETC", "두피 케어", "EXCLUDE");
        long lash = menu(shop, "LASH", "속눈썹 연장", "NORMAL");
        long nail2 = menu(shop, "NAIL", "젤 제거", "END_SIGNAL");
        long nail1 = data.menu(shop, 60);
        long other = data.shop();
        menu(other, "NAIL", "다른 매장 메뉴", "NORMAL");

        mvc.perform(get("/api/service-menus").session(session(worker)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].id")
                                .value(contains((int) nail2, (int) nail1, (int) lash, (int) etc)))
                .andExpect(jsonPath("$[2].category").value("LASH"))
                .andExpect(jsonPath("$[2].name").value("속눈썹 연장"))
                .andExpect(jsonPath("$[2].price").value(30000))
                .andExpect(jsonPath("$[2].durationMinutes").value(90))
                .andExpect(jsonPath("$[2].cycleMinDays").value(30))
                .andExpect(jsonPath("$[2].cycleMaxDays").value(60))
                .andExpect(jsonPath("$[2].predictionAttribute").value("NORMAL"))
                .andExpect(jsonPath("$[0].predictionAttribute").value("END_SIGNAL"))
                .andExpect(jsonPath("$[3].predictionAttribute").value("EXCLUDE"));
    }

    @Test
    void 근무자가_없으면_worker_required_403() throws Exception {
        mvc.perform(get("/api/service-menus").session(session(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }
}
