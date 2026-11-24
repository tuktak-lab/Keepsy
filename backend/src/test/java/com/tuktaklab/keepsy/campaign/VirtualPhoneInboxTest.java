package com.tuktaklab.keepsy.campaign;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 가상 수신함 (Keepsy #98, CONTRACTS 6장 CMP, F23). 실제 MySQL에서 돈다. 모의 데이터 · 가짜 번호만 쓴다. 가상 발송기 스케줄러가
 * PENDING 행을 먼저 처리해도 수신함에 나오지 않도록 매장 실패 비율을 100%로 둔다 (PENDING → FAILED).
 */
@SpringBootTest
@AutoConfigureMockMvc
class VirtualPhoneInboxTest {

    private static final String PHONE = "010-0000-7001";
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 20, 10, 0);

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
        staff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
        jdbc.update("UPDATE shop SET virtual_failure_percent = 100 WHERE id = ?", shop);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private long message(
            long shopId,
            String phone,
            String kind,
            String status,
            boolean control,
            LocalDateTime resultAt) {
        jdbc.update(
                "INSERT INTO campaign_message (shop_id, kind, customer_id, control_group,"
                        + " recipient_phone, message_type, body, send_status, result_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'SMS', ?, ?, ?)",
                shopId,
                kind,
                customer,
                control,
                phone,
                "모의 " + kind + " " + resultAt,
                status,
                resultAt);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM campaign_message WHERE shop_id = ?", Long.class, shopId);
    }

    private ResultActions inbox(boolean pin, String phone, String... pageAndSize) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        var req = get("/api/virtual-phone/messages").param("phone", phone).session(session);
        if (pageAndSize.length == 2) {
            req.param("page", pageAndSize[0]).param("size", pageAndSize[1]);
        }
        return mvc.perform(req);
    }

    @Test
    void 받은_메시지만_종류와_상관없이_받은_시각_최신순으로_준다() throws Exception {
        long ad = message(shop, PHONE, "AD", "SENT", false, T);
        long notice = message(shop, PHONE, "UNSUBSCRIBE_NOTICE", "SENT", false, T.plusDays(1));
        message(shop, PHONE, "AD", "FAILED", false, T.plusDays(2));
        message(shop, PHONE, "AD", "PENDING", false, null);
        message(shop, PHONE, "AD", null, true, null); // 대조군
        message(shop, "010-0000-7002", "AD", "SENT", false, T.plusDays(3)); // 다른 번호
        message(data.shop(), PHONE, "AD", "SENT", false, T.plusDays(4)); // 다른 매장

        // 하이픈 없이 보내도 같은 번호
        inbox(true, "01000007001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) notice, (int) ad)))
                .andExpect(jsonPath("$.items[0].kind").value("UNSUBSCRIBE_NOTICE"))
                .andExpect(jsonPath("$.items[0].messageType").value("SMS"))
                .andExpect(jsonPath("$.items[0].receivedAt").value("2026-11-21T10:00:00+09:00"))
                .andExpect(jsonPath("$.items[1].body").value("모의 AD " + T))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void 페이지로_나눠_준다() throws Exception {
        message(shop, PHONE, "AD", "SENT", false, T);
        long newest = message(shop, PHONE, "AD", "SENT", false, T.plusDays(1));

        inbox(true, PHONE, "0", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) newest)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 번호_형식이나_페이지_값이_틀리면_invalid_400() throws Exception {
        for (String phone : List.of("010-123", "02-0000-0000", "0100000700a")) {
            inbox(true, phone)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("invalid"));
        }
        inbox(true, PHONE, "0", "101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
    }

    @Test
    void PIN_인증이_없으면_pin_required_403() throws Exception {
        inbox(false, PHONE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));
    }
}
