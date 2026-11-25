package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.error.ApiException;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비밀번호 재설정 · 가상 메일함 (Keepsy #25, CONTRACTS 6장 ACC, FR-ACC-06, C3, F14 · F49). 실제 MySQL에서 돈다. 모의
 * 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordResetService service;

    private TestShopData data;
    private long shop;
    private String email;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        email = jdbc.queryForObject("SELECT email FROM shop WHERE id = ?", String.class, shop);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private ResultActions requestMail(String address) throws Exception {
        return mvc.perform(
                post("/api/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(
                put("/api/password-reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"token\":\"%s\",\"newPassword\":\"%s\"}"
                                        .formatted(token, password)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(
                post("/api/session")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                        .formatted(email, password)));
    }

    /** 가장 최근 가상 메일의 재설정 토큰 */
    private String latestToken() {
        String body =
                jdbc.queryForObject(
                        "SELECT body FROM virtual_mail WHERE shop_id = ? ORDER BY id DESC LIMIT 1",
                        String.class,
                        shop);
        Matcher m = TOKEN.matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MockHttpSession shopSession(long shopId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        return session;
    }

    @Test
    void 요청하면_가상_메일함에_링크가_오고_그_토큰으로_바꾼_비밀번호로_로그인되며_토큰은_한_번만_쓴다() throws Exception {
        requestMail(email).andExpect(status().isNoContent());
        String token = latestToken();
        // 토큰 원문은 저장하지 않는다 (F14)
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?",
                                Integer.class,
                                token))
                .isZero();

        reset(token, "new-password-1").andExpect(status().isNoContent());

        login("new-password-1").andExpect(status().isOk());
        reset(token, "another-password-2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"))
                .andExpect(jsonPath("$.error.details[0].field").value("token"));
    }

    @Test
    void 가입되지_않은_이메일도_204이고_메일은_가지_않는다() throws Exception {
        requestMail("nobody-" + System.nanoTime() + "@example.com")
                .andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM virtual_mail WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 만료된_토큰과_없는_토큰은_invalid이고_비밀번호는_그대로() throws Exception {
        requestMail(email).andExpect(status().isNoContent());
        String token = latestToken();
        jdbc.update(
                "UPDATE password_reset_token SET expires_at = ? WHERE shop_id = ?",
                LocalDateTime.now().minusDays(1),
                shop);

        reset(token, "new-password-1").andExpect(status().isBadRequest());
        reset("no-such-token", "new-password-1").andExpect(status().isBadRequest());
        reset(token, "short").andExpect(status().isBadRequest());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_hash FROM shop WHERE id = ?", String.class, shop))
                .isEqualTo("x");
    }

    @Test
    void 재설정하면_로그인_잠금이_풀리고_먼저_받은_다른_링크는_쓸_수_없다() throws Exception {
        jdbc.update(
                "UPDATE shop SET login_fail_count = 3, login_locked_until = ? WHERE id = ?",
                LocalDateTime.now().plusMinutes(10),
                shop);
        requestMail(email).andExpect(status().isNoContent());
        String older = latestToken();
        requestMail(email).andExpect(status().isNoContent());
        String newer = latestToken();

        reset(newer, "new-password-1").andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT login_fail_count, login_locked_until FROM shop WHERE id = ?",
                                shop))
                .containsEntry("login_fail_count", 0)
                .containsEntry("login_locked_until", null);
        reset(older, "another-password-2").andExpect(status().isBadRequest());
    }

    @Test
    void 같은_토큰으로_동시에_요청해도_하나만_성공한다() throws Exception {
        requestMail(email).andExpect(status().isNoContent());
        String token = latestToken();

        Callable<Boolean> attempt =
                () -> {
                    try {
                        service.reset(token, "new-password-1");
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt));
            long successes = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    successes++;
                }
            }
            assertThat(successes).isOne();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void 가상_메일함은_로그인한_그_매장_메일만_보이고_로그인하지_않으면_401() throws Exception {
        requestMail(email).andExpect(status().isNoContent());

        mvc.perform(get("/api/virtual-mailbox").session(shopSession(shop)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].subject").value(PasswordResetService.SUBJECT));
        mvc.perform(get("/api/virtual-mailbox").session(shopSession(data.shop())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/virtual-mailbox")).andExpect(status().isUnauthorized());
    }
}
