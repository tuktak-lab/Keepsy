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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.SessionQueryService.SessionView;
import com.tuktaklab.keepsy.account.ShopLoginService.LoggedIn;
import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.FeatureGuard;
import com.tuktaklab.keepsy.common.security.FeatureKey;
import com.tuktaklab.keepsy.common.security.FeatureKey.Access;
import com.tuktaklab.keepsy.common.security.FeatureSettingsReader;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
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

/**
 * 로그인 · 세션 조회 · 로그아웃 · 근무자 전환 · PIN 인증 (Keepsy #15~#18, CONTRACTS 6장). 근무자 검사까지 보려고 FeatureGuard를
 * 함께 띄운다. DB 없이 돈다.
 */
@WebMvcTest(SessionController.class)
@Import({
    SecurityConfig.class,
    ShopSession.class,
    GlobalExceptionHandler.class,
    TimeConfig.class,
    FeatureGuard.class
})
class SessionControllerTest {

    private static final String BODY =
            "{\"email\":\"owner@example.com\",\"password\":\"password1\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ShopLoginService loginService;
    @MockitoBean SessionQueryService queryService;
    @MockitoBean StaffPinVerifier pinVerifier;
    @MockitoBean FeatureSettingsReader featureSettings;

    private static org.springframework.test.web.servlet.RequestBuilder postPin(
            MockHttpSession session, String pin) {
        return post("/api/session/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\"}");
    }

    @Test
    void 근무자가_없으면_PIN_인증과_잠금은_worker_required_403() throws Exception {
        MockHttpSession session = loggedIn();

        mvc.perform(postPin(session, "1234"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
        mvc.perform(delete("/api/session/pin").session(session).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
        verifyNoInteractions(pinVerifier);
    }

    @Test
    void 잠긴_기능_허용_스탭의_PIN이면_인증되고_현재_근무자와_달라도_된다() throws Exception {
        MockHttpSession session = loggedIn();
        session.setAttribute("keepsy.currentStaffId", 10L);
        Staff owner = org.mockito.Mockito.mock(Staff.class);
        when(owner.getId()).thenReturn(20L);
        when(owner.getName()).thenReturn("원장");
        when(pinVerifier.verify(3L, "1234", true)).thenReturn(owner);

        mvc.perform(postPin(session, "1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.name").value("원장"))
                .andExpect(jsonPath("$.expiresAt").exists());

        assertThat(session.getAttribute("keepsy.pinStaffId")).isEqualTo(20L);
        assertThat(session.getAttribute("keepsy.pinExpiresAt")).isNotNull();
        assertThat(session.getAttribute("keepsy.currentStaffId")).isEqualTo(10L);
    }

    @Test
    void 허용이_꺼진_스탭이나_틀린_PIN이면_pin_invalid_400() throws Exception {
        MockHttpSession session = loggedIn();
        session.setAttribute("keepsy.currentStaffId", 10L);
        when(pinVerifier.verify(3L, "5678", true))
                .thenThrow(new ApiException(ErrorCode.PIN_INVALID));

        mvc.perform(postPin(session, "5678"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("pin_invalid"));
        assertThat(session.getAttribute("keepsy.pinStaffId")).isNull();
    }

    @Test
    void 잠금을_누르면_PIN_인증만_풀리고_근무자는_그대로() throws Exception {
        MockHttpSession session = loggedIn();
        session.setAttribute("keepsy.currentStaffId", 10L);
        session.setAttribute("keepsy.pinStaffId", 20L);

        mvc.perform(delete("/api/session/pin").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute("keepsy.pinStaffId")).isNull();
        assertThat(session.getAttribute("keepsy.pinExpiresAt")).isNull();
        assertThat(session.getAttribute("keepsy.currentStaffId")).isEqualTo(10L);
    }

    private static org.springframework.test.web.servlet.RequestBuilder putWorker(
            MockHttpSession session, String pin) {
        return put("/api/session/worker")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\"}");
    }

    @Test
    void 근무자_PIN이_맞으면_그_스탭이_현재_근무자가_된다_교대도_같은_API() throws Exception {
        MockHttpSession session = loggedIn();
        Staff worker = org.mockito.Mockito.mock(Staff.class);
        when(worker.getId()).thenReturn(10L);
        when(worker.getName()).thenReturn("근무자");
        when(pinVerifier.verify(3L, "5678", false)).thenReturn(worker);

        mvc.perform(putWorker(session, "5678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("근무자"));

        assertThat(session.getAttribute("keepsy.currentStaffId")).isEqualTo(10L);
    }

    @Test
    void 근무자_PIN이_틀리면_pin_invalid_400이고_근무자는_그대로() throws Exception {
        MockHttpSession session = loggedIn();
        session.setAttribute("keepsy.currentStaffId", 10L);
        when(pinVerifier.verify(3L, "0000", false))
                .thenThrow(new ApiException(ErrorCode.PIN_INVALID));

        mvc.perform(putWorker(session, "0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("pin_invalid"));

        assertThat(session.getAttribute("keepsy.currentStaffId")).isEqualTo(10L);
    }

    @Test
    void PIN_형식이_틀리면_invalid_400이고_확인하지_않는다() throws Exception {
        MockHttpSession session = loggedIn();

        mvc.perform(putWorker(session, "12ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        verifyNoInteractions(pinVerifier);
    }

    @Test
    void 로그인하지_않고_근무자_PIN을_넣으면_unauthorized_401() throws Exception {
        mvc.perform(
                        put("/api/session/worker")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"pin\":\"1234\"}"))
                .andExpect(status().isUnauthorized());
    }

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
