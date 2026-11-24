package com.tuktaklab.keepsy.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tuktaklab.keepsy.campaign.MessageSender.Outgoing;
import com.tuktaklab.keepsy.support.TestShopData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발송 기록 저장 · 가상 발송 결과 갱신 (Keepsy #87 완료 조건, ARCHITECTURE 7장). 실제 MySQL에서 돈다. 모의 데이터 · 가짜 번호만 쓴다.
 * 백그라운드 스케줄러가 먼저 처리해도 결과가 같도록 실패 비율은 0% · 100%만 쓴다.
 */
@SpringBootTest
class VirtualMessageSenderDbTest {

    @Autowired MessageSender sender;
    @Autowired VirtualMessageSender virtualSender;
    @Autowired JdbcTemplate jdbc;

    private TestShopData data;
    private long shop;
    private long customer;

    @BeforeEach
    void seed() {
        data = new TestShopData(jdbc);
        shop = data.shop();
        customer = data.customer(shop, false, null);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private void failurePercent(int percent) {
        jdbc.update("UPDATE shop SET virtual_failure_percent = ? WHERE id = ?", percent, shop);
    }

    private Outgoing notice(String phone) {
        return new Outgoing(
                shop,
                null,
                MessageKind.UNSUBSCRIBE_NOTICE,
                customer,
                null,
                phone,
                new ComposedMessage("모의 매장 수신 거부 처리 안내", 40));
    }

    private Map<String, Object> row(String phone) {
        return jdbc.queryForMap(
                "SELECT send_status, failure_reason, result_at, message_type, body"
                        + " FROM campaign_message WHERE shop_id = ? AND recipient_phone = ?",
                shop,
                phone);
    }

    @Test
    void 비율_0이면_맞는_번호는_SENT와_결과_시각_틀린_번호는_FAILED와_사유() {
        failurePercent(0);
        sender.send(List.of(notice("010-0000-1001"), notice("010-12")));

        virtualSender.deliverPending();

        assertThat(row("010-0000-1001"))
                .containsEntry("send_status", "SENT")
                .containsEntry("failure_reason", null)
                .containsEntry("message_type", "SMS")
                .containsEntry("body", "모의 매장 수신 거부 처리 안내");
        assertThat(row("010-0000-1001").get("result_at")).isNotNull();
        assertThat(row("010-12"))
                .containsEntry("send_status", "FAILED")
                .containsEntry("failure_reason", VirtualMessageSender.INVALID_PHONE);
    }

    @Test
    void 비율_100이면_맞는_번호도_무작위_실패() {
        failurePercent(100);
        sender.send(List.of(notice("010-0000-1002")));

        virtualSender.deliverPending();

        assertThat(row("010-0000-1002"))
                .containsEntry("send_status", "FAILED")
                .containsEntry("failure_reason", VirtualMessageSender.RANDOM_FAILURE);
    }

    @Test
    void 이미_결과가_난_기록은_다시_바꾸지_않는다() {
        failurePercent(0);
        sender.send(List.of(notice("010-0000-1003")));
        virtualSender.deliverPending();

        failurePercent(100);
        jdbc.update(
                "INSERT INTO campaign_message (shop_id, kind, customer_id, recipient_phone,"
                        + " message_type, body, send_status) VALUES (?, 'UNSUBSCRIBE_NOTICE', ?,"
                        + " '010-0000-1004', 'SMS', '모의 안내', 'PENDING')",
                shop,
                customer);
        virtualSender.deliverPending();

        assertThat(row("010-0000-1003")).containsEntry("send_status", "SENT");
        assertThat(row("010-0000-1004")).containsEntry("send_status", "FAILED");
    }

    @Test
    void 비용은_매장_가상_단가로_계산하고_기본은_0원() {
        assertThat(sender.cost(shop, 3)).isZero();

        jdbc.update("UPDATE shop SET virtual_unit_price = 15 WHERE id = ?", shop);

        assertThat(sender.cost(shop, 3)).isEqualTo(45);
    }

    @Test
    void 바이트_한도_2000을_넘는_메시지는_저장하지_않는다() {
        Outgoing tooLong =
                new Outgoing(
                        shop,
                        null,
                        MessageKind.UNSUBSCRIBE_NOTICE,
                        customer,
                        null,
                        "010-0000-1005",
                        new ComposedMessage("x".repeat(2_001), 2_001));

        assertThatThrownBy(() -> sender.send(List.of(tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign_message WHERE shop_id = ?",
                                Integer.class,
                                shop))
                .isZero();
    }
}
