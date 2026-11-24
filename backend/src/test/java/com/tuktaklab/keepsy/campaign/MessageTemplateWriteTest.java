package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 템플릿 작성 · 수정 · 삭제 (Keepsy #86 완료 조건, C10, CONTRACTS 6장). 실제 MySQL에서 돈다. */
@SpringBootTest
@AutoConfigureMockMvc
class MessageTemplateWriteTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired MessageTemplateService service;

    private TestShopData data;
    private long shop;
    private long staff;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** CAMPAIGN_SEND는 기본 PIN 잠금 */
    private ResultActions send(
            MockHttpServletRequestBuilder req, long shopId, long worker, boolean pin)
            throws Exception {
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
        return mvc.perform(req.session(session).with(csrf()));
    }

    private ResultActions write(
            MockHttpServletRequestBuilder req, String name, String body, String purpose)
            throws Exception {
        String json =
                """
                {"name": "%s", "body": "%s", "purpose": "%s"}\
                """
                        .formatted(name, body, purpose);
        return send(req.contentType(MediaType.APPLICATION_JSON).content(json), shop, staff, true);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT name, body, purpose FROM message_template WHERE id = ?", id);
    }

    @Test
    void 작성하면_201과_id_수정하면_204이고_값이_바뀐다() throws Exception {
        write(post("/api/message-templates"), "가을 안내", "{고객명}님, {매장명}입니다.", "REVISIT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM message_template WHERE shop_id = ?", Long.class, shop);

        write(
                        patch("/api/message-templates/{id}", id),
                        "동의 확인",
                        "{고객명}님 {동의일} 동의",
                        "CONSENT_RECONFIRM")
                .andExpect(status().isNoContent());

        assertThat(row(id))
                .containsEntry("name", "동의 확인")
                .containsEntry("body", "{고객명}님 {동의일} 동의")
                .containsEntry("purpose", "CONSENT_RECONFIRM");
    }

    @Test
    void 용도에_맞지_않는_변수나_없는_변수는_invalid() throws Exception {
        for (String[] bad :
                new String[][] {
                    {"{동의일} 확인", "REVISIT"},
                    {"{최근시술} 후 {경과일}일", "CONSENT_RECONFIRM"},
                    {"{고객 명}님", "ETC"}
                }) {
            write(post("/api/message-templates"), "잘못", bad[0], bad[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"))
                    .andExpect(jsonPath("$.error.details[0].field").value("body"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM message_template WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 본문이_2000바이트를_넘으면_invalid() throws Exception {
        write(post("/api/message-templates"), "긴 글", "가".repeat(1000), "ETC")
                .andExpect(status().isCreated());
        write(post("/api/message-templates"), "너무 긴 글", "가".repeat(1001), "ETC")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 삭제는_204_캠페인이_쓰는_템플릿은_conflict_다른_매장은_not_found() throws Exception {
        long free = service.create(shop, "지울 것", "{고객명}님", TemplatePurpose.ETC);
        long used = service.create(shop, "쓰는 것", "{고객명}님", TemplatePurpose.REVISIT);
        jdbc.update(
                "INSERT INTO campaign (shop_id, name, template_id, message_kind) VALUES (?, '모의 캠페인', ?, 'AD')",
                shop,
                used);

        send(delete("/api/message-templates/{id}", free), shop, staff, true)
                .andExpect(status().isNoContent());
        send(delete("/api/message-templates/{id}", used), shop, staff, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", true);
        send(delete("/api/message-templates/{id}", used), otherShop, otherStaff, true)
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForList(
                                "SELECT id FROM message_template WHERE shop_id = ?",
                                Long.class,
                                shop))
                .containsExactly(used);
    }

    @Test
    void PIN_인증이_없으면_pin_required() throws Exception {
        send(
                        post("/api/message-templates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\": \"a\", \"body\": \"b\", \"purpose\": \"ETC\"}"),
                        shop,
                        staff,
                        false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }
}
