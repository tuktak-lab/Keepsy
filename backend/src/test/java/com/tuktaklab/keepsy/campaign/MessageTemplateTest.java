package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.ShopSignupService;
import com.tuktaklab.keepsy.common.security.ShopSession;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지 템플릿 목록 · 매장 기본 템플릿 (Keepsy #85 완료 조건 · 검증 방법, CONTRACTS 6장). 실제 MySQL에서 돌고, 가입으로 만든 매장은 테스트가
 * 끝나면 되돌린다 (MockMvc도 같은 트랜잭션).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageTemplateTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ShopSignupService signup;

    private long shop;
    private long owner;

    @BeforeEach
    void seed() {
        shop =
                signup.signUp(
                        "template-" + System.nanoTime() + "@example.com",
                        "password1",
                        "모의 네일",
                        "원장",
                        "1234");
        owner = jdbc.queryForObject("SELECT id FROM staff WHERE shop_id = ?", Long.class, shop);
    }

    private MockHttpSession session(long shopId, long worker, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, worker, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    @Test
    void 가입한_매장에는_기본_템플릿_4개가_있고_동의_재확인만_안내다() throws Exception {
        mvc.perform(get("/api/message-templates").session(session(shop, owner, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(
                        jsonPath(
                                "$.items[*].purpose",
                                contains("REVISIT", "CHECK_IN", "WIN_BACK", "CONSENT_RECONFIRM")))
                .andExpect(jsonPath("$.items[*].messageKind", contains("AD", "AD", "AD", "INFO")))
                .andExpect(jsonPath("$.items[3].name").value("동의 재확인 (철회 방법 안내형)"))
                .andExpect(jsonPath("$.items[0].body").value(containsString("{고객명}")));
    }

    @Test
    void 철회_방법_안내형은_전송자_동의_일자_철회_방법을_담는다() {
        String body =
                jdbc.queryForObject(
                        "SELECT body FROM message_template WHERE shop_id = ? AND purpose = 'CONSENT_RECONFIRM'",
                        String.class,
                        shop);
        assertThat(body).contains("{매장명}", "{동의일}", "[수신거부]");
    }

    @Test
    void 페이지로_자르고_다른_매장_템플릿은_보이지_않는다() throws Exception {
        signup.signUp(
                "template-other-" + System.nanoTime() + "@example.com",
                "password1",
                "다른 매장",
                "원장",
                "1234");

        mvc.perform(
                        get("/api/message-templates")
                                .param("size", "3")
                                .param("page", "1")
                                .session(session(shop, owner, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void PIN_인증이_없으면_pin_required_size가_범위_밖이면_invalid() throws Exception {
        mvc.perform(get("/api/message-templates").session(session(shop, owner, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        mvc.perform(
                        get("/api/message-templates")
                                .param("size", "101")
                                .session(session(shop, owner, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }
}
