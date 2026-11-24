package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

/** 상담 메모 조회 · 작성 (Keepsy #43 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class ConsultNoteTest {

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
    void 작성하면_201과_ID_작성자는_현재_근무자_작성_시각은_자동() throws Exception {
        String body =
                write(shop, customer, "다음엔 짧은 길이 원함", true)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long noteId = ((Number) JsonPath.read(body, "$.noteId")).longValue();

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT customer_id, staff_id, content, written_at FROM consult_note WHERE id = ?",
                        noteId);
        assertThat(row)
                .containsEntry("customer_id", customer)
                .containsEntry("staff_id", staff)
                .containsEntry("content", "다음엔 짧은 길이 원함");
        assertThat((LocalDateTime) row.get("written_at"))
                .isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void 목록은_최신_작성순으로_페이징하고_조회를_접근_로그에_남긴다() throws Exception {
        long first = note("첫 메모", LocalDateTime.of(2026, 11, 1, 10, 0));
        long second = note("둘째 메모", LocalDateTime.of(2026, 11, 2, 10, 0));
        long third = note("셋째 메모", LocalDateTime.of(2026, 11, 3, 10, 0));

        list(shop, customer, "0", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].noteId").value(contains((int) third, (int) second)))
                .andExpect(jsonPath("$.items[0].staff.id").value(staff))
                .andExpect(jsonPath("$.items[0].staff.name").value("스탭"))
                .andExpect(jsonPath("$.items[0].writtenAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.totalElements").value(3));
        list(shop, customer, "1", "2")
                .andExpect(jsonPath("$.items[*].noteId").value(contains((int) first)));

        assertThat(
                        jdbc.queryForList(
                                "SELECT action FROM access_log WHERE shop_id = ? AND target_id = ?",
                                String.class,
                                shop,
                                customer))
                .containsExactly("VIEW", "VIEW");
    }

    @Test
    void 근무자가_없으면_worker_required() throws Exception {
        write(shop, customer, "메모", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }

    @Test
    void 빈_메모와_2000자_초과는_invalid() throws Exception {
        for (String content : new String[] {" ", "가".repeat(2001)}) {
            write(shop, customer, content, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
    }

    @Test
    void 다른_매장_고객과_삭제된_고객은_not_found() throws Exception {
        long otherShop = data.shop();
        write(otherShop, customer, "메모", true).andExpect(status().isNotFound());
        list(otherShop, customer, "0", "20").andExpect(status().isNotFound());

        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        write(shop, customer, "메모", true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        list(shop, customer, "0", "20").andExpect(status().isNotFound());
    }

    private long note(String content, LocalDateTime at) {
        jdbc.update(
                "INSERT INTO consult_note (shop_id, customer_id, staff_id, content, written_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                shop,
                customer,
                staff,
                content,
                at);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM consult_note WHERE customer_id = ?", Long.class, customer);
    }

    private ResultActions write(long shopId, long customerId, String content, boolean worker)
            throws Exception {
        return mvc.perform(
                post("/api/customers/" + customerId + "/consult-notes")
                        .session(session(shopId, worker))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"));
    }

    private ResultActions list(long shopId, long customerId, String page, String size)
            throws Exception {
        return mvc.perform(
                get("/api/customers/" + customerId + "/consult-notes")
                        .session(session(shopId, true))
                        .param("page", page)
                        .param("size", size));
    }

    private MockHttpSession session(long shopId, boolean worker) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        if (worker) {
            shopSession.setCurrentStaffId(session, staff);
        }
        return session;
    }
}
