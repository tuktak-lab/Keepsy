package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.SessionQueryService.SessionView;
import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 로그인 · 세션 조회 · 로그아웃 (Keepsy #15 · #16, CONTRACTS 6장). DB 없이 돈다. */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, ShopSession.class, GlobalExceptionHandler.class})
class SessionControllerTest {

    private static final String BODY =
            "{\"email\":\"owner@example.com\",\"password\":\"password1\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ShopLoginService loginService;
    @MockitoBean SessionQueryService queryService;

    private MockHttpSession loggedIn() throws Exception {
        when(loginService.login("owner@example.com", "password1"))
                .thenReturn(new LoggedIn(3L, "테스트 네일"));
        return (MockHttpSession)
                mvc.perform(
                                post("/api/session")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(BODY))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    @Test
    void 세션_조회는_근무자가_없어도_되고_없는_값은_null로_준다() throws Exception {
        MockHttpSession session = loggedIn();
        when(queryService.current(any()))
                .thenReturn(
                        new SessionView(
                                3L,
                                "테스트 네일",
                                null,
                                null,
                                null,
                                Map.of(FeatureKey.CONTACT_FULL, Access.PIN)));

        mvc.perform(get("/api/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("테스트 네일"))
                .andExpect(jsonPath("$.currentStaff").value(nullValue()))
                .andExpect(jsonPath("$.pinStaff").value(nullValue()))
                .andExpect(jsonPath("$.features.CONTACT_FULL").value("PIN"));
    }

    @Test
    void 로그인하지_않고_세션을_조회하면_unauthorized_401() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void 로그아웃하면_204이고_세션이_사라진다() throws Exception {
        MockHttpSession session = loggedIn();
        session.setAttribute("keepsy.currentStaffId", 10L);

        mvc.perform(delete("/api/session").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

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
