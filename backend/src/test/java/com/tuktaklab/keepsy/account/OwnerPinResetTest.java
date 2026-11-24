package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.StaffCommandService.NewStaff;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PUT /api/owner-pin 원장 PIN 재설정 (Keepsy #19, CONTRACTS 6장 ACC, FR-ACC-09, F11 · F14). 실제 MySQL에서
 * 돈다. 모의 데이터만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerPinResetTest {

    private static final String PASSWORD = "mock-password-1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StaffCommandService staffService;

    private TestShopData data;
    private long shop;
    private long owner;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        jdbc.update(
                "UPDATE shop SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode(PASSWORD),
                shop);
        owner = data.staff(shop, "ACTIVE", true);
        // 가입으로 만든 원장(Staff.owner)처럼 "잠긴 기능 사용 허용"을 켠다. TestShopData는 원장도 DB 기본값(false)으로 넣어
        // 잠긴 기능 PIN 인증(FR-ACC-08)에서 원장이 비교 대상에서 빠진다
        jdbc.update("UPDATE staff SET locked_feature_allowed = TRUE WHERE id = ?", owner);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** 매장 세션만 (근무자 · PIN 인증 없음) */
    private MockHttpSession shopOnly() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        return session;
    }

    private ResultActions reset(MockHttpSession session, String password, String pin)
            throws Exception {
        return mvc.perform(
                put("/api/owner-pin")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\",\"pin\":\"%s\"}".formatted(password, pin)));
    }

    private String ownerPinHash() {
        return jdbc.queryForObject("SELECT pin_hash FROM staff WHERE id = ?", String.class, owner);
    }

    private int failCount() {
        return jdbc.queryForObject(
                "SELECT login_fail_count FROM shop WHERE id = ?", Integer.class, shop);
    }

    @Test
    void 맞는_비밀번호면_근무자_없이도_204이고_새_PIN으로_원장_PIN_기능을_쓸_수_있다() throws Exception {
        MockHttpSession session = shopOnly();
        reset(session, PASSWORD, "9876").andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches("9876", ownerPinHash())).isTrue();
        // 새 PIN으로 근무자 전환 → 잠긴 기능 PIN 인증 → 원장 PIN 기능 통과
        mvc.perform(
                        put("/api/session/worker")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"pin\":\"9876\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/session/pin")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"pin\":\"9876\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/staff").param("includeInactive", "true").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void 틀린_비밀번호면_password_mismatch이고_세션과_PIN은_그대로_실패_횟수가_오르고_맞으면_0() throws Exception {
        MockHttpSession session = shopOnly();
        String before = ownerPinHash();

        reset(session, "wrong-password", "9876")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("password_mismatch"));
        assertThat(ownerPinHash()).isEqualTo(before);
        assertThat(failCount()).isOne();

        // 세션은 유지된다 (F11): 같은 세션으로 다시 시도할 수 있다
        reset(session, PASSWORD, "9876").andExpect(status().isNoContent());
        assertThat(failCount()).isZero();
    }

    @Test
    void 연속_5회_틀리면_login_locked이고_잠금_중에는_맞는_비밀번호도_거부한다() throws Exception {
        MockHttpSession session = shopOnly();
        String before = ownerPinHash();
        for (int i = 0; i < 4; i++) {
            reset(session, "wrong-password", "9876")
                    .andExpect(jsonPath("$.error.code").value("password_mismatch"));
        }
        reset(session, "wrong-password", "9876")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("login_locked"));

        reset(session, PASSWORD, "9876")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("login_locked"));
        assertThat(ownerPinHash()).isEqualTo(before);
    }

    @Test
    void 다른_스탭과_같은_PIN이면_duplicate이고_PIN은_그대로_형식이_틀리면_invalid이고_횟수에_넣지_않는다() throws Exception {
        staffService.create(shop, new NewStaff("모의 스탭", "1111", false, List.of()));
        MockHttpSession session = shopOnly();
        String before = ownerPinHash();

        reset(session, PASSWORD, "1111")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
        assertThat(ownerPinHash()).isEqualTo(before);

        reset(session, "wrong-password", "12")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        assertThat(failCount()).isZero();
    }

    @Test
    void 로그인하지_않았으면_unauthorized() throws Exception {
        mvc.perform(
                        put("/api/owner-pin")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"x\",\"pin\":\"9876\"}"))
                .andExpect(status().isUnauthorized());
    }
}
