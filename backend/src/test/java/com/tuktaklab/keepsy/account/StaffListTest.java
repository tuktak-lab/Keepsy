package com.tuktaklab.keepsy.account;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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

/** GET /api/staff 스탭 목록 (Keepsy #20, CONTRACTS 6장 ACC, F22). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffListTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long worker;
    private long owner;
    private long inactive;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        // 등록 순서와 정렬이 다르도록 직원 → 비활성 → 원장 순으로 만든다
        worker = data.staff(shop, "ACTIVE", false);
        inactive = data.staff(shop, "INACTIVE", false);
        owner = data.staff(shop, "ACTIVE", true);
        jdbc.update(
                "UPDATE staff SET regular_days_off = 'FRIDAY,MONDAY', locked_feature_allowed = TRUE"
                        + " WHERE id = ?",
                worker);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private MockHttpSession session(Long workerId, Long pinStaffId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        if (workerId != null) {
            shopSession.setCurrentStaffId(session, workerId);
        }
        if (pinStaffId != null) {
            shopSession.setPin(session, pinStaffId, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    @Test
    void 담당_스탭_목록은_활성만_원장_먼저_등록_순이고_id와_이름만_준다() throws Exception {
        mvc.perform(get("/api/staff").session(session(worker, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains((int) owner, (int) worker)))
                .andExpect(jsonPath("$[0].name").value("스탭"))
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].pinHash").doesNotExist());
    }

    @Test
    void 관리용_목록은_원장_PIN이면_비활성까지_상태_정기_휴무와_함께_준다() throws Exception {
        mvc.perform(
                        get("/api/staff")
                                .param("includeInactive", "true")
                                .session(session(worker, owner)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[*].id")
                                .value(contains((int) owner, (int) worker, (int) inactive)))
                .andExpect(jsonPath("$[0].owner").value(true))
                .andExpect(jsonPath("$[0].regularDaysOff").value(empty()))
                .andExpect(jsonPath("$[1].lockedFeatureAllowed").value(true))
                .andExpect(jsonPath("$[1].regularDaysOff").value(contains("MONDAY", "FRIDAY")))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[2].status").value("INACTIVE"))
                .andExpect(jsonPath("$[*].pinHash").value(empty()));
    }

    @Test
    void 관리용_목록은_원장_PIN_인증이_없으면_owner_pin_required_403() throws Exception {
        mvc.perform(
                        get("/api/staff")
                                .param("includeInactive", "true")
                                .session(session(worker, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
        // 원장이 아닌 스탭의 PIN 인증도 거부
        mvc.perform(
                        get("/api/staff")
                                .param("includeInactive", "true")
                                .session(session(worker, worker)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }

    @Test
    void 근무자가_없으면_worker_required_403() throws Exception {
        mvc.perform(get("/api/staff").session(session(null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }
}
