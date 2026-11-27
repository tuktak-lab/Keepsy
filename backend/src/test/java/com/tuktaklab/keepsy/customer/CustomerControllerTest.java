package com.tuktaklab.keepsy.customer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.audit.AccessLogger;
import com.tuktaklab.keepsy.common.audit.AccessLogger.Action;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureSettingsReader;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
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
 * POST · GET /api/customers 요청 검증 · 권한 연결 (CONTRACTS 6장 「고객 (CUS)」). 업무 규칙은 {@link
 * CustomerServiceTest}
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
    @MockitoBean CustomerImportService importService;
    @MockitoBean FeatureSettingsReader settings;
    @MockitoBean AccessLogger accessLogger;

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

    @Test
    void 목록은_페이지_모양으로_주고_검색어와_필터를_넘긴다() throws Exception {
        when(service.list(eq(1L), eq("민지"), any(), eq(0), eq(20)))
                .thenReturn(
                        new PageResponse<>(
                                List.of(
                                        new CustomerService.ListItem(
                                                3L, "김민지", "010-****-1234", 2, null, true)),
                                0,
                                20,
                                1));

        mvc.perform(
                        get("/api/customers")
                                .session(signIn())
                                .param("q", "민지")
                                .param("category", "NAIL")
                                .param("predictionStatus", "DUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].phone").value("010-****-1234"))
                .andExpect(jsonPath("$.totalElements").value(1));
        // 돌려준 페이지의 고객만 조회로 남긴다 (NFR-06, CONTRACTS 1장). 실제 저장은 CustomerSearchTest와 같은
        // AccessLogger.recordAll
        verify(accessLogger)
                .recordAll(
                        FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, List.of(3L), Action.VIEW);
    }

    @Test
    void 목록을_PIN으로_잠그면_pin_required이고_접근_로그를_남기지_않는다() throws Exception {
        when(settings.lockedFeatures(1L)).thenReturn(Set.of(FeatureKey.CUSTOMER_VIEW));
        mvc.perform(get("/api/customers").session(signIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        org.mockito.Mockito.verifyNoInteractions(accessLogger);
    }

    @Test
    void 상세는_세션_매장으로_조회하고_CUSTOMER_VIEW를_PIN으로_잠그면_pin_required() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/api/customers/3").session(session)).andExpect(status().isOk());
        verify(service).detail(1L, 3L);
        verify(accessLogger)
                .record(FeatureKey.CUSTOMER_VIEW, AccessLogger.CUSTOMER, 3L, Action.VIEW);

        when(settings.lockedFeatures(1L)).thenReturn(Set.of(FeatureKey.CUSTOMER_VIEW));
        mvc.perform(get("/api/customers/3").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    @Test
    void 목록_쿼리_값이_틀리면_invalid() throws Exception {
        MockHttpSession session = signIn();
        for (String[] bad :
                new String[][] {
                    {"category", "HAIR"}, {"predictionStatus", "LATE"}, {"size", "101"}
                }) {
            mvc.perform(get("/api/customers").session(session).param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }
}
