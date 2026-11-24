package com.tuktaklab.keepsy.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.reservation.NewReservation;
import com.tuktaklab.keepsy.reservation.ReservationAppService;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
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
 * 방문 수정 {@code PATCH /api/visits/{visitId}} (Keepsy #70, FR-VIS-03 · 05, F25, CONTRACTS 6장 「방문
 * (VIS)」)
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisitEditTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService reservations;
    @Autowired VisitAppService visits;

    private TestShopData data;
    private long shop;
    private long staff;
    private long staff2;
    private long gel;
    private long care;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        staff2 = data.staff(shop, "ACTIVE", false);
        gel = data.menu(shop, 60);
        care = data.menu(shop, 30);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long reserve(Long customerId, int hour) {
        return reservations.create(
                shop,
                new NewReservation(
                        customerId,
                        staff,
                        DAY.atTime(hour, 0),
                        null,
                        null,
                        false,
                        null,
                        List.of(gel)),
                false);
    }

    private ResultActions edit(long visitId, long shopId, long worker, boolean pin, String body)
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
        return mvc.perform(
                patch("/api/visits/{id}", visitId)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String item(long menuId, long staffId, boolean cycleExcluded) {
        return "{\"serviceMenuId\":%d,\"staffId\":%d,\"cycleExcluded\":%b}"
                .formatted(menuId, staffId, cycleExcluded);
    }

    private static String body(String... items) {
        return "{\"services\":[" + String.join(",", items) + "]}";
    }

    private List<Map<String, Object>> services(long visitId) {
        return jdbc.queryForList(
                "SELECT id, reservation_id, service_menu_id, staff_id, cycle_excluded"
                        + " FROM visit_service WHERE visit_id = ? ORDER BY id",
                visitId);
    }

    @Test
    void 같은_메뉴_행은_남기고_새_메뉴는_가장_이른_예약으로_더하고_예측을_다시_계산한다() throws Exception {
        // 늦은 예약을 먼저 만들어 ID 순서와 시작 시각 순서를 다르게 둔다
        long late = reserve(customer, 14);
        long early = reserve(customer, 11);
        visits.completeWithoutPayment(shop, late, null);
        long visitId = visits.completeWithoutPayment(shop, early, null);
        List<Map<String, Object>> before = services(visitId);
        assertThat(before).hasSize(2);

        // 젤 하나만 남기고(스탭 변경 · 주기 계산 제외) 케어를 더한다
        edit(visitId, shop, staff, true, body(item(gel, staff2, true), item(care, staff, false)))
                .andExpect(status().isNoContent());

        List<Map<String, Object>> after = services(visitId);
        assertThat(after).hasSize(2);
        Map<String, Object> keptGel = after.get(0);
        assertThat(keptGel.get("id")).isIn(before.stream().map(s -> s.get("id")).toList());
        assertThat(((Number) keptGel.get("staff_id")).longValue()).isEqualTo(staff2);
        assertThat(keptGel.get("cycle_excluded")).isEqualTo(true);
        Map<String, Object> added = after.get(1);
        assertThat(((Number) added.get("service_menu_id")).longValue()).isEqualTo(care);
        assertThat(((Number) added.get("reservation_id")).longValue()).isEqualTo(early);

        // 케어가 일반 시술이라 근거 방문은 1회로 남는다
        assertThat(basisVisitCount()).isEqualTo(1);

        // 남은 시술을 모두 주기 계산 제외로 바꾸면 그 카테고리는 예측 없음이 된다 (FR-PRD-01 ①)
        edit(visitId, shop, staff, true, body(item(gel, staff2, true), item(care, staff, true)))
                .andExpect(status().isNoContent());
        assertThat(basisVisitCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_service_date FROM customer_prediction WHERE customer_id = ?",
                                LocalDate.class,
                                customer))
                .isNull();
    }

    private int basisVisitCount() {
        return jdbc.queryForObject(
                "SELECT basis_visit_count FROM customer_prediction WHERE customer_id = ?",
                Integer.class,
                customer);
    }

    @Test
    void 고객_없는_방문은_시술은_고치지만_주기_계산_제외는_invalid() throws Exception {
        long visitId =
                visits.close(
                        shop,
                        reserve(null, 11),
                        new Closing(null, 0, PaymentMethod.CASH, null, true),
                        staff);

        edit(visitId, shop, staff, true, body(item(care, staff2, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        edit(visitId, shop, staff, true, body(item(care, staff2, false)))
                .andExpect(status().isNoContent());
        assertThat(services(visitId))
                .singleElement()
                .satisfies(s -> {
                    assertThat(((Number) s.get("service_menu_id")).longValue()).isEqualTo(care);
                    assertThat(((Number) s.get("staff_id")).longValue()).isEqualTo(staff2);
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM customer_prediction WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }

    @Test
    void PIN_없음_빈_목록_가져온_방문_다른_매장을_거부한다() throws Exception {
        long visitId = visits.completeWithoutPayment(shop, reserve(customer, 11), null);
        String ok = body(item(care, staff, false));

        edit(visitId, shop, staff, false, ok)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
        edit(visitId, shop, staff, true, body())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));

        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);
        long otherMenu = data.menu(otherShop, 60);
        edit(visitId, shop, staff, true, body(item(otherMenu, staff, false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        edit(visitId, otherShop, otherStaff, true, body(item(otherMenu, otherStaff, false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        jdbc.update("UPDATE visit SET source = 'IMPORT' WHERE id = ?", visitId);
        edit(visitId, shop, staff, true, ok)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        // 거부된 요청은 시술을 바꾸지 않는다
        assertThat(services(visitId))
                .singleElement()
                .satisfies(s ->
                        assertThat(((Number) s.get("service_menu_id")).longValue()).isEqualTo(gel));
    }
}
