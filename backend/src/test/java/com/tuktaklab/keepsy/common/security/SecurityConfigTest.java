package com.tuktaklab.keepsy.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 세션 로그인 · CSRF · 오류 모양 (Keepsy #3 「작업 항목」 테스트). DB 없이 돈다. */
@WebMvcTest(SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, ShopSession.class, SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @RestController
    static class TestController {
        private final ShopSession shopSession;

        TestController(ShopSession shopSession) {
            this.shopSession = shopSession;
        }

        /** 비로그인 허용 경로. 로그인 기능(FR-ACC-02) 대신 바로 로그인시킨다 */
        @PostMapping("/api/session")
        void signIn(HttpServletRequest request, HttpServletResponse response) {
            shopSession.signIn(7L, request, response);
        }

        @GetMapping("/api/protected")
        String protectedApi() {
            return "ok";
        }

        @PostMapping("/api/protected")
        String protectedPost() {
            return "ok";
        }
    }

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void 로그인하지_않고_보호_API를_부르면_unauthorized_401() throws Exception {
        mvc.perform(get("/api/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void CSRF_토큰_없이_상태를_바꾸면_csrf_invalid_403() throws Exception {
        mvc.perform(post("/api/session"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("csrf_invalid"));
    }

    @Test
    void 요청하면_프론트가_읽을_XSRF_TOKEN_쿠키를_내려_준다() throws Exception {
        mvc.perform(get("/api/protected")).andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void 로그인한_세션은_보호_API를_쓸_수_있다() throws Exception {
        MockHttpSession session =
                (MockHttpSession)
                        mvc.perform(post("/api/session").with(csrf()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getRequest()
                                .getSession(false);

        mvc.perform(get("/api/protected").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/protected").session(session).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호와_PIN은_BCrypt로_해시한다() {
        String hash = passwordEncoder.encode("1234");

        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches("1234", hash)).isTrue();
    }
}
