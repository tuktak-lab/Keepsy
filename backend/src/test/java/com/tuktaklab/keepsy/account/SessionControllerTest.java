package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** POST /api/session 응답 · 세션 발급 (Keepsy #15, CONTRACTS 6장). DB 없이 돈다. */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, ShopSession.class, GlobalExceptionHandler.class})
class SessionControllerTest {

    private static final String BODY =
            "{\"email\":\"owner@example.com\",\"password\":\"password1\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ShopLoginService loginService;

    @Test
    void 로그인하면_매장_정보를_주고_새_세션에_매장을_둔다_이전_근무자는_지운다() throws Exception {
        when(loginService.login("owner@example.com", "password1"))
                .thenReturn(new LoggedIn(3L, "테스트 네일"));
        MockHttpSession before = new MockHttpSession();
        before.setAttribute("keepsy.currentStaffId", 99L);

        HttpSession after =
                mvc.perform(
                                post("/api/session")
                                        .session(before)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(BODY))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.shopId").value(3))
                        .andExpect(jsonPath("$.shopName").value("테스트 네일"))
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        assertThat(after.getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
        assertThat(after.getAttribute("keepsy.currentStaffId")).isNull();
    }

    @Test
    void 틀리면_어느_쪽이_틀렸는지_말하지_않고_login_failed_401() throws Exception {
        when(loginService.login("owner@example.com", "password1"))
                .thenThrow(new ApiException(ErrorCode.LOGIN_FAILED));

        mvc.perform(
                        post("/api/session")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("login_failed"))
                .andExpect(jsonPath("$.error.message").value("이메일 또는 비밀번호가 맞지 않습니다"));
    }

    @Test
    void 잠금_중이면_login_locked_429() throws Exception {
        when(loginService.login("owner@example.com", "password1"))
                .thenThrow(new ApiException(ErrorCode.LOGIN_LOCKED));

        mvc.perform(
                        post("/api/session")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("login_locked"));
    }

    @Test
    void 빈_값이면_invalid_400() throws Exception {
        mvc.perform(
                        post("/api/session")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        verifyNoInteractions(loginService);
    }
}
