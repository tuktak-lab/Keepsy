package com.tuktaklab.keepsy.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.common.time.TimeConfig;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 통합 검색 (Keepsy #83 완료 조건 · 검증 방법, CONTRACTS 6장). 실제 MySQL에서 돌고 테스트가 끝나면 되돌린다 (MockMvc도 같은
 * 트랜잭션). 모의 이름 · 가짜 번호만 쓴다.
 *
 * <pre>
 * 김민지 010-0000-1234, 박민지 010-0000-5678, 이서연 010-0000-9999, 이름에 % · _가 든 고객 010-0000-1111
 * 삭제된 고객(이름 김민지), 다른 매장의 김민지 010-0000-1234
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerSearchTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private long shop;
    private long staff;
    private long minji;
    private long parkMinji;
    private long wildcard;

    @BeforeEach
    void seed() {
        TestShopData data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        minji = customer(shop, "김민지", "010-0000-1234", "ACTIVE");
        parkMinji = customer(shop, "박민지", "010-0000-5678", "ACTIVE");
        customer(shop, "이서연", "010-0000-9999", "ACTIVE");
        wildcard = customer(shop, "모의%_고객", "010-0000-1111", "ACTIVE");
        customer(shop, "김민지", null, "DELETED");
        customer(data.shop(), "김민지", "010-0000-1234", "ACTIVE");
    }

    @Test
    void 이름_포함은_최근_등록순이고_번호는_마스킹() throws Exception {
        search("민지", false)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[*].customerId", contains((int) parkMinji, (int) minji)))
                .andExpect(jsonPath("$.items[0].name").value("박민지"))
                .andExpect(jsonPath("$.items[0].phone").value("010-****-5678"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 숫자_4자리는_휴대폰_뒷자리_일치이고_다른_매장_고객은_빠진다() throws Exception {
        search(" 1234 ", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].customerId", contains((int) minji)))
                .andExpect(jsonPath("$.items[0].phone").value("010-****-1234"));
    }

    @Test
    void LIKE_특수_문자는_글자_그대로_찾는다() throws Exception {
        search("%", false).andExpect(jsonPath("$.items[*].customerId", contains((int) wildcard)));
        search("_", false).andExpect(jsonPath("$.items[*].customerId", contains((int) wildcard)));
    }

    @Test
    void 페이지로_자른다() throws Exception {
        mvc.perform(
                        get("/api/search/customers")
                                .param("q", "민지")
                                .param("size", "1")
                                .param("page", "1")
                                .session(session(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].customerId", contains((int) minji)))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 검색어가_비었거나_size가_범위_밖이면_invalid() throws Exception {
        MockHttpSession session = session(false);
        mvc.perform(get("/api/search/customers").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        search("  ", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        get("/api/search/customers")
                                .param("q", "민지")
                                .param("size", "101")
                                .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 고객_조회가_PIN으로_잠기면_pin_required이고_PIN_인증_뒤_보인다() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'CUSTOMER_VIEW' WHERE id = ?", shop);

        search("민지", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        search("민지", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /** 접근 로그(NFR-06) 중 고객 조회 행의 대상 고객 ID */
    private List<Long> loggedCustomers() {
        return jdbc.queryForList(
                "SELECT target_id FROM access_log WHERE shop_id = ? AND target_type = 'CUSTOMER'"
                        + " AND action = 'VIEW' ORDER BY target_id",
                Long.class,
                shop);
    }

    @Test
    void 돌려준_페이지의_고객만_조회로_남기고_검색어는_남기지_않는다() throws Exception {
        mvc.perform(
                        get("/api/search/customers")
                                .param("q", "민지")
                                .param("size", "1")
                                .session(session(false)))
                .andExpect(status().isOk());
        // 조건에 맞는 2명 중 돌려준 1명(최근 등록순 첫 번째)만. 다른 매장의 김민지는 없다
        assertThat(loggedCustomers()).containsExactly(parkMinji);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT staff_id FROM access_log WHERE shop_id = ? AND target_id = ?",
                                Long.class,
                                shop,
                                parkMinji))
                .isEqualTo(staff); // 공용이면 현재 근무자
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM access_log WHERE shop_id = ? AND target_type <> 'CUSTOMER'",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void 결과가_없거나_권한이_없거나_검색어가_틀리면_남기지_않는다() throws Exception {
        search("없는이름", false).andExpect(status().isOk());
        search("  ", false).andExpect(status().isBadRequest());
        jdbc.update("UPDATE shop SET pin_locked_features = 'CUSTOMER_VIEW' WHERE id = ?", shop);
        search("민지", false).andExpect(status().isForbidden());

        assertThat(loggedCustomers()).isEmpty();
    }

    @Test
    void 고객_조회가_잠겨_있으면_PIN_인증_스탭으로_남는다() throws Exception {
        jdbc.update("UPDATE shop SET pin_locked_features = 'CUSTOMER_VIEW' WHERE id = ?", shop);
        search("민지", true).andExpect(status().isOk());

        assertThat(loggedCustomers()).containsExactlyInAnyOrder(minji, parkMinji);
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT staff_id FROM access_log WHERE shop_id = ?",
                                Long.class,
                                shop))
                .containsExactly(staff);
    }

    private ResultActions search(String q, boolean pin) throws Exception {
        return mvc.perform(get("/api/search/customers").param("q", q).session(session(pin)));
    }

    private MockHttpSession session(boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now(TimeConfig.ZONE).plusMinutes(5));
        }
        return session;
    }

    private long customer(long shopId, String name, String phone, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("shop_id", shopId);
        row.put("name", name);
        row.put("phone", phone);
        row.put("status", status);
        row.put("registered_at", LocalDateTime.now(TimeConfig.ZONE));
        return new SimpleJdbcInsert(jdbc)
                .withTableName("customer")
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(row)
                .longValue();
    }
}
