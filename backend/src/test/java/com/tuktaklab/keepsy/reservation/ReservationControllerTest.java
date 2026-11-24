package com.tuktaklab.keepsy.reservation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDateTime;
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

/** 예약 API의 권한 · 요청 검증 · 응답 모양 (CONTRACTS 2 · 3 · 4장). 규칙 자체는 ReservationAppServiceTest가 본다. */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationControllerTest {

    private static final String DAY = "2026-12-01";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired ReservationAppService service;

    private TestShopData data;
    private long shop;
    private long staff;
    private long owner;
    private long care;
    private long restricted;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", false);
        owner = data.staff(shop, "ACTIVE", true);
        care = data.menu(shop, 30);
        restricted = data.customer(shop, true, "노쇼 3회");
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** 매장 로그인 + 현재 근무자 (FR-ACC-02 · 10 기능 대신 세션을 직접 채운다) */
    private MockHttpSession session(long shopId, Long worker) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        return session;
    }

    private String body(Long customerId, String startAt, String menus) {
        return """
        {"customerId": %s, "staffId": %d, "startAt": "%s", "serviceMenuIds": %s}\
        """
                .formatted(customerId, staff, startAt, menus);
    }

    @Test
    void 근무자가_없으면_worker_required() throws Exception {
        mvc.perform(
                        get("/api/reservations/available-times")
                                .param("staffId", String.valueOf(staff))
                                .param("date", DAY)
                                .param("minutes", "60")
                                .session(session(shop, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("worker_required"));
    }

    @Test
    void 예약_가능_시간은_오프셋이_붙은_시각_목록이다() throws Exception {
        mvc.perform(
                        get("/api/reservations/available-times")
                                .param("staffId", String.valueOf(staff))
                                .param("date", DAY)
                                .param("minutes", "60")
                                .session(session(shop, staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTimes[0]").value("2026-12-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.startTimes.length()").value(17));
    }

    @Test
    void 등록하면_201과_id_예정_시술이_없으면_invalid() throws Exception {
        mvc.perform(
                        post("/api/reservations")
                                .session(session(shop, staff))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(null, DAY + "T11:00:00+09:00", "[" + care + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(
                        post("/api/reservations")
                                .session(session(shop, staff))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(null, DAY + "T12:00:00+09:00", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void 예약_제한_고객은_원장_PIN이_있어야_등록된다() throws Exception {
        String req = body(restricted, DAY + "T11:00:00+09:00", "[" + care + "]");
        mvc.perform(
                        post("/api/reservations")
                                .session(session(shop, staff))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(req))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("owner_pin_required"))
                .andExpect(jsonPath("$.error.message").value("예약 제한 고객입니다. 사유: 노쇼 3회"));

        MockHttpSession withOwnerPin = session(shop, staff);
        shopSession.setPin(withOwnerPin, owner, LocalDateTime.now().plusMinutes(5));
        mvc.perform(
                        post("/api/reservations")
                                .session(withOwnerPin)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(req))
                .andExpect(status().isCreated());
    }

    @Test
    void 취소는_204_시작_전_노쇼는_conflict_고객_연결은_제한_안내를_준다() throws Exception {
        long id =
                service.create(
                        shop,
                        new NewReservation(
                                null,
                                staff,
                                LocalDateTime.of(2099, 1, 6, 11, 0),
                                null,
                                null,
                                false,
                                null,
                                List.of(care)),
                        false);
        MockHttpSession s = session(shop, staff);

        mvc.perform(post("/api/reservations/{id}/no-show", id).session(s).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        mvc.perform(
                        put("/api/reservations/{id}/customer", id)
                                .session(s)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\": " + restricted + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value("예약 제한 고객입니다. 사유: 노쇼 3회"));
        mvc.perform(post("/api/reservations/{id}/cancel", id).session(s).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 다른_매장_예약은_not_found() throws Exception {
        long id =
                service.create(
                        shop,
                        new NewReservation(
                                null,
                                staff,
                                LocalDateTime.of(2099, 1, 6, 11, 0),
                                null,
                                null,
                                false,
                                null,
                                List.of(care)),
                        false);
        long otherShop = data.shop();
        long otherStaff = data.staff(otherShop, "ACTIVE", false);

        mvc.perform(
                        post("/api/reservations/{id}/cancel", id)
                                .session(session(otherShop, otherStaff))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }
}
