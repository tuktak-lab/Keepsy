package com.tuktaklab.keepsy.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.account.StaffCommandService.NewStaff;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
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

/** 스탭 PIN 재설정 (Keepsy #22, CONTRACTS 6장 ACC). 실제 MySQL에서 돈다. 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffPinResetTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired StaffCommandService staffService;
    @Autowired PasswordEncoder passwordEncoder;

    private TestShopData data;
    private long shop;
    private long owner;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        owner = data.staff(shop, "ACTIVE", true);
        staff = staffService.create(shop, new NewStaff("모의 스탭", "1111", false, List.of()));
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private ResultActions reset(long staffId, String pin, boolean ownerPin) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, owner);
        if (ownerPin) {
            shopSession.setPin(session, owner, LocalDateTime.now().plusMinutes(5));
        }
        return mvc.perform(
                put("/api/staff/{id}/pin", staffId)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"" + pin + "\"}"));
    }

    private String pinHash(long staffId) {
        return jdbc.queryForObject(
                "SELECT pin_hash FROM staff WHERE id = ?", String.class, staffId);
    }

    @Test
    void 원장_PIN으로_재설정하면_204이고_새_PIN의_해시로_바뀐다() throws Exception {
        reset(staff, "2222", true).andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches("2222", pinHash(staff))).isTrue();
        assertThat(passwordEncoder.matches("1111", pinHash(staff))).isFalse();
    }

    @Test
    void 자기_지금_PIN과_같은_것은_허용한다() throws Exception {
        reset(staff, "1111", true).andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches("1111", pinHash(staff))).isTrue();
    }

    @Test
    void 다른_스탭의_PIN과_같으면_비활성이어도_duplicate_409이고_바뀌지_않는다() throws Exception {
        long other = staffService.create(shop, new NewStaff("다른 스탭", "3333", false, List.of()));
        jdbc.update("UPDATE staff SET status = 'INACTIVE' WHERE id = ?", other);
        String before = pinHash(staff);

        reset(staff, "3333", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate"));
        assertThat(pinHash(staff)).isEqualTo(before);
    }

    @Test
    void 원장_스탭이면_invalid_다른_매장_스탭이면_not_found_형식이_틀리면_invalid() throws Exception {
        reset(owner, "4444", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        long otherShopStaff = data.staff(data.shop(), "ACTIVE", false);
        reset(otherShopStaff, "4444", true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        reset(staff, "12a4", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 원장_PIN_인증이_없으면_owner_pin_required_403() throws Exception {
        reset(staff, "2222", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"));
    }
}
