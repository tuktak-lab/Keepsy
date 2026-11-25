package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tuktaklab.keepsy.common.security.ShopSession;
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
 * 수신 거부 반영 · 처리 안내 (Keepsy #99, CONTRACTS 6장 CMP · CUS, FR-CMP-07 · 11 · 16, F48). 실제 MySQL에서 돈다.
 * 모의 데이터만 쓴다. 가상 발송기 스케줄러(3초마다)가 대기 행을 언제 처리해도 결과가 같게, 안내 행은 상태가 아니라 존재 · 내용 · 발송 예정 시각으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnsubscribeTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopSession shopSession;
    @Autowired UnsubscribeNoticeService notices;
    @Autowired VirtualMessageSender sender;

    private TestShopData data;
    private long shop;
    private long staff;
    private long customer;
    private String phone;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        staff = data.staff(shop, "ACTIVE", true);
        customer = data.customer(shop, false, null);
        phone =
                jdbc.queryForObject(
                        "SELECT phone FROM customer WHERE id = ?", String.class, customer);
        jdbc.update(
                "INSERT INTO consent (shop_id, customer_id, item, agreed, recorded_at, staff_id, method)"
                        + " VALUES (?, ?, 'MARKETING', TRUE, ?, ?, 'TABLET')",
                shop,
                customer,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                staff);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    /** 현재 근무자와 PIN 인증 (가상 휴대폰은 CAMPAIGN_SEND, 기본 PIN) */
    private MockHttpSession session(boolean pin) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(shop, null, List.of())));
        shopSession.setCurrentStaffId(session, staff);
        if (pin) {
            shopSession.setPin(session, staff, LocalDateTime.now().plusMinutes(5));
        }
        return session;
    }

    private ResultActions unsubscribe(String number) throws Exception {
        return mvc.perform(
                post("/api/virtual-phone/unsubscribe")
                        .session(session(true))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + number + "\"}"));
    }

    private List<Map<String, Object>> notices() {
        return jdbc.queryForList(
                "SELECT id, campaign_id, kind, body, recipient_phone,"
                        + " CAST(send_after AS CHAR) send_after, send_status"
                        + " FROM campaign_message WHERE customer_id = ? ORDER BY id",
                customer);
    }

    private List<Map<String, Object>> marketingRows() {
        return jdbc.queryForList(
                "SELECT agreed, withdrawal_path, staff_id, method FROM consent"
                        + " WHERE customer_id = ? AND item = 'MARKETING' ORDER BY id",
                customer);
    }

    @Test
    void 수신거부하면_마케팅이_수신_거부로_철회되고_광고_표기_없는_안내가_가상_수신함으로_간다() throws Exception {
        unsubscribe(phone.replace("-", "")).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = marketingRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1))
                .containsEntry("agreed", false)
                .containsEntry("withdrawal_path", "UNSUBSCRIBE")
                .containsEntry("staff_id", null)
                .containsEntry("method", null);

        assertThat(notices())
                .singleElement()
                .satisfies(n -> {
                    assertThat(n)
                            .containsEntry("campaign_id", null)
                            .containsEntry("kind", "UNSUBSCRIBE_NOTICE")
                            .containsEntry("recipient_phone", phone);
                    assertThat((String) n.get("body"))
                            .startsWith("[모의 매장] ")
                            .endsWith(" 수신 거부가 처리되었습니다.")
                            .doesNotContain("(광고)", "무료수신거부");
                });

        // 낮에 접수한 안내는 바로 보낸다 (야간이면 send_after가 있고 아래 검사에서 다룬다)
        if (notices().get(0).get("send_after") == null) {
            sender.deliverPending();
            assertThat(notices().get(0)).containsEntry("send_status", "SENT");
        }
    }

    @Test
    void 이미_수신_거부된_번호에서_다시_누르면_아무것도_하지_않는다() throws Exception {
        unsubscribe(phone).andExpect(status().isNoContent());
        unsubscribe(phone).andExpect(status().isNoContent());

        assertThat(marketingRows()).hasSize(2);
        assertThat(notices()).hasSize(1);
    }

    @Test
    void 야간_안내는_다음_08시가_될_때까지_발송기가_보내지_않는다() {
        // CI(UTC)와 발송기 시계(서울)가 달라도 아직 오지 않은 밤이 되도록 내일 22:00 접수로 둔다
        LocalDate night = LocalDate.now().plusDays(1);
        notices.send(shop, customer, phone, night.atTime(22, 0));

        Map<String, Object> notice = notices().get(0);
        assertThat(notice.get("send_after").toString()).startsWith(night.plusDays(1) + " 08:00");
        sender.deliverPending();
        assertThat(notices().get(0)).containsEntry("send_status", "PENDING");

        // 08:00이 지나면 보낸다
        jdbc.update(
                "UPDATE campaign_message SET send_after = ? WHERE id = ?",
                LocalDateTime.now().minusDays(1),
                notice.get("id"));
        sender.deliverPending();
        assertThat(notices().get(0)).containsEntry("send_status", "SENT");
    }

    @Test
    void 스탭이_직접_수신_거부로_철회해도_같은_안내가_나가고_다시_하면_이력만_쌓인다() throws Exception {
        String body =
                "{\"item\":\"MARKETING\",\"agreed\":false,\"method\":\"VERBAL\","
                        + "\"withdrawalPath\":\"UNSUBSCRIBE\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(
                            post("/api/customers/{id}/consents", customer)
                                    .session(session(false))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated());
        }

        assertThat(marketingRows()).hasSize(3);
        assertThat(marketingRows().get(1)).containsEntry("staff_id", staff);
        assertThat(notices())
                .singleElement()
                .satisfies(n -> assertThat(n).containsEntry("kind", "UNSUBSCRIBE_NOTICE"));
    }

    @Test
    void 없는_번호는_not_found_형식이_틀리면_invalid_PIN이_없으면_pin_required() throws Exception {
        unsubscribe("010-9999-9999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        unsubscribe("02-000-0000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid"));
        mvc.perform(
                        post("/api/virtual-phone/unsubscribe")
                                .session(session(false))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("pin_required"));

        assertThat(marketingRows()).hasSize(1);
        assertThat(notices()).isEmpty();
    }
}
