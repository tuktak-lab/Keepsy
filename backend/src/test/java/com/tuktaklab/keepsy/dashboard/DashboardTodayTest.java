package com.tuktaklab.keepsy.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.dashboard.query.DashboardTodayQuery;
import com.tuktaklab.keepsy.dashboard.query.DashboardTodayQuery.Item;
import com.tuktaklab.keepsy.support.TestShopData;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/** 대시보드 오늘 예약 · 마감 안 된 예약 · 이번 주 요약 (FR-DSH-01 · 03 · 05, CONTRACTS 6장). 모의 데이터만 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardTodayTest {

    /** 2026-12-02 수요일 14:00. 이번 주는 11-30(월) ~ 12-06(일) */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 2, 14, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired DashboardTodayQuery query;

    private TestShopData data;
    private long shop;
    private long staff;
    private long menu;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        menu = data.menu(shop, 60);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 오늘_예약은_시간순이고_취소도_넣는다_고객_없는_예약은_customer가_null() {
        jdbc.update("UPDATE customer SET service_caution = '젤 알레르기' WHERE id = ?", customer);
        long late = reservation(shop, customer, NOW.withHour(15), "CONFIRMED");
        long early = reservation(shop, null, NOW.withHour(11), "CONFIRMED");
        long canceled = reservation(shop, customer, NOW.withHour(12), "CANCELED");
        reservation(shop, null, NOW.plusDays(1), "CONFIRMED");

        List<Item> today = query.find(shop, NOW).today();

        assertThat(today).extracting(Item::id).containsExactly(early, canceled, late);
        assertThat(today.get(0).customer()).isNull();
        assertThat(today.get(0).channel()).isEqualTo("NAVER");
        assertThat(today.get(0).services())
                .extracting(s -> s.serviceMenuId())
                .containsExactly(menu);
        assertThat(today.get(2).customer().id()).isEqualTo(customer);
        assertThat(today.get(2).customer().hasCaution()).isTrue();
        assertThat(today.get(2).minutes()).isEqualTo(60);
    }

    @Test
    void 주의사항이_없으면_hasCaution은_false() {
        reservation(shop, customer, NOW.withHour(15), "CONFIRMED");

        assertThat(query.find(shop, NOW).today().get(0).customer().hasCaution()).isFalse();
    }

    @Test
    void 마감_안_된_예약은_시작이_지난_확정_예약만_오래된_순() {
        long lastWeek = reservation(shop, null, NOW.minusDays(7), "CONFIRMED");
        long thisMorning = reservation(shop, customer, NOW.withHour(11), "CONFIRMED");
        reservation(shop, customer, NOW.withHour(10), "COMPLETED");
        reservation(shop, customer, NOW.withHour(12), "CANCELED");
        reservation(shop, customer, NOW.withHour(13), "NO_SHOW");
        reservation(shop, customer, NOW, "CONFIRMED"); // 지금 시작: 아직 지나지 않음
        reservation(shop, customer, NOW.withHour(15), "CONFIRMED");

        assertThat(query.find(shop, NOW).unclosed())
                .extracting(Item::id)
                .containsExactly(lastWeek, thisMorning);
    }

    @Test
    void 이번_주는_월요일부터_7일_상태별_건수() {
        reservation(shop, null, LocalDateTime.of(2026, 11, 29, 11, 0), "CONFIRMED"); // 지난주 일요일
        reservation(shop, null, LocalDateTime.of(2026, 11, 30, 11, 0), "COMPLETED");
        reservation(shop, null, LocalDateTime.of(2026, 11, 30, 13, 0), "NO_SHOW");
        reservation(shop, null, NOW.withHour(11), "CONFIRMED");
        reservation(shop, null, NOW.withHour(12), "CONFIRMED");
        reservation(shop, null, NOW.withHour(13), "CANCELED");
        reservation(shop, null, LocalDateTime.of(2026, 12, 6, 18, 0), "CONFIRMED");
        reservation(shop, null, LocalDateTime.of(2026, 12, 7, 11, 0), "CONFIRMED"); // 다음 주

        List<DashboardTodayQuery.Day> week = query.find(shop, NOW).week();

        assertThat(week)
                .extracting(DashboardTodayQuery.Day::date)
                .containsExactly(
                        LocalDate.of(2026, 11, 30),
                        LocalDate.of(2026, 12, 1),
                        LocalDate.of(2026, 12, 2),
                        LocalDate.of(2026, 12, 3),
                        LocalDate.of(2026, 12, 4),
                        LocalDate.of(2026, 12, 5),
                        LocalDate.of(2026, 12, 6));
        assertThat(week.get(0).counts())
                .isEqualTo(Map.of("CONFIRMED", 0, "COMPLETED", 1, "CANCELED", 0, "NO_SHOW", 1));
        assertThat(week.get(1).counts())
                .isEqualTo(Map.of("CONFIRMED", 0, "COMPLETED", 0, "CANCELED", 0, "NO_SHOW", 0));
        assertThat(week.get(2).counts())
                .isEqualTo(Map.of("CONFIRMED", 2, "COMPLETED", 0, "CANCELED", 1, "NO_SHOW", 0));
        assertThat(week.get(6).counts().get("CONFIRMED")).isEqualTo(1);
    }

    @Test
    void 다른_매장_예약은_나오지_않는다() {
        long other = data.shop();
        long otherStaff = data.staff(other, "ACTIVE", false);
        jdbc.update(
                "INSERT INTO reservation (shop_id, staff_id, start_at, end_at, status, channel,"
                        + " created_at) VALUES (?, ?, ?, ?, 'CONFIRMED', 'STORE', ?)",
                other,
                otherStaff,
                NOW.withHour(11),
                NOW.withHour(12),
                NOW.minusDays(1));

        DashboardTodayQuery.Today result = query.find(shop, NOW);

        assertThat(result.today()).isEmpty();
        assertThat(result.unclosed()).isEmpty();
        assertThat(result.week().get(2).counts().get("CONFIRMED")).isZero();
    }

    @Test
    void 근무자가_없으면_worker_required() throws Exception {
        mvc.perform(get("/api/dashboard/today").session(session(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }

    @Test
    void 로그인하지_않으면_unauthorized() throws Exception {
        mvc.perform(get("/api/dashboard/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void 응답_모양() throws Exception {
        long past = reservation(shop, customer, LocalDateTime.of(2020, 1, 6, 11, 0), "CONFIRMED");

        mvc.perform(get("/api/dashboard/today").session(session(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").isArray())
                .andExpect(jsonPath("$.unclosed[0].id").value(past))
                .andExpect(jsonPath("$.unclosed[0].startAt").value("2020-01-06T11:00:00+09:00"))
                .andExpect(jsonPath("$.unclosed[0].customer.hasCaution").value(false))
                .andExpect(jsonPath("$.unclosed[0].customer.phone").doesNotExist())
                .andExpect(jsonPath("$.unclosed[0].staff.id").value(staff))
                .andExpect(jsonPath("$.unclosed[0].services[0].name").value("젤"))
                .andExpect(jsonPath("$.week.length()").value(7))
                .andExpect(jsonPath("$.week[0].date").isString())
                .andExpect(jsonPath("$.week[0].counts.NO_SHOW").value(0));
    }

    /** 매장 로그인 + 현재 근무자 (세션을 직접 채운다) */
    private MockHttpSession session(Long worker) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        return session;
    }

    /** 1시간 예약 + 예정 시술 하나. 고객 없는 예약은 예약 경로 NAVER */
    private long reservation(long shopId, Long customerId, LocalDateTime start, String status) {
        Object[] args = {
            shopId,
            customerId,
            staff,
            start,
            start.plusHours(1),
            status,
            customerId == null ? "NAVER" : "STORE",
            start.minusDays(1)
        };
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(
                                    "INSERT INTO reservation (shop_id, customer_id, staff_id,"
                                            + " start_at, end_at, status, channel, created_at)"
                                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    return ps;
                },
                key);
        long id = key.getKey().longValue();
        jdbc.update(
                "INSERT INTO reservation_service (shop_id, reservation_id, service_menu_id)"
                        + " VALUES (?, ?, ?)",
                shopId,
                id,
                menu);
        return id;
    }
}
