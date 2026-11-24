package com.tuktaklab.keepsy.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tuktaklab.keepsy.common.security.ShopSession;
import com.tuktaklab.keepsy.support.TestShopData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
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
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 동의 정정 (Keepsy #49 완료 조건, 결정 F44). 실제 MySQL에서 돌고, 모의 데이터만 쓴다. {@code CUSTOMER_EDIT}는 기본 PIN이라 처리
 * 스탭은 현재 근무자가 아니라 PIN 인증 스탭이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentCorrectionTest {

    private static final LocalDateTime T = LocalDateTime.of(2025, 3, 1, 10, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;

    private TestShopData data;
    private long shop;
    private long worker;
    private long pinStaff;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        worker = data.staff(shop, "ACTIVE", false);
        pinStaff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 잘못_기록한_재동의를_고치면_원래_수신_거부와_그_일시가_돌아온다() throws Exception {
        consent("MARKETING", true, T, "TABLET", null);
        consent("MARKETING", false, T.plusDays(1), null, "UNSUBSCRIBE");
        long wrong = consent("MARKETING", true, T.plusDays(2), "VERBAL", null);

        long id = id(correct(wrong, false, shop, true).andExpect(status().isCreated()));

        assertThat(row(id))
                .containsEntry("agreed", false)
                .containsEntry("withdrawal_path", "UNSUBSCRIBE")
                .containsEntry("recorded_at", T.plusDays(1))
                .containsEntry("method", null)
                .containsEntry("staff_id", pinStaff)
                .containsEntry("corrects_consent_id", wrong);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT staff_id, action FROM access_log WHERE shop_id = ?", shop))
                .isEqualTo(Map.of("staff_id", pinStaff, "action", "UPDATE"));
        consents()
                .andExpect(jsonPath("$.current[2].item").value("MARKETING"))
                .andExpect(jsonPath("$.current[2].agreed").value(false))
                .andExpect(jsonPath("$.history[3].correctsConsentId").value(wrong))
                .andExpect(jsonPath("$.history[2].correctsConsentId").isEmpty());
    }

    @Test
    void 잘못_기록한_철회를_고치면_원래_동의일이_유지되어_재확인_주기가_다시_시작되지_않는다() throws Exception {
        consent("MARKETING", true, T, "TABLET", null);
        long wrong = consent("MARKETING", false, T.plusMonths(6), "VERBAL", "DIRECT");

        long id = id(correct(wrong, true, shop, true).andExpect(status().isCreated()));

        assertThat(row(id))
                .containsEntry("agreed", true)
                .containsEntry("recorded_at", T)
                .containsEntry("method", "TABLET")
                .containsEntry("withdrawal_path", null);
    }

    @Test
    void 앞_행이_없으면_고친_행의_일시에_철회_경로_없이_쌓고_사진_건강_주의사항을_지운다() throws Exception {
        long photo = consent("PHOTO", true, T, "TABLET", null);
        long health = consent("HEALTH", true, T, "PAPER", null);
        jdbc.update(
                "UPDATE customer SET service_caution = '젤 리무버 주의', health_caution = '아세톤 알레르기'"
                        + " WHERE id = ?",
                customer);
        jdbc.update(
                "INSERT INTO visit (shop_id, customer_id, visit_date, source) VALUES (?, ?, ?, 'NORMAL')",
                shop,
                customer,
                LocalDate.of(2026, 11, 1));
        jdbc.update(
                "INSERT INTO photo (shop_id, visit_id, storage_key, uploaded_at)"
                        + " SELECT ?, id, '모의-없는-파일', ? FROM visit WHERE customer_id = ?",
                shop,
                LocalDateTime.of(2026, 11, 1, 12, 0),
                customer);

        long photoFix = id(correct(photo, false, shop, true).andExpect(status().isCreated()));
        correct(health, false, shop, true).andExpect(status().isCreated());

        // 받은 적 없음 (F19): 철회 경로 없는 미동의
        assertThat(row(photoFix))
                .containsEntry("recorded_at", T)
                .containsEntry("method", "TABLET")
                .containsEntry("withdrawal_path", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM photo WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT service_caution, health_caution FROM customer WHERE id = ?",
                                customer))
                .containsEntry("service_caution", "젤 리무버 주의")
                .containsEntry("health_caution", null);
    }

    @Test
    void 최신_행이_아니면_conflict_같은_값_PRIVACY는_invalid_다른_매장_삭제_고객은_not_found() throws Exception {
        long old = consent("PHOTO", true, T, "TABLET", null);
        long latest = consent("PHOTO", false, T.plusDays(1), "VERBAL", "DIRECT");
        long privacy = consent("PRIVACY", true, T, "TABLET", null);

        correct(old, false, shop, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        correct(latest, false, shop, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("agreed"));
        correct(privacy, false, shop, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("item"));
        correct(latest, true, data.shop(), true).andExpect(status().isNotFound());
        correct(Long.MAX_VALUE, true, shop, true).andExpect(status().isNotFound());
        jdbc.update("UPDATE customer SET status = 'DELETED' WHERE id = ?", customer);
        correct(latest, true, shop, true).andExpect(status().isNotFound());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM consent WHERE customer_id = ?",
                                Integer.class,
                                customer))
                .isEqualTo(3);
    }

    @Test
    void PIN_인증이_없으면_pin_required() throws Exception {
        long c = consent("PHOTO", true, T, "TABLET", null);
        correct(c, false, shop, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT agreed, recorded_at, method, withdrawal_path, staff_id, corrects_consent_id"
                        + " FROM consent WHERE id = ?",
                id);
    }

    /** 이력 행. 스탭 없이 들어온 철회(가상 휴대폰 [수신거부])는 method · staff가 null */
    private long consent(
            String item, boolean agreed, LocalDateTime at, String method, String path) {
        Map<String, Object> row = new HashMap<>();
        row.put("shop_id", shop);
        row.put("customer_id", customer);
        row.put("item", item);
        row.put("agreed", agreed);
        row.put("recorded_at", at);
        row.put("staff_id", method == null ? null : worker);
        row.put("method", method);
        row.put("withdrawal_path", path);
        return new SimpleJdbcInsert(jdbc)
                .withTableName("consent")
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(row)
                .longValue();
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id"))
                .longValue();
    }

    private MockHttpSession session(long shopId, boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                shopId, null, List.of())));
        shopSession.setCurrentStaffId(session, worker);
        if (pin) {
            shopSession.setPin(session, pinStaff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private ResultActions correct(long consentId, boolean agreed, long shopId, boolean pin)
            throws Exception {
        return mvc.perform(
                post("/api/consents/{id}/corrections", consentId)
                        .session(session(shopId, pin))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreed\":" + agreed + "}"));
    }

    private ResultActions consents() throws Exception {
        return mvc.perform(
                get("/api/customers/{id}/consents", customer).session(session(shop, false)));
    }
}
