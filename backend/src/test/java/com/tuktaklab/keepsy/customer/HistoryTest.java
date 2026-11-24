package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 시술 히스토리북 (Keepsy #45 완료 조건 · 검증 방법). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CUSTOMER_VIEW}는 기본 공용이다.
 *
 * <pre>
 * 11-01 방문: 젤 1건
 * 10-01 방문: 젤(스탭 a) + 속눈썹(스탭 b) 2건, 방문 메모 1개, 사진 2장, 결제 50,000원
 * 09-01 가져온 방문: NAIL, 메뉴 · 스탭 없음
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryTest {

    private static final Pattern SIGNED =
            Pattern.compile("/api/photos/(\\d+)/file\\?expires=(\\d+)&signature=([\\w-]+)");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired PhotoStorage storage;
    @Autowired Clock clock;

    private TestShopData data;
    private long shop;
    private long a;
    private long b;
    private long customer;
    private long nov;
    private long oct;
    private long imported;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        a = data.staff(shop, "ACTIVE", true);
        b = data.staff(shop, "ACTIVE", false);
        long gel = data.menu(shop, 60);
        jdbc.update(
                "INSERT INTO service_menu (shop_id, category, name, price, duration_minutes,"
                        + " cycle_min_days, cycle_max_days) VALUES (?, 'LASH', '속눈썹 연장', 40000, 60, 30, 60)",
                shop);
        long lash =
                jdbc.queryForObject(
                        "SELECT id FROM service_menu WHERE shop_id = ? AND category = 'LASH'",
                        Long.class,
                        shop);
        customer = data.customer(shop, false, null);
        photoConsent(true);

        nov = visit(LocalDate.of(2026, 11, 1), "NORMAL");
        service(nov, "NAIL", gel, a);
        oct = visit(LocalDate.of(2026, 10, 1), "NORMAL");
        service(oct, "NAIL", gel, a);
        service(oct, "LASH", lash, b);
        jdbc.update(
                "INSERT INTO visit_note (shop_id, visit_id, staff_id, content, created_at)"
                        + " VALUES (?, ?, ?, '손톱이 얇아 젤 두께 주의', ?)",
                shop,
                oct,
                a,
                LocalDateTime.of(2026, 10, 1, 15, 0));
        for (int i = 0; i < 2; i++) {
            jdbc.update(
                    "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at) VALUES (?, ?, ?, ?)",
                    shop,
                    oct,
                    "모의-" + i,
                    LocalDateTime.of(2026, 10, 1, 15, i));
        }
        jdbc.update(
                "INSERT INTO reservation (shop_id, customer_id, staff_id, start_at, end_at, status,"
                        + " channel, created_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'STORE', ?)",
                shop,
                customer,
                a,
                LocalDateTime.of(2026, 10, 1, 13, 0),
                LocalDateTime.of(2026, 10, 1, 14, 0),
                LocalDateTime.of(2026, 9, 30, 10, 0));
        jdbc.update(
                "INSERT INTO payment (shop_id, customer_id, reservation_id, visit_id, amount, method,"
                        + " paid_at) SELECT ?, ?, id, ?, 50000, 'CARD', ? FROM reservation WHERE customer_id = ?",
                shop,
                customer,
                oct,
                LocalDateTime.of(2026, 10, 1, 14, 0),
                customer);
        imported = visit(LocalDate.of(2026, 9, 1), "IMPORT");
        service(imported, "NAIL", null, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 방문_최신순이고_시술_건수_시술별_담당_스탭_메모_결제_금액은_없다() throws Exception {
        String body =
                history(customer, shop, "")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(3))
                        .andExpect(jsonPath("$.items[1].visitDate").value("2026-10-01"))
                        .andExpect(jsonPath("$.items[1].serviceCount").value(2))
                        .andExpect(jsonPath("$.items[1].services[0].category").value("NAIL"))
                        .andExpect(jsonPath("$.items[1].services[0].staff.id").value(a))
                        .andExpect(jsonPath("$.items[1].services[1].menuName").value("속눈썹 연장"))
                        .andExpect(jsonPath("$.items[1].services[1].staff.id").value(b))
                        .andExpect(jsonPath("$.items[1].notes[0].content").value("손톱이 얇아 젤 두께 주의"))
                        .andExpect(jsonPath("$.items[1].notes[0].staff.id").value(a))
                        .andExpect(
                                jsonPath("$.items[1].notes[0].createdAt")
                                        .value("2026-10-01T15:00:00+09:00"))
                        // 가져온 방문은 메뉴 미상 · 스탭 없음 (A13)
                        .andExpect(jsonPath("$.items[2].services[0].menuName").value(nullValue()))
                        .andExpect(jsonPath("$.items[2].services[0].staff").value(nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(JsonPath.<List<Number>>read(body, "$.items[*].visitId"))
                .extracting(Number::longValue)
                .containsExactly(nov, oct, imported);
        assertThat(body).doesNotContain("amount").doesNotContain("50000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM access_log WHERE shop_id = ? AND action = 'VIEW'"
                                        + " AND target_id = ?",
                                Integer.class,
                                shop,
                                customer))
                .isOne();
    }

    @Test
    void 사진은_만료_시각이_있는_세션_매장_서명_URL이고_사진_동의가_없으면_빈_배열() throws Exception {
        String body = history(customer, shop, "").andReturn().getResponse().getContentAsString();
        List<String> urls = JsonPath.read(body, "$.items[1].photos[*].url");
        assertThat(urls).hasSize(2);
        for (String url : urls) {
            Matcher m = SIGNED.matcher(url);
            assertThat(m.matches()).isTrue();
            long expires = Long.parseLong(m.group(2));
            assertThat(expires).isGreaterThan(clock.instant().getEpochSecond());
            assertThat(
                            storage.verify(
                                    Long.parseLong(m.group(1)),
                                    shop,
                                    expires,
                                    m.group(3),
                                    clock.instant()))
                    .isTrue();
        }

        photoConsent(false);
        history(customer, shop, "").andExpect(jsonPath("$.items[1].photos.length()").value(0));
    }

    @Test
    void 방문_단위로_페이지를_나누고_다른_매장_삭제된_고객은_not_found() throws Exception {
        history(customer, shop, "?page=1&size=1")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].visitId").value(oct));
        history(customer, shop, "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        history(customer, data.shop(), "").andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        history(customer, shop, "").andExpect(status().isNotFound());
    }

    private long visit(LocalDate date, String source) {
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, ?)",
                shop,
                customer,
                date,
                source);
        return jdbc.queryForObject(
                "SELECT id FROM visit WHERE customer_id = ? AND visit_date = ?",
                Long.class,
                customer,
                date);
    }

    private void service(long visitId, String category, Long menuId, Long staffId) {
        jdbc.update(
                "INSERT INTO visit_service (shop_id, visit_id, service_menu_id, category, staff_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                shop,
                visitId,
                menuId,
                category,
                staffId);
    }

    private void photoConsent(boolean agreed) {
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id,"
                        + " method, withdrawal_path) VALUES (?, ?, 'PHOTO', ?, ?, ?, 'TABLET', ?)",
                shop,
                customer,
                agreed,
                LocalDateTime.now(),
                a,
                agreed ? null : "DIRECT");
    }

    private ResultActions history(long customerId, long shopId, String query) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, a);
        return mvc.perform(get("/api/customers/{id}/history" + query, customerId).session(session));
    }
}
