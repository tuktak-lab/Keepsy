package com.tuktaklab.keepsy.account;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.common.error.ErrorCode;
import com.tuktaklab.keepsy.common.error.GlobalExceptionHandler;
import com.tuktaklab.keepsy.common.security.SecurityConfig;
import com.tuktaklab.keepsy.common.security.ShopSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/shops 요청 검증 · 응답 모양 (Keepsy #14, CONTRACTS 6장). DB 없이 돈다. GET /api/shop은 권한 검사까지 보려고
 * {@link ShopInfoTest}에서 실제 MySQL로 확인한다.
 */
@WebMvcTest(ShopController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ShopSession.class})
class ShopControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ShopSignupService signupService;
    @MockitoBean ShopInfoService infoService;

    private static String body(String email, String password, String pin) {
        return """
        {"email":"%s","password":"%s","shopName":"테스트 네일","ownerName":"원장","ownerPin":"%s"}
        """
                .formatted(email, password, pin);
    }

    @Test
    void 로그인_없이_가입하면_201과_매장_ID() throws Exception {
        when(signupService.signUp("owner@example.com", "password1", "테스트 네일", "원장", "1234"))
                .thenReturn(5L);

        mvc.perform(
                        post("/api/shops")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("owner@example.com", "password1", "1234")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shopId").value(5));
    }

    @Test
    void PIN이_숫자_4_6자리가_아니면_invalid_400과_칸별_사유() throws Exception {
        mvc.perform(
                        post("/api/shops")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("owner@example.com", "password1", "12ab")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("ownerPin"))
                .andExpect(jsonPath("$.error.details[0].reason").value("PIN은 숫자 4~6자리입니다"));
        verifyNoInteractions(signupService);
    }

    @Test
    void 이메일_형식과_비밀번호_길이도_검증한다() throws Exception {
        mvc.perform(
                        post("/api/shops")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("not-an-email", "short", "1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.length()").value(2));
    }

    @Test
    void 이미_가입한_이메일이면_duplicate_409() throws Exception {
        when(signupService.signUp(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.DUPLICATE, "이미 가입한 이메일입니다"));

        mvc.perform(
                        post("/api/shops")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("owner@example.com", "password1", "1234")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
    }

    @Test
    void CSRF_토큰이_없으면_csrf_invalid_403() throws Exception {
        mvc.perform(
                        post("/api/shops")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("owner@example.com", "password1", "1234")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }
}
