package com.tuktaklab.keepsy.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureSettingsReader;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/customers 요청 검증 · 권한 연결 (CONTRACTS 6장 「고객 (CUS)」). 업무 규칙은 {@link CustomerServiceTest}
 */
@WebMvcTest(CustomerController.class)
@Import({
    SecurityConfig.class,
    ShopSession.class,
    GlobalExceptionHandler.class,
    TimeConfig.class,
    FeatureGuard.class,
    CustomerControllerTest.SignInController.class
})
class CustomerControllerTest {

    /** 테스트용: 매장 1로 로그인하고 스탭 7을 현재 근무자로 둔다 */
    @RestController
    static class SignInController {
        private final ShopSession shopSession;

        SignInController(ShopSession shopSession) {
            this.shopSession = shopSession;
        }

        @PostMapping("/api/session")
        void signIn(HttpServletRequest req, HttpServletResponse res) {
            shopSession.signIn(1L, req, res);
            shopSession.setCurrentStaffId(req.getSession(), 7L);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean CustomerService service;
    @MockitoBean FeatureSettingsReader settings;

    private static final String BODY =
            """
            {"name":"모의 고객","phone":"%s","consents":["PRIVACY","MARKETING"],"consentMethod":"TABLET"}
            """;

    private MockHttpSession signIn() throws Exception {
        return (MockHttpSession)
                mvc.perform(post("/api/session").with(csrf()))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    @Test
    void 등록하면_201과_고객_ID_처리_스탭은_현재_근무자() throws Exception {
        when(service.register(eq(1L), eq(7L), any())).thenReturn(42L);

        mvc.perform(
                        post("/api/customers")
                                .session(signIn())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.formatted("010-0000-0000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(42));
        verify(service).register(eq(1L), eq(7L), any());
    }

    @Test
    void 휴대폰_형식이_틀리면_invalid와_칸별_사유() throws Exception {
        mvc.perform(
                        post("/api/customers")
                                .session(signIn())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.formatted("02-000-0000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("phone"));
    }
}
