package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 동의 받기 · 철회 (Keepsy #48 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CONSENT}는 공용 고정이라 PIN 없이
 * 현재 근무자 세션으로만 부른다 (명세서 2장, E8).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentChangeTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PhotoStorage storage;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;
    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() throws IOException {
        for (String key : keys) {
            storage.delete(key);
        }
        data.cleanUp();
    }

    @Test
    void 동의를_받으면_현재_근무자_동의_방법으로_이력을_쌓고_접근_로그() throws Exception {
        String body =
                change(shop, "{\"item\":\"PHOTO\",\"agreed\":true,\"method\":\"TABLET\"}")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(
                        jdbc.queryForMap(
                                "SELECT item, agreed, staff_id, method, withdrawal_path FROM consent WHERE id = ?",
                                id))
                .containsEntry("item", "PHOTO")
                .containsEntry("agreed", true)
                .containsEntry("staff_id", staff)
                .containsEntry("method", "TABLET")
                .containsEntry("withdrawal_path", null);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT staff_id, action FROM access_log WHERE shop_id = ?", shop))
                .isEqualTo(Map.of("staff_id", staff, "action", "UPDATE"));
    }

    @Test
    void 마케팅_철회는_기본_직접_철회_스탭이_처리한_수신_거부는_UNSUBSCRIBE() throws Exception {
        change(shop, "{\"item\":\"MARKETING\",\"agreed\":false,\"method\":\"VERBAL\"}")
                .andExpect(status().isCreated());
        change(
                        shop,
                        "{\"item\":\"MARKETING\",\"agreed\":false,\"method\":\"VERBAL\","
                                + "\"withdrawalPath\":\"UNSUBSCRIBE\"}")
                .andExpect(status().isCreated());

        assertThat(
                        jdbc.queryForList(
                                "SELECT withdrawal_path FROM consent WHERE customer_id = ?"
                                        + " AND item = 'MARKETING' ORDER BY id",
                                String.class,
                                customer))
                .containsExactly("DIRECT", "UNSUBSCRIBE");
    }

    @Test
    void 건강_정보_철회는_건강_주의사항만_비운다() throws Exception {
        jdbc.update(
                "UPDATE customer SET service_caution = '젤 리무버 주의', health_caution = '아세톤 알레르기'"
                        + " WHERE id = ?",
                customer);

        change(shop, "{\"item\":\"HEALTH\",\"agreed\":false,\"method\":\"PAPER\"}")
                .andExpect(status().isCreated());

        assertThat(
                        jdbc.queryForMap(
                                "SELECT service_caution, health_caution FROM customer WHERE id = ?",
                                customer))
                .containsEntry("service_caution", "젤 리무버 주의")
                .containsEntry("health_caution", null);
    }

    @Test
    void 사진_동의_철회는_그_고객의_사진_행과_파일을_지우고_다른_고객_사진은_남긴다() throws Exception {
        String mine = photo(customer);
        long other = data.customer(shop, false, null);
        String others = photo(other);

        change(shop, "{\"item\":\"PHOTO\",\"agreed\":false,\"method\":\"VERBAL\"}")
                .andExpect(status().isCreated());

        assertThat(photoCount(customer)).isZero();
        assertThat(fileExists(mine)).isFalse();
        assertThat(photoCount(other)).isOne();
        assertThat(fileExists(others)).isTrue();
    }

    @Test
    void 개인정보_철회_가져오기_방법_마케팅_아닌_수신_거부_동의에_철회_경로는_invalid이고_쌓지_않는다() throws Exception {
        for (String body :
                List.of(
                        "{\"item\":\"PRIVACY\",\"agreed\":false,\"method\":\"VERBAL\"}",
                        "{\"item\":\"PHOTO\",\"agreed\":true,\"method\":\"IMPORT\"}",
                        "{\"item\":\"PHOTO\",\"agreed\":false,\"method\":\"VERBAL\","
                                + "\"withdrawalPath\":\"UNSUBSCRIBE\"}",
                        "{\"item\":\"MARKETING\",\"agreed\":true,\"method\":\"VERBAL\","
                                + "\"withdrawalPath\":\"DIRECT\"}",
                        "{\"item\":\"PHOTO\",\"method\":\"VERBAL\"}",
                        "{\"item\":\"SMS\",\"agreed\":true,\"method\":\"VERBAL\"}")) {
            change(shop, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        change(shop, "{\"item\":\"PRIVACY\",\"agreed\":false,\"method\":\"VERBAL\"}")
                .andExpect(jsonPath("$.error.details[0].field").value("item"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM consent WHERE customer_id = ?",
                                Integer.class,
                                customer))
                .isZero();
    }

    @Test
    void 다른_매장_삭제된_고객은_not_found() throws Exception {
        String body = "{\"item\":\"PHOTO\",\"agreed\":true,\"method\":\"TABLET\"}";
        change(data.shop(), body).andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        change(shop, body).andExpect(status().isNotFound());
    }

    private String photo(long customerId) throws IOException {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customerId,
                LocalDate.of(2026, 11, 1));
        long visit =
                jdbc.queryForObject(
                        "SELECT id FROM visit WHERE customer_id = ?", Long.class, customerId);
        String key = storage.save(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        keys.add(key);
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, ?, ?)",
                shop,
                visit,
                key,
                LocalDateTime.of(2026, 11, 1, 12, 0));
        return key;
    }

    private boolean fileExists(String key) throws IOException {
        try (InputStream in = storage.open(key)) {
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private int photoCount(long customerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM photo p JOIN visit v ON v.id = p.visit_id WHERE v.customer_id = ?",
                Integer.class,
                customerId);
    }

    private ResultActions change(long shopId, String body) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return mvc.perform(
                post("/api/customers/" + customer + "/consents")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
