package com.tuktaklab.keepsy.common.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 애너테이션이 붙은 API 앞에서 검사가 돌고 CONTRACTS 2장 모양으로 응답하는지 (Keepsy #4). DB 없이 돈다. */
@WebMvcTest(FeatureGuardWiringTest.TestController.class)
@Import({
    SecurityConfig.class,
    ShopSession.class,
    GlobalExceptionHandler.class,
    TimeConfig.class,
    FeatureGuard.class,
    FeatureGuardWiringTest.TestController.class
})
class FeatureGuardWiringTest {

    @RestController
    static class TestController {
        private final ShopSession shopSession;

        TestController(ShopSession shopSession) {
            this.shopSession = shopSession;
        }

        /** 테스트용: 매장 1로 로그인하고, worker가 있으면 현재 근무자로 둔다 */
        @PostMapping("/api/session")
        void signIn(
                @RequestParam(required = false) Long worker,
                HttpServletRequest req,
                HttpServletResponse res) {
            shopSession.signIn(1L, req, res);
            shopSession.setCurrentStaffId(req.getSession(), worker);
        }

        @RequireWorker
        @GetMapping("/api/staff")
        String staff() {
            return "ok";
        }

        @RequireFeature(FeatureKey.RESERVATION)
        @GetMapping("/api/reservations")
        String reservations() {
            return "ok";
        }

        @RequireFeature(FeatureKey.CONTACT_FULL)
        @GetMapping("/api/contact")
        String contact() {
            return "ok";
        }

        @GetMapping("/api/session")
        String sessionOnly() {
            return "ok";
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean FeatureSettingsReader settings;

    private MockHttpSession signIn(Long worker) throws Exception {
        var request = post("/api/session").with(csrf());
        if (worker != null) {
            request.param("worker", worker.toString());
        }
        return (MockHttpSession) mvc.perform(request).andReturn().getRequest().getSession(false);
    }

    @Test
    void 근무자가_없으면_근무자_API와_기능_API는_worker_required_403() throws Exception {
        MockHttpSession session = signIn(null);

        mvc.perform(get("/api/staff").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
        mvc.perform(get("/api/reservations").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
        mvc.perform(get("/api/session").session(session)).andExpect(status().isOk());
    }

    @Test
    void 근무자가_있으면_공용은_통과하고_PIN_기능은_pin_required_403() throws Exception {
        when(settings.lockedFeatures(1L)).thenReturn(EnumSet.of(FeatureKey.CONTACT_FULL));
        MockHttpSession session = signIn(10L);

        mvc.perform(get("/api/staff").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/reservations").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/contact").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }
}
