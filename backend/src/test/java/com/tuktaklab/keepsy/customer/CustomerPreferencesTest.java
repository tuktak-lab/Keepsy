package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 취향 태그 · 자유 메모 (Keepsy #42 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPreferencesTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 태그와_메모를_통째로_덮어쓰고_대소문자만_다른_태그는_하나만_남긴다() throws Exception {
        save(shop, customer, tags("NAIL", "누드톤", "LASH", "C컬"), "\"조용한 시술 선호\"")
                .andExpect(status().isNoContent());
        save(shop, customer, tags("NAIL", " 누드톤 ", "NAIL", "Short", "NAIL", "short"), "\"  \"")
                .andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForList(
                                "SELECT CONCAT(category, ':', tag) FROM preference_tag"
                                        + " WHERE customer_id = ? ORDER BY id",
                                String.class,
                                customer))
                .containsExactly("NAIL:누드톤", "NAIL:Short");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT preference_memo FROM customer WHERE id = ?",
                                String.class,
                                customer))
                .isNull();
    }

    @Test
    void 매장_태그_목록은_같은_매장_다른_고객의_태그를_카테고리별로_중복_없이_준다() throws Exception {
        long other = data.customer(shop, false, null);
        save(shop, customer, tags("NAIL", "누드톤", "NAIL", "글리터", "LASH", "C컬"), "null");
        save(shop, other, tags("NAIL", "누드톤", "NAIL", "프렌치"), "null");
        long otherShop = data.shop();
        long stranger = data.customer(otherShop, false, null);
        save(otherShop, stranger, tags("NAIL", "다른 매장 태그"), "null")
                .andExpect(status().isNoContent());

        tagsOf(shop, "NAIL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(contains("글리터", "누드톤", "프렌치")));
        tagsOf(shop, "WAXING").andExpect(jsonPath("$.tags", empty()));
    }

    @Test
    void 형식이_틀리면_invalid() throws Exception {
        for (String body :
                new String[] {
                    "{\"tags\":[{\"category\":\"HAIR\",\"tag\":\"x\"}]}",
                    "{\"tags\":[{\"category\":\"NAIL\",\"tag\":\" \"}]}",
                    "{\"tags\":[{\"category\":\"NAIL\",\"tag\":\"" + "가".repeat(51) + "\"}]}",
                    "{\"memo\":\"태그 목록 없음\"}"
                }) {
            request(shop, customer, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        tagsOf(shop, "HAIR").andExpect(status().isBadRequest());
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        save(data.shop(), customer, tags("NAIL", "누드톤"), "null").andExpect(status().isNotFound());

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        save(shop, customer, tags("NAIL", "누드톤"), "null")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    /** 카테고리, 태그 쌍 */
    private static String tags(String... pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"category\":\"")
                    .append(pairs[i])
                    .append("\",\"tag\":\"")
                    .append(pairs[i + 1])
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    private ResultActions save(long shopId, long customerId, String tags, String memo)
            throws Exception {
        return request(shopId, customerId, "{\"tags\":" + tags + ",\"memo\":" + memo + "}");
    }

    private ResultActions request(long shopId, long customerId, String body) throws Exception {
        return mvc.perform(
                put("/api/customers/" + customerId + "/preferences")
                        .session(session(shopId))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions tagsOf(long shopId, String category) throws Exception {
        return mvc.perform(
                get("/api/preference-tags").session(session(shopId)).param("category", category));
    }

    private MockHttpSession session(long shopId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return session;
    }
}
