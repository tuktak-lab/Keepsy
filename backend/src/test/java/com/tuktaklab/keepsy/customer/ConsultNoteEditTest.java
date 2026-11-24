package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * 상담 메모 수정 · 삭제 (Keepsy #44 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. 매장의 기능 잠금은 기본값(명세서 2장,
 * {@code CUSTOMER_EDIT}은 PIN)에서 시작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsultNoteEditTest {

    private static final LocalDateTime WRITTEN = LocalDateTime.of(2026, 11, 1, 10, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long writer;
    private long worker;
    private long pinStaff;
    private long customer;
    private long note;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        writer = data.staff(shop, "ACTIVE", false);
        worker = data.staff(shop, "ACTIVE", false);
        pinStaff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
        jdbc.update(
                "INSERT INTO consult_note (shop_id, customer_id, staff_id, content, written_at)"
                        + " VALUES (?, ?, ?, '처음 내용', ?)",
                shop,
                customer,
                writer,
                WRITTEN);
        note =
                jdbc.queryForObject(
                        "SELECT id FROM consult_note WHERE customer_id = ?", Long.class, customer);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void PIN_인증이_없으면_수정_삭제_모두_pin_required이고_그대로다() throws Exception {
        edit(shop, note, "바꾼 내용", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        remove(shop, note, false).andExpect(status().isForbidden());

        assertThat(content()).isEqualTo("처음 내용");
        assertThat(logs()).isEmpty();
    }

    @Test
    void PIN_인증_뒤_수정하면_내용만_바뀌고_PIN_인증_스탭으로_UPDATE() throws Exception {
        edit(shop, note, "바꾼 내용", true).andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT content, staff_id, written_at FROM consult_note WHERE id = ?",
                                note))
                .containsEntry("content", "바꾼 내용")
                .containsEntry("staff_id", writer)
                .containsEntry("written_at", WRITTEN);
        assertThat(logs()).containsExactly(Map.of("staff_id", pinStaff, "action", "UPDATE"));
    }

    @Test
    void PIN_인증_뒤_삭제하면_지우고_DELETE() throws Exception {
        remove(shop, note, true).andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM consult_note WHERE id = ?",
                                Integer.class,
                                note))
                .isZero();
        assertThat(logs()).containsExactly(Map.of("staff_id", pinStaff, "action", "DELETE"));
    }

    @Test
    void 공용으로_바꾸면_PIN_없이_바로_통과하고_현재_근무자로_남긴다() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = '' WHERE id = ?", shop);

        edit(shop, note, "바꾼 내용", false).andExpect(status().isNoContent());
        assertThat(logs()).containsExactly(Map.of("staff_id", worker, "action", "UPDATE"));
    }

    @Test
    void 빈_내용은_invalid_다른_매장_없는_메모는_not_found() throws Exception {
        edit(shop, note, " ", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        long otherShop = data.shop();
        edit(otherShop, note, "바꾼 내용", true).andExpect(status().isNotFound());
        remove(otherShop, note, true).andExpect(status().isNotFound());
        remove(shop, note + 1_000_000, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        assertThat(content()).isEqualTo("처음 내용");
    }

    private String content() {
        return jdbc.queryForObject(
                "SELECT content FROM consult_note WHERE id = ?", String.class, note);
    }

    private List<Map<String, Object>> logs() {
        return jdbc.queryForList(
                "SELECT staff_id, action FROM access_log WHERE shop_id = ? AND target_id = ?",
                shop,
                customer);
    }

    private ResultActions edit(long shopId, long noteId, String content, boolean pin)
            throws Exception {
        return mvc.perform(
                patch("/api/consult-notes/" + noteId)
                        .session(session(shopId, pin))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"));
    }

    private ResultActions remove(long shopId, long noteId, boolean pin) throws Exception {
        return mvc.perform(
                delete("/api/consult-notes/" + noteId).session(session(shopId, pin)).with(csrf()));
    }

    /** 현재 근무자는 worker, {@code pin}이면 PIN 인증은 pinStaff */
    private MockHttpSession session(long shopId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }
}
