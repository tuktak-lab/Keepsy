package com.tuktaklab.keepsy.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 없는 검색어 저장 (Keepsy #84 완료 조건 · 검증 방법, 결정 F28, CONTRACTS 6장). 실제 MySQL에서 돌고 테스트가 끝나면 되돌린다. 모의 이름
 * · 가짜 번호만 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchMissTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private long shop;
    private long other;
    private long staff;

    @BeforeEach
    void seed() {
        TestShopData data = new TestShopData(jdbc);
        shop = data.shop();
        other = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        customer(shop, "김민지", "010-0000-1234", "ACTIVE");
        customer(shop, "이서연", null, "DELETED");
        customer(other, "박모의", "010-0000-5678", "ACTIVE");
    }

    @Test
    void 고객_결과도_없으면_앞뒤_공백을_빼고_이_매장으로_저장한다() throws Exception {
        miss("  예약 바꾸기  ").andExpect(status().isNoContent());

        assertThat(keywords(shop)).containsExactly("예약 바꾸기");
        assertThat(keywords(other)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT searched_at FROM search_miss_log WHERE shop_id = ?",
                                LocalDateTime.class,
                                shop))
                .isNotNull();
    }

    @Test
    void 고객을_찾은_검색어는_저장하지_않는다() throws Exception {
        miss("민지").andExpect(status().isNoContent());
        miss("1234").andExpect(status().isNoContent());

        assertThat(keywords(shop)).isEmpty();
    }

    @Test
    void 고객_조회가_PIN으로_잠겨_있어도_고객을_찾은_검색어는_저장하지_않는다() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'CUSTOMER_VIEW' WHERE id = ?", shop);

        miss("민지").andExpect(status().isNoContent());

        assertThat(keywords(shop)).isEmpty();
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_고객_결과가_아니다() throws Exception {
        miss("박모의").andExpect(status().isNoContent());
        miss("5678").andExpect(status().isNoContent());
        miss("이서연").andExpect(status().isNoContent());

        assertThat(keywords(shop)).containsExactlyInAnyOrder("박모의", "5678", "이서연");
    }

    @Test
    void 검색어가_비었거나_100자를_넘으면_invalid() throws Exception {
        miss("  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        miss("가".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        post("/api/search/misses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                                .session(session())
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        miss("가".repeat(100)).andExpect(status().isNoContent());

        assertThat(keywords(shop)).containsExactly("가".repeat(100));
    }

    private ResultActions miss(String keyword) throws Exception {
        return mvc.perform(
                post("/api/search/misses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + keyword + "\"}")
                        .session(session())
                        .with(csrf()));
    }

    private List<String> keywords(long shopId) {
        return jdbc.queryForList(
                "SELECT keyword FROM search_miss_log WHERE shop_id = ? ORDER BY id",
                String.class,
                shopId);
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        return session;
    }

    private void customer(long shopId, String name, String phone, String status) {
        jdbc.update(
                "INSERT INTO customer (shop_id, name, phone, status, registered_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                shopId,
                name,
                phone,
                status,
                LocalDateTime.now(TimeConfig.ZONE));
    }
}
